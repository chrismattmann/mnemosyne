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

package org.apache.oodt.cas.filemgr.validation;

import org.apache.oodt.cas.filemgr.structs.Element;
import org.apache.oodt.commons.database.DatabaseConnectionBuilder;
import org.apache.oodt.commons.database.SqlScript;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import javax.sql.DataSource;

import static org.junit.Assert.*;

/**
 * DataSourceValidationLayer had no test coverage at all -- the class was at
 * 0.0% before this. The shipped example configurations use the XML layer, so
 * the JDBC one has been unexercised for a very long time, and both of the
 * statements exercised here were malformed for every possible input.
 */
public class TestDataSourceValidationLayer {

    private DataSource ds;
    private DataSourceValidationLayer layer;

    @Before
    public void setUp() throws Exception {
        File tempFile = File.createTempFile("foo", "bar");
        tempFile.deleteOnExit();
        String tmpDirPath = tempFile.getParentFile().getAbsolutePath();

        ds = DatabaseConnectionBuilder.buildDataSource("sa", "",
                "org.hsqldb.jdbcDriver",
                "jdbc:hsqldb:file:" + tmpDirPath + "/testValLayer;shutdown=true");

        File schema = new File(getClass().getResource("/testvallayer.sql").getFile());
        SqlScript script = new SqlScript(schema.getAbsolutePath(), ds);
        script.loadScript();
        script.execute();
        ds.getConnection().commit();

        layer = new DataSourceValidationLayer(ds, true);
    }

    @After
    public void tearDown() throws Exception {
        ds.getConnection().close();
    }

    private static Element element(String name, String description) {
        Element e = new Element();
        e.setElementName(name);
        e.setDCElement("title");
        e.setDescription(description);
        return e;
    }

    /**
     * The INSERT was missing the quote that should have closed the element
     * name -- the literal opened, took the name, then took ", '" instead of
     * "', '" -- so this threw for every input. The shrunk counterexample is
     * name "A", description "A".
     */
    @Test
    public void testAnElementCanBeAdded() throws Exception {
        layer.addElement(element("A", "A"));

        assertNotNull("the element was not stored", layer.getElementByName("A"));
    }

    /**
     * getElementByName concatenated the value in with no quotes at all, so it
     * threw for every name that was not accidentally numeric. This is the
     * path the catalog takes to resolve a criterion's element name for every
     * query against this layer.
     */
    @Test
    public void testASeededElementCanBeLookedUpByName() throws Exception {
        Element found = layer.getElementByName("Filename");

        assertNotNull("a seeded element could not be looked up by name", found);
        assertEquals("Filename", found.getElementName());
    }

    /** An added element reads back by id as well as by name. */
    @Test
    public void testAnAddedElementIsRetrievableById() throws Exception {
        layer.addElement(element("AddedByTest", "a description"));

        Element byName = layer.getElementByName("AddedByTest");
        assertNotNull(byName);
        assertEquals("AddedByTest", layer.getElementById(byName.getElementId())
                .getElementName());
    }

    /** A name nothing is stored under returns null rather than throwing. */
    @Test
    public void testAnUnknownNameReturnsNull() throws Exception {
        assertNull(layer.getElementByName("NoSuchElementAnywhere"));
    }

    /**
     * Bound parameters, so ordinary text works. An apostrophe in a
     * description is not an edge case.
     */
    @Test
    public void testAnApostropheInADescriptionIsStored() throws Exception {
        layer.addElement(element("Apostrophe", "O'Brien's element"));

        assertEquals("O'Brien's element",
                layer.getElementByName("Apostrophe").getDescription());
    }

    /** and a name carrying SQL is stored as text rather than executed. */
    @Test
    public void testANameCarryingSqlIsStoredAsText() throws Exception {
        String hostile = "x'); DROP TABLE elements; --";
        layer.addElement(element(hostile, "harmless"));

        assertNotNull("the elements table did not survive",
                layer.getElementByName("Filename"));
        assertEquals(hostile, layer.getElementByName(hostile).getElementName());
    }
}
