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

import org.apache.oodt.cas.filemgr.structs.ProductPage;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.validation.ValidationLayer;
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
 * The productIdString branch of {@link DataSourceCatalog}, which had no test.
 *
 * That branch produced
 * {@code SELECT DISTINCT products.product_id ... ORDER BY products.product_datetime DESC}.
 * HSQLDB rejects that outright -- "ORDER BY item should be in the SELECT
 * DISTINCT list" -- and so does PostgreSQL; only MySQL accepts it. Every
 * query and every page under productIdString was therefore unusable on a
 * standards-compliant database.
 *
 * The schema here is the one that branch is written for: a string product_id
 * and a product_datetime column. It exists only as a commented example in
 * cas-filemgr-schema-mysql.sql, so it is written out in
 * testcat-stringid.sql -- and it does carry product_datetime, so this test
 * fails on the ORDER BY rule rather than on a missing column.
 */
public class TestDataSourceCatalogStringProductId {

    private DataSource ds;
    private DataSourceCatalog catalog;

    @Before
    public void setUp() throws Exception {
        File tempFile = File.createTempFile("foo", "bar");
        tempFile.deleteOnExit();
        String tmpDirPath = tempFile.getParentFile().getAbsolutePath();

        ds = DatabaseConnectionBuilder.buildDataSource("sa", "",
                "org.hsqldb.jdbcDriver",
                "jdbc:hsqldb:file:" + tmpDirPath + "/testStringIdCat;shutdown=true");

        File schema = new File(getClass().getResource("/testcat-stringid.sql").getFile());
        SqlScript script = new SqlScript(schema.getAbsolutePath(), ds);
        script.loadScript();
        script.execute();
        ds.getConnection().commit();

        // productIdString = true is the whole point; the validation layer is
        // not consulted for a query that carries no criteria.
        catalog = new DataSourceCatalog(ds, (ValidationLayer) null, false, 20,
                0L, true, false);
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

    @Test
    public void testAQueryWithNoCriteriaIsValidSql() throws Exception {
        List<String> ids = catalog.query(new Query(), genericFile());

        assertNotNull(ids);
        assertEquals(3, ids.size());
        assertTrue(ids.contains("urn:prod:a"));
        assertTrue(ids.contains("urn:prod:b"));
        assertTrue(ids.contains("urn:prod:c"));
    }

    /** and the paged form, which is the branch InstanceRepoCleaner-style
     *  callers walk. */
    @Test
    public void testAPagedQueryIsValidSql() throws Exception {
        ProductPage page = catalog.pagedQuery(new Query(), genericFile(), 1);

        assertNotNull(page);
        assertNotNull(page.getPageProducts());
    }

    /** the order is deterministic, so paging is stable. */
    @Test
    public void testTheOrderIsStable() throws Exception {
        List<String> first = catalog.query(new Query(), genericFile());
        List<String> second = catalog.query(new Query(), genericFile());

        assertEquals(first, second);
    }

    /**
     * The fixture above is also the regression test for SqlScript's comment
     * handling. isComment recognised '#' and nothing else, so the '--' lines
     * in testcat-stringid.sql were appended into the statement being built
     * and corrupted it -- silently, surfacing here as HSQLDB reporting
     * GenericFile_metadata missing after being asked to create it. The
     * project ships scripts written that way: sdschema.sql opens with a '---'
     * licence header.
     *
     * It is asserted here rather than in commons because commons runs
     * surefire 2.4 with useSystemClassLoader=false, under which no test can
     * load java.sql -- which is why commons/database has no tests at all.
     */
    @Test
    public void testTheSchemaWithSqlCommentsLoadedCompletely() throws Exception {
        java.sql.Connection conn = ds.getConnection();
        try {
            java.sql.ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM GenericFile_metadata");
            assertTrue("the metadata table was never created: a '--' comment "
                    + "line was parsed as part of a statement", rs.next());
            assertEquals(3, rs.getInt(1));
        } finally {
            conn.close();
        }
    }

}
