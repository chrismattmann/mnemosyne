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

package org.apache.oodt.pcs.util;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.system.FileManagerClient;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of the pure, static helpers in {@link FileManagerUtils}.
 *
 * <p>Everything exercised here is reachable without a File Manager: the
 * instance methods all funnel through an RPC client, but these five statics
 * are ordinary functions over strings and lists, and the PCS trace and long
 * lister tools depend on them directly.
 */
class FileManagerUtilsPropertyTest {

  /** Path segment: a directory or product name, no separators inside it. */
  private static final Generator<String> SEGMENT =
      text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");

  private static Product namedProduct(String name) {
    Product p = new Product();
    p.setProductName(name);
    return p;
  }

  private static Reference refWithOrig(String orig) {
    Reference r = new Reference();
    r.setOrigReference(orig);
    return r;
  }

  /**
   * Naming a list of products loses nothing: the caller gets one name per
   * product, in the order the products arrived. The long lister prints these
   * side by side with the products they came from, so a reorder or a drop
   * would mislabel a row.
   */
  @HegelTest
  void toProductNameListPreservesOrderAndSize(TestCase tc) {
    List<String> names = tc.draw(lists(SEGMENT).minSize(0).maxSize(30), "names");

    List<Product> products = new ArrayList<>();
    for (String name : names) {
      products.add(namedProduct(name));
    }

    @SuppressWarnings("unchecked")
    List<String> result = FileManagerUtils.toProductNameList(products);

    assertEquals(names, result);
  }

