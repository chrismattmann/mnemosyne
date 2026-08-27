/**
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
package org.apache.oodt.pcs.services;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

public class TestConfigResource extends TestCase {

  public void testReadPropertiesSkipsCommentsAndRedactsSecrets() throws Exception {
    File file = File.createTempFile("filemgr", ".properties");
    file.deleteOnExit();
    OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file),
        Charset.forName("ISO-8859-1"));
    try {
      writer.write("# comment\n");
      writer.write("filemgr.catalog.factory=org.apache.oodt.cas.filemgr.catalog.LuceneCatalogFactory\n");
      writer.write("org.apache.oodt.cas.filemgr.catalog.datasource.jdbc.pass=s3cret\n");
      writer.write("\n");
      writer.write("filemgr.datatransfer.factory=org.apache.oodt.cas.filemgr.datatransfer.LocalDataTransferFactory\n");
    } finally {
      writer.close();
    }
    List<Map<String, String>> rows = ConfigResource.readProperties(file);
    assertEquals(3, rows.size());
    assertEquals("filemgr.catalog.factory", rows.get(0).get("key"));
    assertTrue(rows.get(0).get("value").indexOf("LuceneCatalogFactory") >= 0);
    assertEquals("••••", rows.get(1).get("value"));
    assertEquals("filemgr.datatransfer.factory", rows.get(2).get("key"));
  }

  public void testIsSecret() {
    assertTrue(ConfigResource.isSecret("org.apache.oodt.cas.filemgr.catalog.datasource.jdbc.pass"));
    assertTrue(ConfigResource.isSecret("db.password"));
    assertFalse(ConfigResource.isSecret("filemgr.catalog.factory"));
  }
}
