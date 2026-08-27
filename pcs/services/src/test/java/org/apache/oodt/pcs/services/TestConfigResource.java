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

  public void testReadPropertiesJoinsBackslashContinuations() throws Exception {
    File file = File.createTempFile("filemgr", ".properties");
    file.deleteOnExit();
    OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file),
        Charset.forName("ISO-8859-1"));
    try {
      writer.write("filemgr.catalog.factory=org.apache.oodt.cas.filemgr.catalog.LuceneCatalogFactory\n");
      writer.write("org.apache.oodt.cas.filemgr.repositorymgr.dirs=file:///tmp/policy/core,\\\n");
      writer.write("file:///tmp/policy/geo,\\\n");
      writer.write("  file:///tmp/policy/trace,\\\n");
      writer.write("file:///tmp/policy/bigtranslate\n");
      writer.write("org.apache.oodt.cas.filemgr.validation.dirs=file:///tmp/policy/core,\\\n");
      writer.write("file:///tmp/policy/geo\n");
      writer.write("literal.backslash=keep\\\\\n");
      writer.write("next.property=ok\n");
    } finally {
      writer.close();
    }
    List<Map<String, String>> rows = ConfigResource.readProperties(file);
    assertEquals(5, rows.size());
    assertEquals("filemgr.catalog.factory", rows.get(0).get("key"));
    assertEquals("org.apache.oodt.cas.filemgr.repositorymgr.dirs", rows.get(1).get("key"));
    assertEquals(
        "file:///tmp/policy/core,file:///tmp/policy/geo,file:///tmp/policy/trace,file:///tmp/policy/bigtranslate",
        rows.get(1).get("value"));
    assertEquals("org.apache.oodt.cas.filemgr.validation.dirs", rows.get(2).get("key"));
    assertEquals("file:///tmp/policy/core,file:///tmp/policy/geo", rows.get(2).get("value"));
    assertEquals("literal.backslash", rows.get(3).get("key"));
    assertEquals("keep\\\\", rows.get(3).get("value"));
    assertEquals("next.property", rows.get(4).get("key"));
    for (int i = 0; i < rows.size(); i++) {
      String key = rows.get(i).get("key");
      assertFalse("continuation leaked as a key: " + key,
          "file".equals(key) || key.startsWith("file:"));
    }
  }

  public void testEndsWithContinuation() {
    assertTrue(ConfigResource.endsWithContinuation("foo,\\"));
    assertFalse(ConfigResource.endsWithContinuation("foo\\\\"));
    assertTrue(ConfigResource.endsWithContinuation("foo\\\\\\"));
    assertFalse(ConfigResource.endsWithContinuation("foo"));
  }
}
