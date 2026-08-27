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

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.oodt.cas.crawl.status.IngestStatus;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.metadata.SerializableMetadata;

/**
 * Properties of the directory walk in {@link ProductCrawler#crawl(File)}.
 *
 * <p>The walk decides which files on disk become candidate products, and it is
 * the part of a crawl an operator can least easily check: a file the walk
 * misses is simply never ingested, silently. Each case here builds a small tree
 * under a fresh temporary directory, crawls it, and compares the products the
 * crawler reported against the tree that was actually written. Nothing talks to
 * a file manager — ingest is switched off, which is a supported configuration
 * and the one a dry run uses — and the case counts are kept modest because
 * every case writes files.
 */
class ProductCrawlerWalkPropertyTest {

  /**
   * A crawler that accepts whatever it is given. The only thing overridden
   * beyond the three abstract methods is the ingester setup, which would
   * otherwise open a client to a file manager that is not running.
   */
  private static class WalkingCrawler extends ProductCrawler {
    @Override
    protected boolean passesPreconditions(File product) {
      return true;
    }

    @Override
    protected Metadata getMetadataForProduct(File product) {
      return new Metadata();
    }

    @Override
    protected File renameProduct(File product, Metadata productMetadata) {
      return product;
    }

    @Override
    void setupIngester() {
      // no file manager in a dry run
    }
  }

  /** The standard crawler, with the same ingester setup removed. */
  private static class WalkingStdCrawler extends StdProductCrawler {
    @Override
    void setupIngester() {
      // no file manager in a dry run
    }
  }

  private static final List<String> NAME_CHARS = Arrays.asList("a", "b", "c", "d", "e");

  private static Generator<String> name() {
    return lists(sampledFrom(NAME_CHARS)).minSize(1).maxSize(4).map(cs -> String.join("", cs));
  }

  private static void deleteTree(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  /** Absolute paths of every regular file below {@code root}, sorted. */
  private static TreeSet<String> filesUnder(Path root) throws IOException {
    TreeSet<String> found = new TreeSet<String>();
    try (Stream<Path> paths = Files.walk(root)) {
      paths.filter(Files::isRegularFile).forEach(p -> found.add(p.toFile().getAbsolutePath()));
    }
    return found;
  }

  /** Absolute paths of every directory below {@code root}, excluding the root. */
  private static TreeSet<String> directoriesUnder(Path root) throws IOException {
    TreeSet<String> found = new TreeSet<String>();
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .filter(Files::isDirectory)
          .filter(p -> !p.equals(root))
          .forEach(p -> found.add(p.toFile().getAbsolutePath()));
    }
    return found;
  }

  private static TreeSet<String> productsReportedBy(ProductCrawler crawler) {
    TreeSet<String> reported = new TreeSet<String>();
    for (IngestStatus status : crawler.getIngestStatus()) {
      reported.add(status.getProduct().getAbsolutePath());
    }
    return reported;
  }

  /**
   * Writes a two-level tree: some files in the root, some directories, and the
   * same file names again inside each of them.
   */
  private static void writeTree(Path root, List<String> dirs, List<String> files)
      throws IOException {
    for (String file : files) {
      Files.write(root.resolve("f" + file), new byte[] {1});
    }
    for (String dir : dirs) {
      Path sub = root.resolve("d" + dir);
      Files.createDirectories(sub);
      for (String file : files) {
        Files.write(sub.resolve("f" + file), new byte[] {2});
      }
    }
  }

  /** A crawler configured for a dry run: no ingest, and a product type to hand on. */
  private static void configureForDryRun(ProductCrawler crawler) {
    crawler.setSkipIngest(true);
    Metadata global = new Metadata();
    global.addMetadata(ProductCrawler.PRODUCT_TYPE, "GenericFile");
    crawler.setGlobalMetadata(global);
  }

