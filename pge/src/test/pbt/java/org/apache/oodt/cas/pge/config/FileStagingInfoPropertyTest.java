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

package org.apache.oodt.cas.pge.config;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Properties of {@link FileStagingInfo}, the list of files and products a PGE
 * wants copied next to it before it runs.
 *
 * <p>The class stores both lists as sets and hands back a fresh list each time,
 * so the properties are stated as set membership: staging a file twice is one
 * copy, and a caller cannot change what will be staged by editing a list it was
 * given.
 */
class FileStagingInfoPropertyTest {

  private static Generator<List<String>> pathsWithRepeats() {
    return lists(integers().min(0).max(4).map(i -> "path" + i)).maxSize(8);
  }

  private static Generator<String> dirs() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  /** The staging directory and the force flag come back as they went in. */
  @HegelTest
  void theStagingDirectoryAndForceFlagRoundTrip(TestCase tc) {
    String stagingDir = tc.draw(dirs(), "stagingDir");
    boolean force = tc.draw(booleans(), "force");

    FileStagingInfo info = new FileStagingInfo(stagingDir, force);

    assertEquals(stagingDir, info.getStagingDir());
    assertEquals(force, info.isForceStaging());
    assertEquals(
        false,
        new FileStagingInfo(stagingDir).isForceStaging(),
        "staging is forced by default");
  }

  /**
   * The files to stage are exactly the distinct paths that were asked for,
   * however they were asked for. Adding a path one at a time and adding it in a
   * batch mean the same thing, and asking twice does not stage it twice.
   */
  @HegelTest
  void stagedFilesAreExactlyTheDistinctPathsAsked(TestCase tc) {
    List<String> oneByOne = tc.draw(pathsWithRepeats(), "oneByOne");
    List<String> inBatch = tc.draw(pathsWithRepeats(), "inBatch");

    FileStagingInfo info = new FileStagingInfo("staging");
    for (String path : oneByOne) {
      info.addFilePath(path);
    }
    info.addFilePaths(inBatch);

    Set<String> expected = new HashSet<String>(oneByOne);
    expected.addAll(inBatch);
    assertEquals(expected, new HashSet<String>(info.getFilePaths()));
    assertEquals(expected.size(), info.getFilePaths().size(), "a path was staged twice");
  }

  /** The same, for the product ids a PGE asks to have staged. */
  @HegelTest
  void stagedProductIdsAreExactlyTheDistinctIdsAsked(TestCase tc) {
    List<String> oneByOne = tc.draw(pathsWithRepeats(), "oneByOne");
    List<String> inBatch = tc.draw(pathsWithRepeats(), "inBatch");

    FileStagingInfo info = new FileStagingInfo("staging");
    for (String id : oneByOne) {
      info.addProductId(id);
    }
    info.addProductIds(inBatch);

    Set<String> expected = new HashSet<String>(oneByOne);
    expected.addAll(inBatch);
    assertEquals(expected, new HashSet<String>(info.getProductIds()));
    assertEquals(expected.size(), info.getProductIds().size(), "a product was staged twice");
  }

  /**
   * The lists handed out are copies. The caller iterates them while staging and
   * a config object shared between tasks must not be editable through a list it
   * lent out.
   */
  @HegelTest
  void theListsHandedOutAreCopies(TestCase tc) {
    List<String> paths = tc.draw(pathsWithRepeats(), "paths");

    FileStagingInfo info = new FileStagingInfo("staging");
    info.addFilePaths(paths);
    info.addProductIds(paths);

    int expectedSize = new HashSet<String>(paths).size();
    info.getFilePaths().clear();
    info.getProductIds().clear();

    assertEquals(expectedSize, info.getFilePaths().size(), "clearing the copy emptied the object");
    assertEquals(expectedSize, info.getProductIds().size(), "clearing the copy emptied the object");
    assertTrue(info.getFilePaths().containsAll(new HashSet<String>(paths)));
  }
}
