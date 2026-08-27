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

package org.apache.oodt.cas.curation.service;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Properties of the staging-area listings the curator browses.
 *
 * <p>{@link DirectoryResource#getDirectoryAreaAsHTML} and
 * {@link CurationService#getDirectoryAreaAsJSON} both take the directory to
 * list as a parameter rather than reading the static service config, so both
 * can be driven against a real temporary directory with no servlet container.
 *
 * <p>The listing is a round trip, not just a rendering: each entry carries the
 * path the browser sends back to open that entry, and
 * {@link CurationService#cleansePath} is what turns it back into a path. Every
 * property below is about that round trip over the file names a staging area
 * really holds. Each case writes files, so the case counts are modest.
 */
class DirectoryListingPropertyTest {

  /** Characters a staging file name is made of. */
  private static final List<String> PLAIN_CHARS =
      Arrays.asList("a", "b", "c", "D", "1", "2", "_", "-");

  /**
   * Names that a curator would not think twice about: a version number with a
   * plus in it, a percentage in a filename, a name with an ampersand.
   */
  private static final List<String> AWKWARD_CHARS = Arrays.asList("+", "&", "%", " ", ".");

  private static Generator<String> plainName() {
    return lists(sampledFrom(PLAIN_CHARS)).minSize(1).maxSize(5).map(cs -> String.join("", cs));
  }

  private static void deleteTree(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  private static Document parse(String html) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(html.getBytes("UTF-8")));
  }

  /** The {@code rel} of every entry in a rendered listing, in document order. */
  private static List<String> relsIn(String html) throws Exception {
    NodeList anchors = parse(html).getElementsByTagName("a");
    List<String> rels = new ArrayList<String>();
    for (int i = 0; i < anchors.getLength(); i++) {
      rels.add(((Element) anchors.item(i)).getAttribute("rel"));
    }
    return rels;
  }

  /**
   * A listing shows every file in the directory it was asked about. This is the
   * curator's only view of the staging area; a file it does not show is a file
   * that cannot be ingested through the UI.
   */
  @HegelTest(testCases = 25)
  void theHtmlListingShowsEveryFileInTheDirectory(TestCase tc) throws Exception {
    List<String> names = tc.draw(lists(plainName()).minSize(1).maxSize(5), "names");

    Path root = Files.createTempDirectory("curator-html");
    try {
      TreeSet<String> written = new TreeSet<String>();
      for (String name : names) {
        Files.write(root.resolve(name), new byte[] {1});
        written.add(name);
      }

      String html =
          new DirectoryResource().getDirectoryAreaAsHTML(root.toFile().getAbsolutePath(), "/", true);

      NodeList anchors = parse(html).getElementsByTagName("a");
      TreeSet<String> shown = new TreeSet<String>();
      for (int i = 0; i < anchors.getLength(); i++) {
        shown.add(anchors.item(i).getTextContent());
      }

      assertEquals(written, shown, "the listing does not show the files in the directory");
    } finally {
      deleteTree(root);
    }
  }

  /**
   * The path a listing entry carries, sent back the way the browser sends it,
   * names the file that entry stands for. The whole staging UI is built on this
   * round trip: the {@code rel} value is what the browser posts back to expand
   * or ingest an entry, and {@link CurationService#cleansePath} is what turns
   * it back into a path on disk.
   */
  @HegelTest(testCases = 30)
  void anEntrysPathComesBackNamingTheSameFile(TestCase tc) throws Exception {
    String before = tc.draw(plainName(), "before");
    String awkward = tc.draw(sampledFrom(AWKWARD_CHARS), "awkward");
    String after = tc.draw(plainName(), "after");
    String name = before + awkward + after;

    Path root = Files.createTempDirectory("curator-roundtrip");
    try {
      Files.write(root.resolve(name), new byte[] {1});

      DirectoryResource resource = new DirectoryResource();
      String base = root.toFile().getAbsolutePath();
      String html = resource.getDirectoryAreaAsHTML(base, "/", true);

      List<String> rels = relsIn(html);
      assertEquals(1, rels.size(), "the listing did not describe the one file in the directory");

      String returned = resource.cleansePath(rels.get(0));
      File resolved = new File(base + "/" + returned);
      assertTrue(
          resolved.exists(),
          "the path [" + rels.get(0) + "] came back as [" + returned + "], which names no file");
    } finally {
      deleteTree(root);
    }
  }

  /**
   * A JSON listing describes every entry in the directory it was asked about.
   * The tree view of the staging area is built from it: the curator clicks a
   * folder, the browser sends that folder's path back, and the entries returned
   * are the ones shown inside it. The folder names are the ones a staging area
   * really uses.
   */
  @HegelTest(testCases = 25)
  void theJsonListingDescribesEveryEntryInTheDirectory(TestCase tc) throws Exception {
    String folder =
        tc.draw(
            sampledFrom(Arrays.asList("data", "source", "incoming", "archive")), "folder");
    List<String> names = tc.draw(lists(plainName()).minSize(1).maxSize(4), "names");

    Path root = Files.createTempDirectory("curator-json");
    try {
      Path listed = root.resolve(folder);
      Files.createDirectories(listed);

      TreeSet<String> written = new TreeSet<String>();
      for (String name : names) {
        Files.createDirectories(listed.resolve(name));
        written.add(name);
      }

      String json =
          new CurationService()
              .getDirectoryAreaAsJSON(root.toFile().getAbsolutePath(), folder, false);

      JSONArray entries = JSONArray.fromObject(json);
      TreeSet<String> described = new TreeSet<String>();
      for (int i = 0; i < entries.size(); i++) {
        described.add(((JSONObject) entries.get(i)).getString("text"));
      }

      assertEquals(
          written,
          described,
          "the JSON listing does not describe the entries of [" + folder + "]");
    } finally {
      deleteTree(root);
    }
  }

  /**
   * Listing a directory that is not there gives an empty listing rather than
   * failing. A staging area empties as things are ingested, so a browser
   * holding a slightly stale tree will ask about directories that have just
   * gone; the HTML listing already answers that with an empty list.
   */
  @HegelTest(testCases = 20)
  void listingAnAbsentDirectoryGivesAnEmptyListing(TestCase tc) throws Exception {
    String gone = tc.draw(plainName(), "gone");

    Path root = Files.createTempDirectory("curator-absent");
    try {
      String base = root.toFile().getAbsolutePath();

      String html = new DirectoryResource().getDirectoryAreaAsHTML(base, "/" + gone, true);
      assertEquals(0, relsIn(html).size(), "the HTML listing invented an entry");

      String json = new CurationService().getDirectoryAreaAsJSON(base, gone, true);
      assertEquals(0, JSONArray.fromObject(json).size(), "the JSON listing invented an entry");
    } finally {
      deleteTree(root);
    }
  }
}