  /**
   * A recursive crawl considers every file in the tree and nothing else. A file
   * the walk never visits is never ingested and never reported, so the operator
   * has no way to learn it was skipped.
   */
  @HegelTest(testCases = 25)
  void aRecursiveCrawlConsidersEveryFileInTheTree(TestCase tc) throws Exception {
    List<String> dirs = tc.draw(lists(name()).maxSize(3), "dirs");
    List<String> files = tc.draw(lists(name()).maxSize(3), "files");

    Path root = Files.createTempDirectory("crawl-recursive");
    try {
      writeTree(root, dirs, files);

      WalkingCrawler crawler = new WalkingCrawler();
      configureForDryRun(crawler);
      crawler.crawl(root.toFile());

      assertEquals(
          filesUnder(root),
          productsReportedBy(crawler),
          "the crawl did not consider exactly the files in the tree");
    } finally {
      deleteTree(root);
    }
  }

  /**
   * Every file the crawler considers is reported exactly once. A duplicated
   * status means a product would be ingested twice.
   */
  @HegelTest(testCases = 25)
  void everyFileIsReportedExactlyOnce(TestCase tc) throws Exception {
    List<String> dirs = tc.draw(lists(name()).maxSize(3), "dirs");
    List<String> files = tc.draw(lists(name()).maxSize(3), "files");

    Path root = Files.createTempDirectory("crawl-once");
    try {
      writeTree(root, dirs, files);

      WalkingCrawler crawler = new WalkingCrawler();
      configureForDryRun(crawler);
      crawler.crawl(root.toFile());

      List<IngestStatus> statuses = crawler.getIngestStatus();
      assertEquals(
          statuses.size(),
          productsReportedBy(crawler).size(),
          "a file was reported more than once");
    } finally {
      deleteTree(root);
    }
  }

  /**
   * With recursion switched off, the crawl considers the files in the root
   * directory and stops there. That is the whole meaning of the setting.
   */
  @HegelTest(testCases = 25)
  void aNonRecursiveCrawlStaysInTheRootDirectory(TestCase tc) throws Exception {
    List<String> dirs = tc.draw(lists(name()).minSize(1).maxSize(3), "dirs");
    List<String> files = tc.draw(lists(name()).maxSize(3), "files");

    Path root = Files.createTempDirectory("crawl-norecur");
    try {
      writeTree(root, dirs, files);

      WalkingCrawler crawler = new WalkingCrawler();
      configureForDryRun(crawler);
      crawler.setNoRecur(true);
      crawler.crawl(root.toFile());

      TreeSet<String> expected = new TreeSet<String>();
      File[] children = root.toFile().listFiles();
      for (File child : children == null ? new File[0] : children) {
        if (child.isFile()) {
          expected.add(child.getAbsolutePath());
        }
      }

      assertEquals(expected, productsReportedBy(crawler), "a non-recursive crawl left the root");
    } finally {
      deleteTree(root);
    }
  }

  /**
   * A crawl for directories considers every directory below the root and no
   * regular file. This is how a hierarchical product type is crawled, where the
   * product is the directory rather than the files in it.
   */
  @HegelTest(testCases = 25)
  void aCrawlForDirectoriesConsidersEveryDirectoryAndNoFile(TestCase tc) throws Exception {
    List<String> dirs = tc.draw(lists(name()).maxSize(3), "dirs");
    List<String> files = tc.draw(lists(name()).maxSize(2), "files");

    Path root = Files.createTempDirectory("crawl-dirs");
    try {
      writeTree(root, dirs, files);

      WalkingCrawler crawler = new WalkingCrawler();
      configureForDryRun(crawler);
      crawler.setCrawlForDirs(true);
      crawler.crawl(root.toFile());

      assertEquals(
          directoriesUnder(root),
          productsReportedBy(crawler),
          "the crawl did not consider exactly the directories in the tree");
    } finally {
      deleteTree(root);
    }
  }

