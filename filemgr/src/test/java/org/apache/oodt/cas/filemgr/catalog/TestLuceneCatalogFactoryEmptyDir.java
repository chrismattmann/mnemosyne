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

import java.io.File;
import java.nio.file.Files;

import junit.framework.TestCase;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

/**
 * A catalog directory that exists but holds no index is a catalog to create,
 * not one to open.
 */
public class TestLuceneCatalogFactoryEmptyDir extends TestCase {

  private File dir;

  protected void setUp() throws Exception {
    dir = Files.createTempDirectory("lucene-catalog").toFile();
  }

  protected void tearDown() throws Exception {
    System.clearProperty("org.apache.oodt.cas.filemgr.catalog.lucene.idxPath");
    System.clearProperty(
        "org.apache.oodt.cas.filemgr.catalog.lucene.lenientFields");
    delete(dir);
  }

  public void testAnEmptyDirectoryGetsAnIndex() throws Exception {
    assertTrue(dir.exists());
    assertEquals(0, dir.list().length);

    createCatalogOver(dir);

    assertTrue("an empty catalog directory was left without an index",
        DirectoryReader.indexExists(FSDirectory.open(dir.toPath())));
  }

  public void testAMissingDirectoryGetsAnIndex() throws Exception {
    File missing = new File(dir, "not-there-yet");
    assertFalse(missing.exists());

    createCatalogOver(missing);

    assertTrue(DirectoryReader.indexExists(FSDirectory.open(missing.toPath())));
  }

  public void testAnExistingIndexIsNotReplaced() throws Exception {
    createCatalogOver(dir);
    long before = newestWrite(dir);
    Thread.sleep(1100);

    createCatalogOver(dir);

    assertEquals("an existing index was rewritten", before, newestWrite(dir));
  }

  /**
   * The factory takes its index path from a system property, and lenient
   * fields keep a validation layer out of a test that is about the index.
   */
  private void createCatalogOver(File indexDir) {
    System.setProperty("org.apache.oodt.cas.filemgr.catalog.lucene.idxPath",
        indexDir.getAbsolutePath());
    System.setProperty(
        "org.apache.oodt.cas.filemgr.catalog.lucene.lenientFields", "true");
    new LuceneCatalogFactory().createCatalog();
  }

  private static long newestWrite(File dir) {
    long newest = 0;
    File[] files = dir.listFiles();
    for (int i = 0; files != null && i < files.length; i++) {
      newest = Math.max(newest, files[i].lastModified());
    }
    return newest;
  }

  private static void delete(File file) {
    File[] kids = file.listFiles();
    for (int i = 0; kids != null && i < kids.length; i++) {
      delete(kids[i]);
    }
    file.delete();
  }
}
