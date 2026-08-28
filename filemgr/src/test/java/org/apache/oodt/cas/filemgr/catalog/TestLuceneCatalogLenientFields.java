/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oodt.cas.filemgr.catalog;

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.metadata.Metadata;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import static org.junit.Assert.*;

/**
 * A Lucene catalog with no validation layer -- lucene.lenientFields=true.
 *
 * toCompleteProduct copied every stored field into the returned Metadata,
 * including the catalog's own reserved names. modifyProduct hands that
 * Metadata straight back to toDoc, which writes a SortedDocValuesField for
 * each key it is given, while the documents already in the index carry those
 * same names without doc values. Lucene rejects the inconsistency, so the
 * second product ingested failed and every one after it.
 *
 * FileManager.setProductTransferStatus calls modifyProduct on every
 * server-side ingest, so such a deployment ingested exactly one product and
 * then stopped.
 */
public class TestLuceneCatalogLenientFields {

    private final List<LuceneCatalog> opened = new ArrayList<LuceneCatalog>();
    private File indexDir;

    @Before
    public void setUp() throws Exception {
        indexDir = Files.createTempDirectory("lenientCat").toFile();
    }

    @After
    public void tearDown() throws Exception {
        for (LuceneCatalog catalog : opened) {
            try {
                catalog.close();
            } catch (Exception ignore) {
            }
        }
        opened.clear();
        File[] files = indexDir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        indexDir.delete();
    }

    /** No validation layer: that is what lenientFields means. */
    private LuceneCatalog catalog() {
        LuceneCatalog catalog = new LuceneCatalog(indexDir.getAbsolutePath(),
                null, 20, 60L, 60L, 20);
        opened.add(catalog);
        return catalog;
    }

    private static ProductType genericFile() {
        ProductType type = new ProductType();
        type.setName("GenericFile");
        type.setProductTypeId("urn:oodt:GenericFile");
        return type;
    }

    private static Product product(String name) {
        Product p = Product.getDefaultFlatProduct(name, "urn:oodt:GenericFile");
        p.getProductType().setName("GenericFile");
        Vector<Reference> refs = new Vector<Reference>();
        refs.add(new Reference("file:///" + name + ".txt",
                "file:///archive/" + name + ".txt", 100L, null));
        p.setProductReferences(refs);
        return p;
    }

    private static String store(LuceneCatalog catalog, String name)
            throws Exception {
        Product p = product(name);
        catalog.addProduct(p);
        catalog.addProductReferences(p);
        Metadata met = new Metadata();
        met.addMetadata("CAS.ProductName", name);
        catalog.addMetadata(met, p);
        return p.getProductId();
    }

    /** The shrunk sequence from the issue: ADD, ADD, MODIFY. */
    @Test
    public void testACatalogWithTwoProductsCanStillBeModified() throws Exception {
        LuceneCatalog catalog = catalog();
        store(catalog, "first");
        String id = store(catalog, "second");

        Product stored = catalog.getProductById(id);
        stored.setProductType(genericFile());
        stored.setTransferStatus(Product.STATUS_RECEIVED);

        // This is what setProductTransferStatus does on every ingest.
        catalog.modifyProduct(stored);

        assertEquals(Product.STATUS_RECEIVED,
                catalog.getProductById(id).getTransferStatus());
    }

    /** And it keeps working past two, which is where it used to stop. */
    @Test
    public void testProductsKeepBeingIngestable() throws Exception {
        LuceneCatalog catalog = catalog();
        for (int i = 0; i < 5; i++) {
            String id = store(catalog, "p" + i);
            Product stored = catalog.getProductById(id);
            stored.setProductType(genericFile());
            stored.setTransferStatus(Product.STATUS_RECEIVED);
            catalog.modifyProduct(stored);
        }

        assertEquals(5, catalog.getNumProducts(genericFile()));
    }

    /**
     * The catalog's own field names are storage detail. Handing them back as
     * product metadata is what caused the above, and they are not something a
     * caller put there.
     */
    @Test
    public void testReservedFieldNamesAreNotReturnedAsMetadata() throws Exception {
        LuceneCatalog catalog = catalog();
        String id = store(catalog, "reserved");

        Product stored = catalog.getProductById(id);
        stored.setProductType(genericFile());
        Metadata met = catalog.getMetadata(stored);

        for (String reserved : new String[] { "product_id", "product_name",
                "product_structure", "product_transfer_status",
                "product_type_id", "product_type_name", "reference_orig",
                "reference_data_store", "reference_fileSize",
                "reference_mimeType", "myfield" }) {
            assertFalse("the catalog returned its own field [" + reserved
                    + "] as product metadata", met.containsKey(reserved));
        }
    }

    /** The metadata a caller actually stored still comes back. */
    @Test
    public void testStoredMetadataStillComesBack() throws Exception {
        LuceneCatalog catalog = catalog();
        String id = store(catalog, "keeps-its-metadata");

        Product stored = catalog.getProductById(id);
        stored.setProductType(genericFile());

        assertEquals("keeps-its-metadata",
                catalog.getMetadata(stored).getMetadata("CAS.ProductName"));
    }
}