  /**
   * Pointed at a file rather than a directory, the crawl covers that file's
   * directory. The product path is operator-supplied and this is the documented
   * behaviour of the stack seeding in {@code crawl}.
   */
  @HegelTest(testCases = 20)
  void crawlingAFileCoversTheDirectoryItIsIn(TestCase tc) throws Exception {
    List<String> files = tc.draw(lists(name()).minSize(1).maxSize(3), "files");

    Path root = Files.createTempDirectory("crawl-file");
    try {
      writeTree(root, new ArrayList<String>(), files);

      File[] children = root.toFile().listFiles();
      File one = children[0];

      WalkingCrawler crawler = new WalkingCrawler();
      configureForDryRun(crawler);
      crawler.setNoRecur(true);
      crawler.crawl(one);

      assertTrue(
          productsReportedBy(crawler).contains(one.getAbsolutePath()),
          "the file the crawler was pointed at was not considered");
    } finally {
      deleteTree(root);
    }
  }

  /** A crawl root that is not there is refused rather than silently doing nothing. */
  @HegelTest(testCases = 20)
  void anAbsentCrawlRootIsRefused(TestCase tc) throws Exception {
    String missing = tc.draw(name(), "missing");
    boolean nullRoot = tc.draw(booleans(), "nullRoot");

    Path root = Files.createTempDirectory("crawl-absent");
    try {
      WalkingCrawler crawler = new WalkingCrawler();
      configureForDryRun(crawler);
      File dirRoot = nullRoot ? null : root.resolve("absent" + missing).toFile();

      assertThrows(IllegalArgumentException.class, () -> crawler.crawl(dirRoot));
    } finally {
      deleteTree(root);
    }
  }

  /**
   * The standard crawler treats exactly those files that have a metadata file
   * beside them as products. That rule is the entire selection policy of
   * {@link StdProductCrawler}: a file with a {@code .met} beside it is ingested,
   * anything else is passed over.
   */
  @HegelTest(testCases = 25)
  void theStandardCrawlerPicksExactlyTheFilesWithAMetFileBesideThem(TestCase tc)
      throws Exception {
    List<String> files = tc.draw(lists(name()).minSize(1).maxSize(4), "files");
    List<Boolean> hasMet =
        tc.draw(lists(booleans()).minSize(1).maxSize(4), "hasMet");

    Path root = Files.createTempDirectory("crawl-std");
    try {
      TreeSet<String> withMet = new TreeSet<String>();
      List<String> written = new ArrayList<String>();
      for (int i = 0; i < files.size(); i++) {
        String fileName = "f" + files.get(i);
        if (written.contains(fileName)) {
          continue;
        }
        written.add(fileName);
        Path file = root.resolve(fileName);
        Files.write(file, new byte[] {1});

        if (hasMet.get(i % hasMet.size())) {
          writeMetFile(root.resolve(fileName + ".met"));
          withMet.add(file.toFile().getAbsolutePath());
        }
      }

      WalkingStdCrawler crawler = new WalkingStdCrawler();
      crawler.setSkipIngest(true);
      crawler.crawl(root.toFile());

      TreeSet<String> selected = new TreeSet<String>();
      for (IngestStatus status : crawler.getIngestStatus()) {
        if (status.getResult() != IngestStatus.Result.PRECONDS_FAILED) {
          selected.add(status.getProduct().getAbsolutePath());
        }
      }

      assertEquals(
          withMet, selected, "the crawler selected a different set of files than the met files name");
    } finally {
      deleteTree(root);
    }
  }

  /** A met file carrying the fields the file manager requires. */
  private static void writeMetFile(Path metFile) throws Exception {
    Metadata met = new Metadata();
    met.addMetadata(ProductCrawler.PRODUCT_TYPE, "GenericFile");
    SerializableMetadata serialisable = new SerializableMetadata(met, "UTF-8", false);
    OutputStream out = new FileOutputStream(metFile.toFile());
    try {
      serialisable.writeMetadataToXmlStream(out);
    } finally {
      out.close();
    }
  }
}
