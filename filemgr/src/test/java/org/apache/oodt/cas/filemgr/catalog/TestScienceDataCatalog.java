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
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.commons.database.DatabaseConnectionBuilder;
import org.apache.oodt.commons.database.SqlScript;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import static org.junit.Assert.*;

/**
 * {@link ScienceDataCatalog} against a standards-compliant database.
 *
 * There was no test for this class at all, and it could not have had one:
 * the schema it targets was MySQL-only -- backtick identifiers, int(10)
 * unsigned, auto_increment, ENGINE=MyISAM -- so the tables could not be
 * created anywhere a test would want to create them. Its write path was
 * MySQL-only for the same reason.
 *
 * This runs the whole of it on HSQLDB: schema, ingest, read back.
 */
public class TestScienceDataCatalog {

    private DataSource ds;
    private ScienceDataCatalog catalog;

    @Before
    public void setUp() throws Exception {
        File tempFile = File.createTempFile("foo", "bar");
        tempFile.deleteOnExit();
        String tmpDirPath = tempFile.getParentFile().getAbsolutePath();

        ds = DatabaseConnectionBuilder.buildDataSource("sa", "",
                "org.hsqldb.jdbcDriver",
                "jdbc:hsqldb:file:" + tmpDirPath + "/testSdCat;shutdown=true");

        // Best-effort clean: the file database outlives a single test run.
        Connection conn = ds.getConnection();
        Statement drop = conn.createStatement();
        for (String table : new String[] { "dataPoint", "dpMap", "parameter",
                                           "granule", "dataset" }) {
            try {
                drop.execute("DROP TABLE " + table);
            } catch (Exception alreadyGone) {
                // first run
            }
        }
        drop.close();
        conn.close();

        File schema = new File(getClass().getResource("/sdschema.sql").getFile());
        SqlScript script = new SqlScript(schema.getAbsolutePath(), ds);
        script.loadScript();
        script.execute();
        ds.getConnection().commit();

        seedDataset();
        catalog = new ScienceDataCatalog(ds, null, 20);
    }

    @After
    public void tearDown() throws Exception {
        ds.getConnection().close();
    }

    private void seedDataset() throws Exception {
        Connection conn = ds.getConnection();
        Statement s = conn.createStatement();
        s.execute("INSERT INTO dataset (dataset_id, longName, shortName) "
                + "VALUES (1, 'A Test Dataset', 'test')");
        conn.commit();
        s.close();
        conn.close();
    }

    private static Metadata granuleMetadata(String filename, String... points) {
        Metadata m = new Metadata();
        m.addMetadata("dataset_id", "1");
        m.addMetadata("granule_filename", filename);
        m.addMetadata("param_airTemperature", "airTemperature");
        for (String point : points) {
            m.addMetadata("data_airTemperature", point);
        }
        return m;
    }

    /** "lat,lon,vertical,time,value" with the compact timestamp these carry. */
    private static String point(String day, String value) {
        return "34.05,-118.24,0.0,2026010" + day + "T1200," + value;
    }

    private static Product granuleProduct(String name) {
        Product p = Product.getDefaultFlatProduct(name, "urn:oodt:GenericFile");
        p.getProductType().setName("GenericFile");
        return p;
    }

    private int countOf(String table) throws Exception {
        Connection conn = ds.getConnection();
        try {
            Statement s = conn.createStatement();
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table);
            assertTrue(rs.next());
            return rs.getInt(1);
        } finally {
            conn.close();
        }
    }

    /** The schema itself is the first thing that had to be portable. */
    @Test
    public void testTheSchemaLoadsOnAStandardsCompliantDatabase() throws Exception {
        assertEquals(1, countOf("dataset"));
        assertEquals(0, countOf("granule"));
        assertEquals(0, countOf("dataPoint"));
    }

    @Test
    public void testMetadataIsIngested() throws Exception {
        catalog.addMetadata(granuleMetadata("granule-one.nc",
                point("1", "12.5"), point("2", "13.5")), granuleProduct("g1"));

        assertEquals(1, countOf("granule"));
        assertEquals(1, countOf("parameter"));
        assertEquals(2, countOf("dataPoint"));
    }

    /**
     * The batch used to be a single multi-row INSERT ... VALUES (..),(..),
     * flushed every hundred rows -- a MySQL extension HSQLDB rejects with
     * "Unexpected token: ,". This crosses the flush boundary.
     */
    @Test
    public void testABatchLargerThanOneFlushIsWritten() throws Exception {
        String[] points = new String[250];
        for (int i = 0; i < points.length; i++) {
            points[i] = "34.05,-118.24,0.0,20260101T1200," + i;
        }

        catalog.addMetadata(granuleMetadata("granule-big.nc", points),
                granuleProduct("big"));

        assertEquals(250, countOf("dataPoint"));
    }

    /**
     * The timestamp was wrapped in double quotes, which are an identifier
     * quote everywhere but MySQL, so the value was read as a column name.
     * Bound as a Timestamp now.
     */
    @Test
    public void testTheTimestampIsStoredAsATime() throws Exception {
        catalog.addMetadata(granuleMetadata("granule-time.nc",
                "34.05,-118.24,0.0,20260315T0945,7.5"), granuleProduct("t"));

        Connection conn = ds.getConnection();
        try {
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT time FROM dataPoint");
            assertTrue(rs.next());
            assertNotNull("the timestamp was not stored", rs.getTimestamp(1));
            assertEquals("2026-03-15", rs.getTimestamp(1).toString().substring(0, 10));
        } finally {
            conn.close();
        }
    }

    /** A granule already recorded is not recorded twice. */
    @Test
    public void testTheSameGranuleIsNotCreatedTwice() throws Exception {
        catalog.addMetadata(granuleMetadata("granule-dup.nc",
                "34.05,-118.24,0.0,20260101T1200,1.0"), granuleProduct("d"));
        catalog.addMetadata(granuleMetadata("granule-dup.nc",
                "34.05,-118.24,0.0,20260101T1300,2.0"), granuleProduct("d"));

        assertEquals(1, countOf("granule"));
        assertEquals(2, countOf("dataPoint"));
    }

    /**
     * A filename carrying an apostrophe closed the literal it was
     * concatenated into. Ordinary text, not an edge case.
     */
    @Test
    public void testAFilenameWithAnApostropheIsStored() throws Exception {
        catalog.addMetadata(granuleMetadata("o'brien.nc",
                "34.05,-118.24,0.0,20260101T1200,1.0"), granuleProduct("a"));

        assertEquals(1, countOf("granule"));

        Connection conn = ds.getConnection();
        try {
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT filename FROM granule");
            assertTrue(rs.next());
            assertEquals("o'brien.nc", rs.getString(1));
        } finally {
            conn.close();
        }
    }

    /** and the read path still answers over what was written. */
    @Test
    public void testTheProductIsReadableAfterIngest() throws Exception {
        catalog.addMetadata(granuleMetadata("granule-read.nc",
                "34.05,-118.24,0.0,20260101T1200,1.0"), granuleProduct("r"));

        ProductType type = new ProductType();
        type.setName("test");
        type.setProductTypeId("1");

        Product stored = catalog.getProductByName("granule-read.nc");
        assertNotNull("the granule could not be read back", stored);
        assertEquals("granule-read.nc", stored.getProductName());
    }
}
