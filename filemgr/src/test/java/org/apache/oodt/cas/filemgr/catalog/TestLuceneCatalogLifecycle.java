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
import java.util.Vector;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.lucene.index.IndexWriter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * What {@link LuceneCatalog} holds open, and when it lets go: #16 and #147.
 */
public class TestLuceneCatalogLifecycle {

    private final List<LuceneCatalog> opened = new ArrayList<LuceneCatalog>();
    private final List<File> directories = new ArrayList<File>();

    @Before
    public void setUp() {
        System.setProperty("org.apache.oodt.cas.filemgr.catalog.datasource.lenientFields",
                "false");
    }

    @After
    public void tearDown() throws Exception {
        for (LuceneCatalog catalog : opened) {
            try {
                catalog.close();
            } catch (Exception ignore) {
                // a test may have closed it already
            }
        }
        opened.clear();
        for (File dir : directories) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
        directories.clear();
    }

    private LuceneCatalog catalogIn(File dir) {
        LuceneCatalog catalog = new LuceneCatalog(dir.getAbsolutePath(), null,
                20, 60L, 60L, 20);
        opened.add(catalog);
        return catalog;
    }

    private File freshIndexDirectory() throws Exception {
        File dir = Files.createTempDirectory("luceneCat").toFile();
        directories.add(dir);
        return dir;
    }

    private static Product product(String name) {
        Product p = Product.getDefaultFlatProduct(name, "urn:oodt:GenericFile");
        p.getProductType().setName("GenericFile");
        // A product reaches the index only once it has both metadata and
        // references carrying a data store reference; see hasMetadataAndRefs.
        Vector<Reference> references = new Vector<Reference>();
        references.add(new Reference("file:///" + name + ".txt",
                "file:///archive/" + name + ".txt", 100));
        p.setProductReferences(references);
        return p;
    }

    private static Metadata metadataFor(String name) {
        Metadata met = new Metadata();
        met.addMetadata("CAS.ProductName", name);
        return met;
    }

    /** Stores a product completely, which is what puts it in the index. */
    private static String store(LuceneCatalog catalog, Product p) throws Exception {
        catalog.addProduct(p);
        catalog.addProductReferences(p);
        catalog.addMetadata(metadataFor(p.getProductName()), p);
        return p.getProductId();
    }

    private static ProductType genericFile() {
        ProductType type = new ProductType();
        type.setName("GenericFile");
        type.setProductTypeId("urn:oodt:GenericFile");
        return type;
    }

    /**
     * removeProduct deleted the document and left the cache entry, so a
     * removed id still resolved from cache and re-adding it threw "already
     * existed" for a product the catalog no longer held.
     */
    @Test
    public void testRemovingAProductEvictsItFromTheCache() throws Exception {
        LuceneCatalog catalog = catalogIn(freshIndexDirectory());

        // addProduct caches the product until metadata and references
        // complete it; addProduct then refuses any id the cache holds.
        Product p = product("removeme");
        catalog.addProduct(p);
        assertNotNull(p.getProductId());

        catalog.removeProduct(p);

        // The re-add is the assertion. removeProduct deleted the document
        // and left the cache entry, so this threw "Attempt to add a product
        // that already existed" for a product the catalog no longer held.
        catalog.addProduct(p);

        // and it can still be completed and read back
        assertNotNull(catalog.getProductById(
                store(catalog, product("removeme-again"))));
    }

    /** A fully stored product can be removed and stored again too. */
    @Test
    public void testAStoredProductCanBeRemovedAndStoredAgain() throws Exception {
        LuceneCatalog catalog = catalogIn(freshIndexDirectory());

        Product first = product("cycle");
        String id = store(catalog, first);
        assertNotNull(catalog.getProductById(id));

        catalog.removeProduct(first);

        Product again = product("cycle");
        assertNotNull(catalog.getProductById(store(catalog, again)));
    }

