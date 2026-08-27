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

package org.apache.oodt.cas.crawl;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.oodt.cas.crawl.status.IngestStatus;
import org.apache.oodt.cas.metadata.util.MimeTypeUtils;

/**
 * Properties of the file selection {@link AutoDetectProductCrawler} makes.
 *
 * <p>This crawler decides what to ingest by working out each file's MIME type
 * and looking for a metadata extractor configured against it. That decision is
 * invisible to an operator — a file whose type has no extractor is passed over
 * silently — so it is worth pinning down that it is made on the type and on
 * nothing else.
 *
 * <p>Each case writes a real extractor map, a real MIME type registry and a
 * small tree of files into a fresh temporary directory. Ingest is switched off,
 * so no file manager is contacted; only the selection is exercised. The case
 * count is small because every case writes files and re-reads the Tika type
 * registry.
 */
class AutoDetectProductCrawlerPropertyTest {

  /** The standard crawler with the ingester setup removed, as in a dry run. */
  private static class WalkingAutoDetectCrawler extends AutoDetectProductCrawler {
    @Override
    void setupIngester() {
      // no file manager in a dry run
    }
  }

  private static final List<String> NAME_CHARS = Arrays.asList("a", "b", "c", "d");

  private static Generator<String> name() {
    return lists(sampledFrom(NAME_CHARS)).minSize(1).maxSize(4).map(cs -> String.join("", cs));
  }

  /** Extensions with no metadata extractor configured against their type. */
  private static Generator<String> unconfiguredExtension() {
    return sampledFrom(Arrays.asList(".png", ".pdf", ".zip"));
  }

  private static void deleteTree(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  /**
   * Writes an extractor map configuring one extractor, for {@code text/plain}
   * only, alongside the Tika type registry the metadata module ships.
   *
   * @return the path of the map file
   */
  private static Path writeExtractorMap(Path dir) throws IOException {
    Path registry = dir.resolve("mimetypes.xml");
    InputStream in =
        MimeTypeUtils.class.getResourceAsStream(MimeTypeUtils.MIME_FILE_RES_PATH);
    try {
      Files.copy(in, registry, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      in.close();
    }

    Path map = dir.resolve("mime-extractor-map.xml");
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<cas:mimetypemap xmlns:cas=\"http://oodt.jpl.nassa.gov/1.0/cas\""
            + " magic=\"false\" mimeRepo=\"mimetypes.xml\">\n"
            + "  <mime type=\"text/plain\">\n"
            + "    <extractor"
            + " class=\"org.apache.oodt.cas.metadata.extractors.MetReaderExtractor\"/>\n"
            + "  </mime>\n"
            + "</cas:mimetypemap>\n";
    Files.write(map, xml.getBytes(StandardCharsets.UTF_8));
    return map;
  }

  /**
   * The crawler considers a file if, and only if, its type has an extractor
   * configured. This is the whole of the crawler's selection policy: a file it
   * does not consider is never ingested and is reported as having failed its
   * preconditions, which is the only trace an operator gets.
   */
  @HegelTest(testCases = 15)
  void onlyFilesWhoseTypeHasAnExtractorAreConsidered(TestCase tc) throws Exception {
    List<String> configured = tc.draw(lists(name()).maxSize(3), "configured");
    List<String> unconfigured = tc.draw(lists(name()).maxSize(3), "unconfigured");
    String otherExtension = tc.draw(unconfiguredExtension(), "otherExtension");

    Path work = Files.createTempDirectory("autodetect");
    try {
      Path map = writeExtractorMap(work);
      Path products = work.resolve("products");
      Files.createDirectories(products);

      TreeSet<String> expected = new TreeSet<String>();
      for (String base : configured) {
        Path file = products.resolve("c" + base + ".txt");
        Files.write(file, "some text\n".getBytes(StandardCharsets.UTF_8));
        expected.add(file.toFile().getAbsolutePath());
      }
      for (String base : unconfigured) {
        Path file = products.resolve("u" + base + otherExtension);
        Files.write(file, new byte[] {0, 1, 2, 3});
      }

      WalkingAutoDetectCrawler crawler = new WalkingAutoDetectCrawler();
      crawler.setSkipIngest(true);
      crawler.setMimeExtractorRepo(map.toFile().getAbsolutePath());
      crawler.crawl(products.toFile());

      TreeSet<String> considered = new TreeSet<String>();
      for (IngestStatus status : crawler.getIngestStatus()) {
        if (status.getResult() != IngestStatus.Result.PRECONDS_FAILED) {
          considered.add(status.getProduct().getAbsolutePath());
        }
      }

      assertEquals(
          expected,
          considered,
          "the crawler considered a different set of files than the extractor map names");
    } finally {
      deleteTree(work);
    }
  }
}
