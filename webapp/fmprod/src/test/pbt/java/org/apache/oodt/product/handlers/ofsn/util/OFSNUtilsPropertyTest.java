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

package org.apache.oodt.product.handlers.ofsn.util;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.apache.oodt.product.handlers.ofsn.OFSNHandlerConfig;
import org.apache.oodt.product.handlers.ofsn.metadata.OFSNXMLMetKeys;
import org.apache.oodt.xmlquery.QueryElement;
import org.apache.oodt.xmlquery.XMLQuery;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Properties of the OFSN listing helpers in {@link OFSNUtils}.
 *
 * <p>An OFSN is the name a product server hands a client for a file, and the
 * name the client hands back to fetch it. The properties below are about that
 * round trip and about the boundary it defends: an OFSN the server accepts must
 * name a file inside the configured product root and nowhere else.
 *
 * <p>The cases that touch disk build a small tree under a fresh temporary
 * directory and delete it afterwards, so the case count is kept modest.
 */
class OFSNUtilsPropertyTest implements OFSNXMLMetKeys {

  private static final List<String> NAME_CHARS =
      Arrays.asList("a", "b", "c", "d", "e", "f", "g", "1", "2", "-", "_");

  private static Generator<String> fileName() {
    return lists(sampledFrom(NAME_CHARS))
        .minSize(1)
        .maxSize(8)
        .map(cs -> String.join("", cs));
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  private static OFSNHandlerConfig listingConfig() {
    OFSNHandlerConfig cfg = new OFSNHandlerConfig();
    cfg.setType("listing");
    Properties props = new Properties();
    props.setProperty("isSizeCmd", "false");
    cfg.setHandlerConf(props);
    return cfg;
  }

  private static List<String> ofsnsIn(Document doc) {
    List<String> ofsns = new ArrayList<String>();
    NodeList entries = doc.getElementsByTagName(OFSN_TAG);
    for (int i = 0; i < entries.getLength(); i++) {
      ofsns.add(((Element) entries.item(i)).getTextContent());
    }
    return ofsns;
  }

  /**
   * The name the listing publishes for a file, appended to the product root the
   * way {@code OFSNFileHandler} appends it, names that same file again. This is
   * the round trip the whole protocol rests on: a client can only ever ask for
   * a name the server gave it.
   */
  @HegelTest(testCases = 30)
  void aPublishedOfsnNamesTheSameFileWhenTheHandlerResolvesIt(TestCase tc) throws Exception {
    List<String> names = tc.draw(lists(fileName()).minSize(1).maxSize(5), "names");
    boolean rootHasTrailingSlash = tc.draw(booleans(), "rootHasTrailingSlash");

    Path root = Files.createTempDirectory("ofsn-listing");
    try {
      List<File> files = new ArrayList<File>();
      for (String name : names) {
        Path file = root.resolve(name);
        if (!Files.exists(file)) {
          Files.write(file, new byte[] {1, 2, 3});
          files.add(file.toFile());
        }
      }

      String productRoot = root.toFile().getAbsolutePath() + (rootHasTrailingSlash ? "/" : "");
      Document doc =
          OFSNUtils.getOFSNDoc(files, listingConfig(), productRoot, false, false);
      assertNotNull(doc, "no listing document was produced");

      List<String> ofsns = ofsnsIn(doc);
      assertEquals(files.size(), ofsns.size(), "the listing lost or invented an entry");

      for (int i = 0; i < files.size(); i++) {
        File resolved = new File(productRoot + ofsns.get(i));
        assertEquals(
            files.get(i).getCanonicalPath(),
            resolved.getCanonicalPath(),
            "OFSN [" + ofsns.get(i) + "] did not resolve back to the file it names");
      }
    } finally {
      deleteTree(root);
    }
  }

  /**
   * {@link OFSNUtils#relativeize} is the other half of the same round trip: it
   * is the public helper for turning an OFSN back into a path under the product
   * root, so it has to agree with the names the listing publishes.
   */
  @HegelTest(testCases = 30)
  void relativeizeResolvesAPublishedOfsnToTheSameFile(TestCase tc) throws Exception {
    List<String> names = tc.draw(lists(fileName()).minSize(1).maxSize(4), "names");
    boolean rootHasTrailingSlash = tc.draw(booleans(), "rootHasTrailingSlash");

    Path root = Files.createTempDirectory("ofsn-relativeize");
    try {
      List<File> files = new ArrayList<File>();
      for (String name : names) {
        Path file = root.resolve(name);
        if (!Files.exists(file)) {
          Files.write(file, new byte[] {7});
          files.add(file.toFile());
        }
      }

      String productRoot = root.toFile().getAbsolutePath() + (rootHasTrailingSlash ? "/" : "");
      Document doc =
          OFSNUtils.getOFSNDoc(files, listingConfig(), productRoot, false, false);
      List<String> ofsns = ofsnsIn(doc);

      for (int i = 0; i < files.size(); i++) {
        File resolved = new File(OFSNUtils.relativeize(ofsns.get(i), productRoot));
        assertEquals(
            files.get(i).getCanonicalPath(),
            resolved.getCanonicalPath(),
            "relativeize did not resolve OFSN [" + ofsns.get(i) + "] back to its file");
      }
    } finally {
      deleteTree(root);
    }
  }

  /**
   * Every OFSN the validator accepts stays inside the product root once
   * {@link OFSNUtils#relativeize} has resolved it. {@code OFSNFileHandler}
   * calls the validator and nothing else before opening the file, so this is
   * the only thing standing between a request and the rest of the filesystem.
   */
  @HegelTest(testCases = 40)
  void anAcceptedOfsnCannotEscapeTheProductRoot(TestCase tc) throws Exception {
    List<String> segments = tc.draw(lists(pathSegment()).minSize(1).maxSize(4), "segments");
    boolean leadingSlash = tc.draw(booleans(), "leadingSlash");
    boolean rootHasTrailingSlash = tc.draw(booleans(), "rootHasTrailingSlash");

    String ofsn = (leadingSlash ? "/" : "") + String.join("/", segments);
    if (!OFSNUtils.validateOFSN(ofsn)) {
      return;
    }

    Path root = Files.createTempDirectory("ofsn-escape");
    try {
      String productRoot = root.toFile().getAbsolutePath() + (rootHasTrailingSlash ? "/" : "");
      String resolved = new File(OFSNUtils.relativeize(ofsn, productRoot)).getCanonicalPath();
      String canonicalRoot = root.toFile().getCanonicalPath();

      assertTrue(
          resolved.equals(canonicalRoot) || resolved.startsWith(canonicalRoot + File.separator),
          "OFSN [" + ofsn + "] resolved to [" + resolved + "], outside [" + canonicalRoot + "]");
    } finally {
      deleteTree(root);
    }
  }

  /** A path segment, including the ones a traversal attempt would use. */
  private static Generator<String> pathSegment() {
    return sampledFrom(Arrays.asList("a", "b", "sub", "..", ".", "etc", "passwd"));
  }

  /**
   * The listing reports the true size of every file when asked to, and reports
   * no size at all when not. A client uses this to decide whether to fetch.
   */
  @HegelTest(testCases = 30)
  void theListingReportsTheTrueFileSizeExactlyWhenAsked(TestCase tc) throws Exception {
    List<Integer> sizes =
        tc.draw(lists(integers().min(0).max(64)).minSize(1).maxSize(5), "sizes");
    boolean showFileSize = tc.draw(booleans(), "showFileSize");

    Path root = Files.createTempDirectory("ofsn-sizes");
    try {
      List<File> files = new ArrayList<File>();
      for (int i = 0; i < sizes.size(); i++) {
        Path file = root.resolve("f" + i);
        Files.write(file, new byte[sizes.get(i)]);
        files.add(file.toFile());
      }

      Document doc =
          OFSNUtils.getOFSNDoc(
              files,
              listingConfig(),
              root.toFile().getAbsolutePath(),
              false,
              showFileSize);

      NodeList reported = doc.getElementsByTagName(FILE_SIZE_TAG);
      if (!showFileSize) {
        assertEquals(0, reported.getLength(), "sizes were reported although none were asked for");
        return;
      }

      assertEquals(sizes.size(), reported.getLength(), "a file's size went unreported");
      for (int i = 0; i < sizes.size(); i++) {
        assertEquals(
            String.valueOf((long) (int) sizes.get(i)),
            reported.item(i).getTextContent(),
            "the size reported for f" + i + " is not the size on disk");
      }
    } finally {
      deleteTree(root);
    }
  }

  /**
   * An empty query, built without going anywhere near the SQL parser: the
   * where-element set is filled in directly, as the parser would have filled
   * it, in matched element/literal pairs.
   */
  private static XMLQuery unparsedQuery() {
    return new XMLQuery(null, null, null, null, null, null, null, null, 1, null, false);
  }

  /**
   * A field asked for by name comes back with the literal that was written
   * against it. This is how the handler reads the OFSN and the return type out
   * of the client's query, so reading the wrong literal means serving the
   * wrong file.
   */
  @HegelTest
  void aQueryFieldComesBackWithItsOwnLiteral(TestCase tc) {
    List<String> fields = tc.draw(lists(fileName()).minSize(1).maxSize(5), "fields");
    List<String> values = tc.draw(lists(fileName()).minSize(1).maxSize(5), "values");
    int wanted = tc.draw(integers().min(0).max(fields.size() - 1), "wanted");

    XMLQuery query = unparsedQuery();
    List<String> distinct = new ArrayList<String>();
    List<String> paired = new ArrayList<String>();
    for (int i = 0; i < fields.size(); i++) {
      if (distinct.contains(fields.get(i))) {
        continue;
      }
      distinct.add(fields.get(i));
      paired.add(values.get(i % values.size()));
      query.getWhereElementSet().add(new QueryElement("elemName", fields.get(i)));
      query.getWhereElementSet().add(new QueryElement("LITERAL", values.get(i % values.size())));
    }

    int index = Math.min(wanted, distinct.size() - 1);
    assertEquals(
        paired.get(index),
        OFSNUtils.extractFieldFromQuery(query, distinct.get(index)),
        "the literal returned for [" + distinct.get(index) + "] belongs to another field");
  }

  /** A field the query never mentions has no value, rather than someone else's. */
  @HegelTest
  void anAbsentQueryFieldHasNoValue(TestCase tc) {
    List<String> fields = tc.draw(lists(fileName()).minSize(0).maxSize(5), "fields");
    String value = tc.draw(fileName(), "value");
    String absent = tc.draw(fileName(), "absent");

    XMLQuery query = unparsedQuery();
    for (String field : fields) {
      if (field.equalsIgnoreCase(absent)) {
        return;
      }
      query.getWhereElementSet().add(new QueryElement("elemName", field));
      query.getWhereElementSet().add(new QueryElement("LITERAL", value));
    }

    assertEquals(null, OFSNUtils.extractFieldFromQuery(query, absent));
  }
}
