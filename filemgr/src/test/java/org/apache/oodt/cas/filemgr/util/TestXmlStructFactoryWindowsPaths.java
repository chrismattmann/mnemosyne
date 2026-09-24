/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.oodt.cas.filemgr.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TestXmlStructFactoryWindowsPaths {

    @Test
    public void testWindowsDriveRepositoryBecomesLocalFileUri() {
        assertEquals("file:///C:/drat/data/archive",
                XmlStructFactory.normalizeRepositoryPath(
                        "file://C:/drat/data/archive"));
        assertEquals("file:///D:/drat/data/archive",
                XmlStructFactory.normalizeRepositoryPath(
                        "file://D:\\drat\\data\\archive"));
    }

    @Test
    public void testUnixAndUncRepositoriesAreUnchanged() {
        assertEquals("file:///opt/drat/data/archive",
                XmlStructFactory.normalizeRepositoryPath(
                        "file:///opt/drat/data/archive"));
        assertEquals("file://server/share/archive",
                XmlStructFactory.normalizeRepositoryPath(
                        "file://server/share/archive"));
    }
}
