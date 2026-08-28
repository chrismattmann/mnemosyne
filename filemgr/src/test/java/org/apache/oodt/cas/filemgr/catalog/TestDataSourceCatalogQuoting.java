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
import org.apache.oodt.cas.filemgr.structs.ProductPage;
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
 * Apostrophes in catalogued values, and the mismatch between how results are
 * counted and how they are selected.
 *
 * Uses the lenient catalog because it accepts keys with no validation layer;
 * the quoting and paging code under test is DataSourceCatalog's, inherited
 * unchanged.
 */
public class TestDataSourceCatalogQuoting {

    private DataSource ds;
    private LenientDataSourceCatalog catalog;

    @Before
    public void setUp() throws Exception {
        File tempFile = File.createTempFile("foo", "bar");
        tempFile.deleteOnExit();
        String tmpDirPath = tempFile.getParentFile().getAbsolutePath();

        ds = DatabaseConnectionBuilder.buildDataSource("sa", "",
                "org.hsqldb.jdbcDriver",
                "jdbc:hsqldb:file:" + tmpDirPath + "/testQuotingCat;shutdown=true");

        File schema = new File(getClass().getResource("/testcat.sql").getFile());
        SqlScript script = new SqlScript(schema.getAbsolutePath(), ds);
        script.loadScript();
        script.execute();
        ds.getConnection().commit();

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

    private Product readBack(String id) throws Exception {
        Product p = catalog.getProductById(id);
        p.setProductType(genericFile());
        return p;
    }

    private String store(String name, String key, String value) throws Exception {
        Product p = Product.getDefaultFlatProduct(name, "urn:oodt:GenericFile");
        p.getProductType().setName("GenericFile");
        catalog.addProduct(p);
        Metadata met = new Metadata();
        met.addMetadata(key, value);
        catalog.addMetadata(met, p);
        return p.getProductId();
    }

    /**
     * The worst of the group, because nothing failed: addMetadataValue
     * concatenated the value in unescaped, addMetadata caught per value and
     * carried on, so the product was catalogued, the ingest reported
     * successful, and the field was simply missing from the archive record.
     * The shrunk counterexample is the single character '.
     */
    @Test
    public void testAMetadataValueContainingAnApostropheIsStored() throws Exception {
        String id = store("p0", "Description", "'");

        Metadata read = catalog.getMetadata(readBack(id));

        assertEquals("the value was silently discarded", "'",
                read.getMetadata("Description"));
    }

    /** Ordinary text carrying one. */
    @Test
    public void testAnOrdinaryValueWithAnApostropheIsStored() throws Exception {
        String id = store("p1", "Observer", "O'Brien");

        assertEquals("O'Brien",
                catalog.getMetadata(readBack(id)).getMetadata("Observer"));
    }

    /** A value carrying SQL is stored as text rather than executed. */
    @Test
    public void testAValueCarryingSqlIsStoredAsText() throws Exception {
        String hostile = "x'); DROP TABLE products; --";
        String id = store("p2", "Hostile", hostile);

        assertEquals(hostile,
                catalog.getMetadata(readBack(id)).getMetadata("Hostile"));
        assertNotNull("the products table did not survive", catalog.getProductById(id));
    }

    /**
     * O'Brien.txt is a legal filename, and the caller has no escaping hook.
     * This one failed loudly rather than silently.
     */
    @Test
    public void testAProductNameContainingAnApostropheIsStored() throws Exception {
        String id = store("O'Brien.txt", "Key", "value");

        assertEquals("O'Brien.txt", catalog.getProductById(id).getProductName());
    }

    /**
     * A value that cannot be stored used to be logged at WARNING and skipped,
     * so the ingest reported success with a field missing. It fails now.
     */
    @Test
    public void testAValueThatCannotBeStoredFailsTheIngest() throws Exception {
        Product p = Product.getDefaultFlatProduct("p3", "urn:oodt:GenericFile");
        p.getProductType().setName("NoSuchProductType");
        catalog.addProduct(p);

        Metadata met = new Metadata();
        met.addMetadata("Key", "value");
        try {
            catalog.addMetadata(met, p);
            fail("a metadata value that could not be stored was reported as ingested");
        } catch (Exception expected) {
            // the ingest fails rather than losing the field quietly
        }
    }

    /**
     * Counting used LIKE '%x%' while selecting used = 'x', so a term that is
     * a substring of another stored value was counted and not returned:
     * paging offered pages it could not fill.
     */
    @Test
    public void testTheResultCountMatchesWhatTheQueryReturns() throws Exception {
        store("sub0", "Instrument", "AIRS");
        store("sub1", "Instrument", "AIRS-2");
        store("sub2", "Instrument", "PRE-AIRS");

        Query query = new Query();
        query.addCriterion(new TermQueryCriteria("Instrument", "AIRS"));

        List<String> returned = catalog.query(query, genericFile());
        ProductPage page = catalog.pagedQuery(query, genericFile(), 1);

        assertEquals("exactly one product has Instrument = AIRS", 1, returned.size());
        assertEquals("the count does not match what the query returns",
                returned.size(), page.getPageProducts().size());
    }

    /** and paging reports no more pages than it can fill. */
    @Test
    public void testPagingDoesNotOfferAPageItCannotFill() throws Exception {
        store("f0", "Instrument", "AIRS");
        store("f1", "Instrument", "AIRS-2");

        Query query = new Query();
        query.addCriterion(new TermQueryCriteria("Instrument", "AIRS"));

        ProductPage page = catalog.pagedQuery(query, genericFile(), 1);

        assertEquals(1, page.getTotalPages());
        assertEquals(1, page.getPageProducts().size());
    }
}
