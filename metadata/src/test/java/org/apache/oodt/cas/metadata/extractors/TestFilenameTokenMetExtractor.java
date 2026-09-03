/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements.  See the NOTICE.txt file distributed with this work for
 * additional information regarding copyright ownership.  The ASF licenses this
 * file to you under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.oodt.cas.metadata.extractors;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.metadata.exceptions.MetExtractionException;

import junit.framework.TestCase;

/**
 * Tests that {@link FilenameTokenMetExtractor} says which of the filename and
 * the configuration disagreed, rather than indexing past the end of the key
 * list.
 */
public class TestFilenameTokenMetExtractor extends TestCase {

  private File tmpDir;

  @Override
  protected void setUp() throws Exception {
    tmpDir = Files.createTempDirectory("filename-token-test").toFile();
  }

  @Override
  protected void tearDown() throws Exception {
    for (File f : tmpDir.listFiles()) {
      f.delete();
    }
    tmpDir.delete();
  }

  /** A config naming keyCount keys, delimited by underscore. */
  private File configNaming(int keyCount) throws Exception {
    File config = new File(tmpDir, "config" + keyCount + ".xml");
    PrintWriter w = new PrintWriter(config, "UTF-8");
    w.println("<input>");
    w.println("  <group name=\"TokenNameListGroup\">");
    w.println("    <scalar name=\"Delimeter\">_</scalar>");
    w.println("    <vector name=\"TokenMetKeys\">");
    for (int i = 0; i < keyCount; i++) {
      w.println("      <element>Key" + i + "</element>");
    }
    w.println("    </vector>");
    w.println("  </group>");
    w.println("  <group name=\"CommonMetadata\">");
    w.println("    <scalar name=\"DataVersion\">1.0</scalar>");
    w.println("  </group>");
    w.println("</input>");
    w.close();
    return config;
  }

  private Metadata extract(int keyCount, String filename) throws Exception {
    File product = new File(tmpDir, filename);
    product.createNewFile();
    FilenameTokenMetExtractor extractor = new FilenameTokenMetExtractor();
    extractor.setConfigFile(configNaming(keyCount));
    return extractor.extractMetadata(product);
  }

  public void testEachTokenBecomesTheKeyAtItsPosition() throws Exception {
    Metadata met = extract(4, "rat_x-msaccess_1788392262411_3419.log");
    assertEquals("rat", met.getMetadata("Key0"));
    assertEquals("x-msaccess", met.getMetadata("Key1"));
    assertEquals("1788392262411", met.getMetadata("Key2"));
    assertEquals("3419", met.getMetadata("Key3"));
  }

  public void testAFilenameWithMoreTokensThanKeysSaysSo() throws Exception {
    try {
      extract(3, "rat_x-msaccess_1788392262411_3419.log");
      fail("a filename with a token the config does not name was extracted");
    } catch (MetExtractionException expected) {
      String message = expected.getMessage();
      assertNotNull("no message to diagnose from", message);
      assertTrue("does not name the filename: " + message,
          message.contains("rat_x-msaccess_1788392262411_3419.log"));
      assertTrue("does not give both counts: " + message,
          message.contains("4") && message.contains("3"));
    }
  }

  /** Fewer tokens than keys is legitimate: the trailing keys go unset. */
  public void testAFilenameWithFewerTokensThanKeysLeavesTheRestUnset()
      throws Exception {
    Metadata met = extract(4, "rat_x-msaccess.log");
    assertEquals("rat", met.getMetadata("Key0"));
    assertEquals("x-msaccess", met.getMetadata("Key1"));
    assertNull(met.getMetadata("Key2"));
  }
}
