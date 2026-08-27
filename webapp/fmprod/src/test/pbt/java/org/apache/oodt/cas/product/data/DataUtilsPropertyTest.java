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

package org.apache.oodt.cas.product.data;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of the download helpers in {@link DataUtils}.
 *
 * <p>These are the two things the file manager's data servlet does with a
 * product before handing it to a browser: guess a content type for it, and pack
 * it into a zip. The zip cases write into a fresh temporary directory and
 * remove it afterwards, so the case count is kept modest.
 */
class DataUtilsPropertyTest {

  /** The extensions {@link DataUtils#guessTypeFromName} claims to know. */
  private static final List<String[]> KNOWN_TYPES =
      Arrays.asList(
          new String[] {".jpg", "image/jpeg"},
          new String[] {".jpeg", "image/jpeg"},
          new String[] {".png", "image/png"},
          new String[] {".gif", "image/gif"},
          new String[] {".doc", "application/msword"},
          new String[] {".pdf", "application/pdf"},
          new String[] {".rtf", "application/rtf"},
          new String[] {".xls", "application/vnd.ms-excel"},
          new String[] {".ppt", "application/vnd.ms-powerpoint"},
          new String[] {".html", "text/html"},
          new String[] {".htm", "text/html"},
          new String[] {".xml", "text/xml"},
          new String[] {".txt", "text/plain"});

  private static final List<String> NAME_CHARS =
      Arrays.asList("a", "b", "c", "d", "e", "1", "2", "-", "_");

  private static Generator<String> baseName() {
    return lists(sampledFrom(NAME_CHARS)).minSize(1).maxSize(8).map(cs -> String.join("", cs));
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  /**
   * A file with a known extension is served as its own content type, whatever
   * case the extension is written in. A browser decides whether to display or
   * download on this answer alone.
   */
  @HegelTest
  void aKnownExtensionAlwaysGivesItsOwnContentType(TestCase tc) {
    String base = tc.draw(baseName(), "base");
    String[] known = tc.draw(sampledFrom(KNOWN_TYPES), "known");
    boolean upperCase = tc.draw(booleans(), "upperCase");

    String extension = upperCase ? known[0].toUpperCase() : known[0];

    assertEquals(known[1], DataUtils.guessTypeFromName(base + extension));
  }

  /**
   * A name whose extension is not one of the known ones falls back to the
   * generic binary type, rather than to whichever known type happens to appear
   * somewhere earlier in the name.
   */
  @HegelTest
  void anUnknownExtensionFallsBackToOctetStream(TestCase tc) {
    String base = tc.draw(baseName(), "base");
    String[] known = tc.draw(sampledFrom(KNOWN_TYPES), "known");
    String unknown = tc.draw(baseName(), "unknown");

    String name = base + known[0] + "." + unknown;
    if (isKnownExtension("." + unknown)) {
      return;
    }

    assertEquals("application/octet-stream", DataUtils.guessTypeFromName(name));
  }

  private static boolean isKnownExtension(String extension) {
    for (String[] known : KNOWN_TYPES) {
      if (known[0].equalsIgnoreCase(extension)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The zip a user downloads holds every file the product refers to, plus the
   * product's metadata. This is the whole contract of the download: a caller
   * that unpacks it must not find a file missing.
   */
  @HegelTest(testCases = 25)
  void theProductZipHoldsEveryReferencedFileAndTheMetadata(TestCase tc) throws Exception {
    List<String> dirs = tc.draw(lists(baseName()).minSize(1).maxSize(3), "dirs");
    List<String> names = tc.draw(lists(baseName()).minSize(1).maxSize(4), "names");
    int contentSize = tc.draw(integers().min(0).max(32), "contentSize");
    String productName = tc.draw(baseName(), "productName");

    Path root = Files.createTempDirectory("datautils-zip");
    try {
      List<Reference> refs = new ArrayList<Reference>();
      Set<String> distinctPaths = new HashSet<String>();
      for (String dir : dirs) {
        Path subdir = root.resolve(dir);
        Files.createDirectories(subdir);
        for (String name : names) {
          Path file = subdir.resolve(name);
          if (!distinctPaths.add(file.toString())) {
            continue;
          }
          Files.write(file, new byte[contentSize]);
          Reference ref = new Reference();
          ref.setDataStoreReference(file.toUri().toString());
          ref.setOrigReference(file.toUri().toString());
          ref.setFileSize(contentSize);
          refs.add(ref);
        }
      }

      Product product = new Product();
      product.setProductId("id-" + productName);
      product.setProductName(productName);
      product.setProductStructure(Product.STRUCTURE_FLAT);
      product.setProductType(new ProductType());
      product.setProductReferences(refs);

      Metadata met = new Metadata();
      met.addMetadata("ProductName", productName);

      Path workingDir = Files.createTempDirectory("datautils-out");
      try {
        String zipPath =
            DataUtils.createProductZipFile(
                product, met, workingDir.toFile().getAbsolutePath());

        Set<String> entries = new HashSet<String>();
        ZipFile zip = new ZipFile(zipPath);
        try {
          java.util.Enumeration<? extends ZipEntry> e = zip.entries();
          while (e.hasMoreElements()) {
            entries.add(e.nextElement().getName());
          }
        } finally {
          zip.close();
        }

        assertTrue(
            entries.contains(productName + ".met"),
            "the product's metadata is missing from the zip");
        for (Reference ref : refs) {
          String fileName = new File(new java.net.URI(ref.getDataStoreReference())).getName();
          assertTrue(
              entries.contains(fileName),
              "the zip does not hold the referenced file [" + fileName + "]");
        }
        assertEquals(
            refs.size() + 1,
            entries.size(),
            "the zip holds a different number of entries than the product has files");
      } finally {
        deleteTree(workingDir);
      }
    } finally {
      deleteTree(root);
    }
  }
}
