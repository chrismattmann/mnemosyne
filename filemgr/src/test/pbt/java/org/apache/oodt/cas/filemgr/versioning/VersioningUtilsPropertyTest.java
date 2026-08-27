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

package org.apache.oodt.cas.filemgr.versioning;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.Reference;

/**
 * Properties of the pure reference-rewriting helpers in
 * {@link VersioningUtils}. These decide where every file of an ingested
 * product ends up in the archive, so a reference that comes out wrong is a
 * file archived to the wrong path — or not archived at all.
 *
 * <p>Only the methods that are functions of their arguments are exercised
 * here: {@code createBasicDataStoreRefsHierarchical},
 * {@code createBasicDataStoreRefsFlat} and {@code getAbsolutePathFromUri}. The
 * directory-walking helpers are left alone because they read the filesystem.
 *
 * <p>These methods mutate the {@link Reference} objects handed to them rather
 * than returning new ones, so each property builds a fresh list per run and
 * reads the result back off the same objects.
 */
class VersioningUtilsPropertyTest {

  /** Ordinary directory names. None of them occurs inside the "file:/" prefix. */
  private static Generator<String> dirNames() {
    return sampledFrom(List.of("data", "raw", "x"));
  }

  /**
   * File names as a user may actually create them: lower-case letters plus the
   * three characters that have to survive being carried through a URL.
   */
  private static Generator<String> fileNames() {
    return text().minSize(1).maxSize(6).categories("Ll").includeCharacters(" #%");
  }

  private static Reference referenceTo(String origRef) {
    Reference r = new Reference();
    r.setOrigReference(origRef);
    return r;
  }

  /**
   * The hierarchical rewrite re-roots every file of a product underneath the
   * product's directory in the archive, keeping its path relative to the
   * staging root. The method's own worked example says exactly this:
   * {@code file:///www/folder1/folder2/file3} under staging root
   * {@code file:///www/folder1} becomes {@code <productDir>/folder2/file3}.
   *
   * <p>The relative path is what the archive lays out on disk, so getting it
   * wrong scatters a product's files across directories that were never asked
   * for.
   */
  @HegelTest
  void hierarchicalRefsKeepEachFilePathRelativeToTheStagingRoot(TestCase tc) {
    List<String> rootSegments = tc.draw(lists(dirNames()).minSize(1).maxSize(3), "rootSegments");
    List<String> childSegments = tc.draw(lists(dirNames()).minSize(1).maxSize(2), "childSegments");

    String stagingRoot = "file:/" + String.join("/", rootSegments);
    String relativePath = String.join("/", childSegments);
    String productDir = "file:/archive/AProduct/";

    Reference root = referenceTo(stagingRoot);
    root.setDataStoreReference(productDir);
    Reference child = referenceTo(stagingRoot + "/" + relativePath);

    List<Reference> references = new ArrayList<>(List.of(root, child));
    VersioningUtils.createBasicDataStoreRefsHierarchical(references);
    tc.note("stagingRoot = " + stagingRoot + ", child = " + child.getOrigReference());

    assertEquals(
        productDir + relativePath,
        child.getDataStoreReference(),
        "file was re-rooted at the wrong place under " + productDir);
  }

  /**
   * The flat rewrite has to hand back a data store reference that is a usable
   * URI naming the same file, because that is precisely what the caller does
   * with it next: {@code LocalDataTransferer} line 299 runs
   * {@code new File(new URI(r.getDataStoreReference()))} to copy the file into
   * the archive.
   *
   * <p>The original reference is built the way the file manager builds one, by
   * {@code File.toURI().toURL().toExternalForm()}, so it is properly encoded
   * going in. Anything the rewrite loses on the way out, it lost itself.
   */
  @HegelTest
  void flatRefsProduceAUsableDataStoreUri(TestCase tc) throws Exception {
    String fileName = tc.draw(fileNames(), "fileName");

    String origRef =
        new File("/staging/" + fileName).toURI().toURL().toExternalForm();
    Reference reference = referenceTo(origRef);
    tc.note("origRef = " + origRef);

    VersioningUtils.createBasicDataStoreRefsFlat(
        "AProduct", "file:/archive", new ArrayList<>(List.of(reference)));

    String dataStoreRef = reference.getDataStoreReference();
    assertNotNull(
        dataStoreRef, "reference was silently left with no data store reference: " + origRef);

    URI asUri;
    try {
      asUri = new URI(dataStoreRef);
    } catch (URISyntaxException e) {
      fail("data store reference is not a URI: " + dataStoreRef + " (" + e.getMessage() + ")");
      return;
    }
    assertEquals(
        fileName,
        new File(asUri).getName(),
        "data store reference " + dataStoreRef + " names a different file");
  }

  /**
   * {@code getAbsolutePathFromUri} catches {@link URISyntaxException} and
   * returns null, so its contract is plainly "a path, or null if I cannot make
   * one" — callers such as {@code FileManager} line 1087 and
   * {@code FinalFileLocationExtractor} test the result for null rather than
   * catching anything.
   *
   * <p>A reference is only ever a string, and nothing constrains it to a
   * hierarchical {@code file:} URL, so the method has to hold that contract for
   * whatever URI it is given.
   */
  @HegelTest
  void absolutePathFromUriReturnsNullRatherThanThrowing(TestCase tc) {
    String prefix =
        tc.draw(
            sampledFrom(
                List.of("file:/staging/", "http://example.com/", "ftp://example.com/", "file:")),
            "prefix");
    String name = tc.draw(text().minSize(1).maxSize(6).categories("Ll"), "name");
    String uriStr = prefix + name;

    try {
      VersioningUtils.getAbsolutePathFromUri(uriStr);
    } catch (RuntimeException e) {
      fail("threw " + e.getClass().getName() + " for URI [" + uriStr + "]: " + e.getMessage());
    }
  }
}
