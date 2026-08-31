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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

public class TestPgeConfigPeek extends TestCase {

  public void testNoPathMeansNoPeek() {
    assertNull(PgeConfigPeek.of(null));
    assertNull(PgeConfigPeek.of(new HashMap<String, String>()));
    Map<String, String> empty = new HashMap<String, String>();
    empty.put(PgeConfigPeek.CONFIG_FILE_PATH, "  ");
    assertNull(PgeConfigPeek.of(empty));
  }

  public void testReadsExeCommandsAndShell() throws Exception {
    File xml = write("pge-peek-", ".xml",
        "<pgeConfig>\n"
            + "  <exe dir=\"[JobDir]\" shell=\"/bin/bash\">\n"
            + "    <cmd>export PATH=[PGE_ROOT]/bin:${PATH}</cmd>\n"
            + "    <cmd>  index-imagespace-fgbg.sh  </cmd>\n"
            + "  </exe>\n"
            + "</pgeConfig>\n");
    Map<String, String> props = new HashMap<String, String>();
    props.put(PgeConfigPeek.CONFIG_FILE_PATH, xml.getAbsolutePath());
    Map<String, Object> row = PgeConfigPeek.of(props);
    assertEquals(xml.getAbsolutePath(), row.get("path"));
    assertEquals("/bin/bash", row.get("shell"));
    assertEquals("[JobDir]", row.get("dir"));
    List<?> commands = (List<?>) row.get("commands");
    assertEquals(2, commands.size());
    assertEquals("export PATH=[PGE_ROOT]/bin:${PATH}", commands.get(0));
    assertEquals("index-imagespace-fgbg.sh", commands.get(1));
    assertTrue(String.valueOf(row.get("xml")).contains("index-imagespace-fgbg.sh"));
    assertNull(row.get("error"));
  }

  public void testShellTypeAttribute() throws Exception {
    File xml = write("pge-shelltype-", ".xml",
        "<pgeConfig><exe dir=\"/tmp\" shellType=\"csh\"><cmd>echo hi</cmd></exe></pgeConfig>");
    Map<String, String> props = new HashMap<String, String>();
    props.put(PgeConfigPeek.CONFIG_FILE_PATH, xml.getAbsolutePath());
    Map<String, Object> row = PgeConfigPeek.of(props);
    assertEquals("csh", row.get("shell"));
    assertEquals("/tmp", row.get("dir"));
  }

  public void testMissingFile() {
    Map<String, String> props = new HashMap<String, String>();
    props.put(PgeConfigPeek.CONFIG_FILE_PATH, "/no/such/PgeConfig.xml");
    Map<String, Object> row = PgeConfigPeek.of(props);
    assertEquals("/no/such/PgeConfig.xml", row.get("path"));
    assertEquals("No PgeConfig.xml at this path.", row.get("error"));
    assertNull(row.get("commands"));
  }

  public void testRejectsNonPgeRoot() throws Exception {
    File xml = write("not-pge-", ".xml", "<workflow><task/></workflow>");
    Map<String, String> props = new HashMap<String, String>();
    props.put(PgeConfigPeek.CONFIG_FILE_PATH, xml.getAbsolutePath());
    Map<String, Object> row = PgeConfigPeek.of(props);
    assertEquals("Not a PgeConfig.xml.", row.get("error"));
    assertNull(row.get("xml"));
  }

  public void testRejectsNonXmlSuffix() throws Exception {
    File file = write("pge-script-", ".sh", "echo hi\n");
    Map<String, String> props = new HashMap<String, String>();
    props.put(PgeConfigPeek.CONFIG_FILE_PATH, file.getAbsolutePath());
    Map<String, Object> row = PgeConfigPeek.of(props);
    assertEquals("PGETask_ConfigFilePath is not an XML file.", row.get("error"));
  }

  private static File write(String prefix, String suffix, String body) throws Exception {
    File file = File.createTempFile(prefix, suffix);
    file.deleteOnExit();
    Files.write(file.toPath(), body.getBytes(StandardCharsets.UTF_8));
    return file;
  }
}
