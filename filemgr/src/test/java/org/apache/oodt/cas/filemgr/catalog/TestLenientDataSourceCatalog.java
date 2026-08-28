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
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.commons.database.DatabaseConnectionBuilder;
import org.apache.oodt.commons.database.SqlScript;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import javax.sql.DataSource;

import static org.junit.Assert.*;

/**
 * Round trips through {@link LenientDataSourceCatalog}, which is what
 * surfaced both of its defects: the write path reported success either way,
 * so nothing short of storing and reading back could see them.
 */
public class TestLenientDataSourceCatalog {

    private DataSource ds;
    private LenientDataSourceCatalog catalog;

    @Before
    public void setUp() throws Exception {
        File tempFile = File.createTempFile("foo", "bar");
        tempFile.deleteOnExit();
        String tmpDirPath = tempFile.getParentFile().getAbsolutePath();

        ds = DatabaseConnectionBuilder.buildDataSource("sa", "",
                "org.hsqldb.jdbcDriver",
                "jdbc:hsqldb:file:" + tmpDirPath + "/testLenientCat;shutdown=true");

        File schema = new File(getClass().getResource("/testcat.sql").getFile());
        SqlScript script = new SqlScript(schema.getAbsolutePath(), ds);
        script.loadScript();
        script.execute();
        ds.getConnection().commit();

        // No validation layer: the lenient catalog's reason for existing is
        // that it accepts keys nobody declared.
        catalog = new LenientDataSourceCatalog(ds, null, true, 20, 0L, false, false);
    }

    @After
    public void tearDown() throws Exception {
        ds.getConnection().close();
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
        return p;
    }

    /**
     * The catalog stores product_type_id and does not resolve the type's name
     * on read -- the file manager does that from the repository manager. The
     * metadata table is named after it, so a test reading straight from the
     * catalog has to put it back.
     */
    private Product readBack(String id) throws Exception {
        Product p = catalog.getProductById(id);
        p.setProductType(genericFile());
        return p;
    }

    private String store(String name, String key, String value) throws Exception {
        Product p = product(name);
        catalog.addProduct(p);
        Metadata met = new Metadata();
        met.addMetadata(key, value);
        catalog.addMetadata(met, p);
        return p.getProductId();
    }

    /**
     * addMetadataValue appended the Map.Entry rather than its key, so
     * Entry.toString() went into element_id: the column held
     * "Filename=Filename". The store reported success, so only reading back
     * shows it.
     */
    @Test
    public void testMetadataRoundTripsUnderItsOwnKey() throws Exception {
        String id = store("p0", "0", "0");

        Metadata read = catalog.getMetadata(readBack(id));

        assertNotNull(read);
        assertTrue("the key is not stored under its own name: " + read.getAllKeys(),
                read.containsKey("0"));
        assertEquals("0", read.getMetadata("0"));
    }

    /** No key comes back carrying an '=' from Entry.toString(). */
    @Test
    public void testNoStoredKeyCarriesAnEqualsSign() throws Exception {
        String id = store("p1", "Filename", "data.dat");

        for (String key : catalog.getMetadata(readBack(id)).getAllKeys()) {
            assertFalse("Entry.toString() was stored as the key: " + key,
                    key.contains("="));
        }
    }

    /**
     * removeMetadataValue already used getKey(), so it deleted by an id
     * addMetadataValue had never written -- the delete matched nothing and
     * the row survived.
     */
    @Test
    public void testRemoveMetadataDeletesTheRowItWrote() throws Exception {
        String id = store("p2", "Doomed", "value");
        Product p = readBack(id);

        Metadata toRemove = new Metadata();
        toRemove.addMetadata("Doomed", "value");
        catalog.removeMetadata(toRemove, p);

        assertFalse("the row survived the delete",
                catalog.getMetadata(readBack(id)).containsKey("Doomed"));
    }

    /**
     * getSqlQuery built four StringBuilders and threw them away, so it
     * returned only the fragment "metadata_value = 'x'", which paginateQuery
     * then executed as a whole statement: every criteria-bearing query
     * against a lenient catalog failed.
     */
    @Test
    public void testASingleTermQueryReturnsTheProduct() throws Exception {
        String id = store("p3", "Instrument", "AIRS");

        Query query = new Query();
        query.addCriterion(new TermQueryCriteria("Instrument", "AIRS"));
        List<String> ids = catalog.query(query, genericFile());

        assertNotNull(ids);
        assertTrue("the query returned nothing for a product it stored",
                ids.contains(id));
    }

    /** A term that matches nothing returns nothing, rather than throwing. */
    @Test
    public void testATermQueryThatMatchesNothingReturnsNothing() throws Exception {
        store("p4", "Instrument", "AIRS");

        Query query = new Query();
        query.addCriterion(new TermQueryCriteria("Instrument", "MODIS"));

        assertTrue(catalog.query(query, genericFile()).isEmpty());
    }

    /** and a query with no criteria still works. */
    @Test
    public void testAQueryWithNoCriteriaStillWorks() throws Exception {
        String id = store("p5", "Instrument", "AIRS");

        assertTrue(catalog.query(new Query(), genericFile()).contains(id));
    }
}