  /**
   * A caller that has no products at all still gets a list back, never null,
   * so the usual {@code for} loop over the result is safe.
   */
  @HegelTest
  void toProductNameListOfNothingIsAnEmptyList(TestCase tc) {
    boolean useNull = tc.draw(booleans(), "useNull");

    List<?> result =
        FileManagerUtils.toProductNameList(useNull ? null : Collections.emptyList());

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  /**
   * A reference is a product's root directory exactly when it names a
   * directory (trailing slash) whose own last path element starts with the
   * product name. That is the rule {@code getDirProductFilePath} relies on to
   * pick the directory of a hierarchical product.
   */
  @HegelTest
  void isRootDirRecognisesADirectoryNamedAfterTheProduct(TestCase tc) {
    List<String> parents = tc.draw(lists(SEGMENT).minSize(0).maxSize(4), "parents");
    String prodName = tc.draw(SEGMENT, "prodName");
    String suffix = tc.draw(text().minSize(0).maxSize(6).categories("Lu", "Ll", "Nd"), "suffix");

    StringBuilder path = new StringBuilder("/");
    for (String parent : parents) {
      path.append(parent).append('/');
    }
    path.append(prodName).append(suffix).append('/');

    assertTrue(
        FileManagerUtils.isRootDir(refWithOrig(path.toString()), prodName),
        "directory [" + path + "] not recognised as the root of [" + prodName + "]");
  }

  /**
   * A reference to a file — anything without a trailing slash — is never a
   * root directory, whatever it is named.
   */
  @HegelTest
  void isRootDirRejectsNonDirectories(TestCase tc) {
    List<String> parents = tc.draw(lists(SEGMENT).minSize(0).maxSize(4), "parents");
    String prodName = tc.draw(SEGMENT, "prodName");

    StringBuilder path = new StringBuilder("/");
    for (String parent : parents) {
      path.append(parent).append('/');
    }
    path.append(prodName);

    assertFalse(FileManagerUtils.isRootDir(refWithOrig(path.toString()), prodName));
  }

  /**
   * Picking the root reference out of a product's references either returns
   * one that really is the root directory, or returns null because none of
   * them is. It must never hand back a reference that fails its own test.
   */
  @HegelTest
  void getRootReferenceReturnsARootOrNothing(TestCase tc) {
    String prodName = tc.draw(SEGMENT, "prodName");
    List<String> tails = tc.draw(lists(SEGMENT).minSize(0).maxSize(8), "tails");
    List<Boolean> asDirectory = tc.draw(lists(booleans()).minSize(0).maxSize(8), "asDirectory");

    List<Reference> refs = new ArrayList<>();
    for (int i = 0; i < tails.size(); i++) {
      boolean dir = i < asDirectory.size() && asDirectory.get(i);
      boolean underProduct = i % 2 == 0;
      String name = underProduct ? prodName + tails.get(i) : tails.get(i);
      refs.add(refWithOrig("/archive/" + name + (dir ? "/" : "")));
    }

    Reference root = FileManagerUtils.getRootReference(prodName, refs);

    if (root != null) {
      assertTrue(
          FileManagerUtils.isRootDir(root, prodName),
          "returned reference [" + root.getOrigReference() + "] is not a root dir");
      assertTrue(refs.contains(root), "returned a reference that was not in the list");
    } else {
      for (Reference r : refs) {
        assertFalse(
            FileManagerUtils.isRootDir(r, prodName),
            "returned null although [" + r.getOrigReference() + "] is a root dir");
      }
    }
  }

  /**
   * A well-formed URL survives the string round-trip unchanged. PCS builds
   * every File Manager, Workflow Manager and Resource Manager handle this
   * way, so a URL that comes back altered points the tools at the wrong host.
   */
  @HegelTest
  void safeGetUrlFromStringRoundTripsWellFormedUrls(TestCase tc) {
    String scheme = tc.draw(sampledFrom("http", "https"), "scheme");
    String host = tc.draw(text().minSize(1).maxSize(10).categories("Ll", "Nd"), "host");
    int port = tc.draw(integers().min(1).max(65_535), "port");
    List<String> path = tc.draw(lists(SEGMENT).minSize(0).maxSize(3), "path");

    StringBuilder urlStr = new StringBuilder(scheme).append("://").append(host).append(':').append(port);
    for (String segment : path) {
      urlStr.append('/').append(segment);
    }

    URL url = FileManagerUtils.safeGetUrlFromString(urlStr.toString());

    assertNotNull(url, "well-formed url [" + urlStr + "] came back null");
    assertEquals(urlStr.toString(), url.toString());
  }

  /**
   * A string that is not a URL at all is reported by returning null, not by
   * throwing: callers such as the {@code Pedigree} constructor pass the value
   * straight on and check it themselves.
   */
  @HegelTest
  void safeGetUrlFromStringReturnsNullForNonUrls(TestCase tc) {
    String junk = tc.draw(text().minSize(1).maxSize(12).categories("Lu", "Ll"), "junk");

    assertNull(FileManagerUtils.safeGetUrlFromString(junk));
  }

  /** An ASCII path such as {@code /archive/seg3/seg7}, legal inside a URI as written. */
  private static String asciiPath(List<Integer> segments) {
    StringBuilder path = new StringBuilder("/archive");
    for (int segment : segments) {
      path.append("/seg").append(segment);
    }
    return path.toString();
  }

  /**
   * A {@code file:} data store reference resolves to the file it names. This
   * is the ordinary case behind {@link FileManagerUtils#getFilePath}, which
   * prints the result for every product the trace tool touches.
   */
  @HegelTest
  void safeGetFileFromUriResolvesFileUris(TestCase tc) {
    List<Integer> segments =
        tc.draw(lists(integers().min(0).max(999)).minSize(1).maxSize(4), "segments");

    String path = asciiPath(segments);

    assertEquals(path, FileManagerUtils.safeGetFileFromUri("file://" + path).getPath());
  }

  /**
   * {@code safeGetFileFromUri} is the "safe" half of the reference-to-file
   * conversion: as its name and its catch block say, it reports a reference it
   * cannot turn into a file by returning null rather than by throwing. Not
   * every catalogued reference is a {@code file:} URI — versioners such as
   * {@code InPlaceVersioner} copy whatever string the ingester supplied, so a
   * bare path or a remote scheme reaches this method unaltered through
   * {@link FileManagerUtils#getFilePath}.
   */
  @HegelTest
  void safeGetFileFromUriReportsFailureByReturningNull(TestCase tc) {
    String scheme = tc.draw(sampledFrom("", "http://archive.example.org", "hdfs://nn"), "scheme");
    List<Integer> segments =
        tc.draw(lists(integers().min(0).max(999)).minSize(1).maxSize(4), "segments");

    String reference = scheme + asciiPath(segments);

    try {
      assertNull(FileManagerUtils.safeGetFileFromUri(reference),
          "expected null for a reference that is not a file uri: [" + reference + "]");
    } catch (RuntimeException e) {
      fail("safeGetFileFromUri threw " + e.getClass().getName() + " for [" + reference + "]: "
          + e.getMessage());
    }
  }

  /**
   * With no File Manager behind it, every {@code safeGetX} answers with the
   * documented empty stand-in rather than failing. PCS tools are expected to
   * run against a File Manager that may be down — the health monitor's whole
   * job is to notice that — so a lookup made while disconnected has to come
   * back with something the caller can print.
   */
  @HegelTest
  void everyLookupHasAnAnswerWhileDisconnected(TestCase tc) {
    String name = tc.draw(SEGMENT, "name");
    int topN = tc.draw(integers().min(0).max(50), "topN");

    FileManagerUtils fm = new FileManagerUtils((FileManagerClient) null);

    assertNotNull(fm.safeGetTopNProducts(topN), "top-N products came back null");
    assertNotNull(fm.safeGetProductTypes(), "product types came back null");
    assertNotNull(fm.safeGetProductTypeByName(name), "product type by name came back null");
    assertNotNull(fm.safeGetProductTypeById(name), "product type by id came back null");
    assertNotNull(fm.safeGetElementByName(name), "element definition came back null");
    assertNotNull(fm.safeGetProductByName(name), "product by name came back null");
    assertNotNull(fm.safeFirstPage(null), "first page came back null");
    assertNotNull(fm.safeGetMetadata(namedProduct(name)), "metadata came back null");
    assertNotNull(fm.safeGetProductReferences(namedProduct(name)), "references came back null");
    assertNotNull(fm.safeIssueQuery(null, null), "query results came back null");
    assertEquals("N/A", fm.getFilePath(namedProduct(name)),
        "a file path was invented for a product that could not be looked up");
    assertTrue(fm.safeGetNumProducts(null) < 0,
        "a disconnected file manager reported a product count as if it knew one");
  }

  /**
   * {@code check} is the guard PCS runs over each metadata value before it
   * declares a run successful. It fails a value exactly when the value is
   * missing, and it records that failure on the metadata it was handed.
   */
  @HegelTest
  void checkFailsExactlyTheMissingValues(TestCase tc) {
    String propName = tc.draw(SEGMENT, "propName");
    boolean present = tc.draw(booleans(), "present");
    String propValue = present ? tc.draw(SEGMENT, "propValue") : null;

    Metadata met = new Metadata();
    boolean ok = FileManagerUtils.check(propName, propValue, met);

    assertEquals(present, ok, "check disagreed with whether the value was present");
    if (present) {
      assertNull(met.getMetadata("ApplicationSuccess"), "a present value was flagged as a failure");
    } else {
      assertEquals("false", met.getMetadata("ApplicationSuccess"),
          "a missing value was not flagged as a failure");
    }
  }
}