    /**
     * The cache was static, so every catalog in the JVM shared one whatever
     * index directory it was pointed at, and two catalogs over unrelated
     * indexes saw each other's products.
     */
    @Test
    public void testTwoCatalogsOverDifferentIndexesDoNotShareACache() throws Exception {
        LuceneCatalog one = catalogIn(freshIndexDirectory());
        LuceneCatalog two = catalogIn(freshIndexDirectory());

        Product onlyInOne = product("only-in-one");
        one.addProduct(onlyInOne);

        Field cache = LuceneCatalog.class.getDeclaredField("catalogCache");
        cache.setAccessible(true);
        assertFalse("the two catalogs are sharing one cache",
                cache.get(one) == cache.get(two));

        assertEquals("a product added to one catalog is visible in the other",
                0, ((java.util.Map<?, ?>) cache.get(two)).size());
    }

    /**
     * The writer is now held for the catalog's life, which is the point of
     * #16 -- so close() has to give the write lock back, or the next catalog
     * over that directory cannot open one.
     */
    @Test
    public void testClosingReleasesTheIndexWriterLock() throws Exception {
        File dir = freshIndexDirectory();

        LuceneCatalog first = catalogIn(dir);
        store(first, product("before"));
        first.close();

        LuceneCatalog second = catalogIn(dir);
        String id = store(second, product("after"));
        assertNotNull("the second catalog could not write to the directory "
                + "the first released", second.getProductById(id));
    }

    /** Closing twice is harmless; shutdown paths call it more than once. */
    @Test
    public void testClosingTwiceIsHarmless() throws Exception {
        LuceneCatalog catalog = catalogIn(freshIndexDirectory());
        store(catalog, product("a"));

        catalog.close();
        catalog.close();
    }

    /**
     * One writer for the catalog, not one per document. Opening a writer
     * reads the segment infos and takes the directory's write lock, and
     * closing it commits and fsyncs; the old code did both around every
     * single addDocument.
     */
    @Test
    public void testTheWriterIsReusedAcrossWrites() throws Exception {
        LuceneCatalog catalog = catalogIn(freshIndexDirectory());

        Field writerField = LuceneCatalog.class.getDeclaredField("writer");
        writerField.setAccessible(true);

        store(catalog, product("first"));
        IndexWriter afterFirst = (IndexWriter) writerField.get(catalog);
        assertNotNull(afterFirst);

        store(catalog, product("second"));
        store(catalog, product("third"));

        assertSame("a new writer was opened for each document",
                afterFirst, writerField.get(catalog));
        assertTrue("the writer was closed between documents", afterFirst.isOpen());
    }

    /** and the products are all readable, so the commits did happen. */
    @Test
    public void testEveryWrittenProductIsReadable() throws Exception {
        LuceneCatalog catalog = catalogIn(freshIndexDirectory());

        String a = store(catalog, product("a"));
        String b = store(catalog, product("b"));
        String c = store(catalog, product("c"));

        assertNotNull(catalog.getProductById(a));
        assertNotNull(catalog.getProductById(b));
        assertNotNull(catalog.getProductById(c));
        assertEquals(3, catalog.getNumProducts(genericFile()));
    }

    /**
     * Six read methods opened a DirectoryReader and left the close as
     * //TODO CLOSE, so a file manager leaked a handle per read and the
     * failure arrived far from its cause as some unrelated open reporting
     * "Too many open files".
     *
     * Counted through /proc, which means this asserts on Linux -- where CI
     * runs -- and is skipped elsewhere. Exhausting the descriptors instead
     * would take the surefire fork down and every unrelated result with it,
     * which is why #147 stated this rather than testing it.
     */
    @Test
    public void testRepeatedReadsDoNotLeakFileHandles() throws Exception {
        File fdDir = new File("/proc/self/fd");
        if (!fdDir.isDirectory()) {
            return;
        }

        LuceneCatalog catalog = catalogIn(freshIndexDirectory());
        String id = store(catalog, product("readme"));

        // settle first: the first reads open whatever is opened once
        for (int i = 0; i < 20; i++) {
            catalog.getProductById(id);
        }
        int before = fdDir.list().length;

        for (int i = 0; i < 400; i++) {
            catalog.getProductById(id);
        }
        int after = fdDir.list().length;

        assertTrue("400 reads left " + (after - before) + " file descriptors "
                + "open; readers are not being closed",
                after - before < 40);
    }
}
