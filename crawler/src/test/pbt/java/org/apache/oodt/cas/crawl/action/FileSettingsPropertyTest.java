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

package org.apache.oodt.cas.crawl.action;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.util.List;

/**
 * Properties of the ancillary-file naming in {@link FileSettings}.
 *
 * <p>This is the rule that turns {@code image.jpg} into
 * {@code cool_image_ancillary.png}. The file-based actions and the FileExists
 * precondition all resolve their target through it, so what matters is that the
 * directory is never changed, the product's own name is never lost, and an
 * unconfigured {@code FileSettings} is the identity.
 */
class FileSettingsPropertyTest {

  private static final String SEPARATOR = System.getProperty("file.separator", "/");

  /** Names safe to embed in a path: letters and digits only. */
  private static Generator<String> word(int min, int max) {
    return text().minSize(min).maxSize(max).categories("Lu", "Ll", "Nd");
  }

  /** A product file: an optional directory, a stem, and an optional extension. */
  private static File drawProduct(TestCase tc) {
    List<String> dirs = tc.draw(lists(word(1, 6)).maxSize(3), "dirs");
    String stem = tc.draw(word(1, 10), "stem");
    boolean hasExtension = tc.draw(booleans(), "hasExtension");
    String extension = tc.draw(word(1, 4), "extension");

    StringBuilder path = new StringBuilder();
    for (String dir : dirs) {
      path.append(dir).append(SEPARATOR);
    }
    path.append(stem);
    if (hasExtension) {
      path.append('.').append(extension);
    }
    return new File(path.toString());
  }

  /** The part of a file name before its last dot, which is what the code keeps. */
  private static String stemOf(File file) {
    String name = file.getName();
    int dot = name.lastIndexOf('.');
    return dot == -1 ? name : name.substring(0, dot);
  }

  /** The last dot and everything after it, or the empty string. */
  private static String extensionOf(File file) {
    String name = file.getName();
    int dot = name.lastIndexOf('.');
    return dot == -1 ? "" : name.substring(dot);
  }

  /**
   * An unconfigured {@code FileSettings} names the product itself. A file-based
   * action with no prefix, suffix or extension configured is asking for the
   * product file, and anything else would send it to a path that does not
   * exist.
   */
  @HegelTest
  void anUnconfiguredSettingNamesTheProductItself(TestCase tc) {
    File product = drawProduct(tc);

    FileSettings settings = new FileSettings();

    assertEquals(product.getPath(), settings.getPreparedFileString(product));
  }

  /**
   * The ancillary file always sits beside the product. None of the four
   * settings is about the directory, so none of them may move the file.
   */
  @HegelTest
  void theDirectoryIsNeverChanged(TestCase tc) {
    File product = drawProduct(tc);
    String prefix = tc.draw(word(0, 6), "prefix");
    String suffix = tc.draw(word(0, 6), "suffix");
    boolean keepExisting = tc.draw(booleans(), "keepExisting");
    boolean setExtension = tc.draw(booleans(), "setExtension");
    String extension = tc.draw(word(1, 4), "extension");

    FileSettings settings = new FileSettings();
    settings.setFilePrefix(prefix);
    settings.setFileSuffix(suffix);
    settings.setKeepExistingExtension(keepExisting);
    if (setExtension) {
      settings.setFileExtension(extension);
    }

    File prepared = new File(settings.getPreparedFileString(product));

    assertEquals(product.getParent(), prepared.getParent(), "the file moved directory");
  }

  /**
   * The product's own name survives every transformation. The whole point of
   * the mapping is that {@code image.jpg} still reads as {@code image}, so a
   * result that has lost the stem has lost the link to its product.
   */
  @HegelTest
  void theProductStemAlwaysSurvives(TestCase tc) {
    File product = drawProduct(tc);
    String prefix = tc.draw(word(0, 6), "prefix");
    String suffix = tc.draw(word(0, 6), "suffix");
    boolean keepExisting = tc.draw(booleans(), "keepExisting");

    FileSettings settings = new FileSettings();
    settings.setFilePrefix(prefix);
    settings.setFileSuffix(suffix);
    settings.setKeepExistingExtension(keepExisting);

    String name = new File(settings.getPreparedFileString(product)).getName();

    assertTrue(
        name.contains(stemOf(product)),
        "'" + name + "' no longer contains the product stem '" + stemOf(product) + "'");
    assertTrue(name.startsWith(prefix), "'" + name + "' does not start with the prefix");
  }

  /**
   * Asking to keep the existing extension keeps exactly the existing extension,
   * and asking not to drops exactly it. A product named {@code image.jpg} whose
   * checksum lives at {@code image.jpg.md5} and one whose checksum lives at
   * {@code image.md5} are two different files on disk.
   */
  @HegelTest
  void theExistingExtensionIsKeptOrDroppedAsAsked(TestCase tc) {
    File product = drawProduct(tc);
    boolean keepExisting = tc.draw(booleans(), "keepExisting");

    FileSettings settings = new FileSettings();
    settings.setKeepExistingExtension(keepExisting);

    String name = new File(settings.getPreparedFileString(product)).getName();

    assertEquals(keepExisting ? stemOf(product) + extensionOf(product) : stemOf(product), name);
  }

  /**
   * A configured extension is the extension the result ends with. Actions use
   * this to point at a fixed companion format, so the answer has to end in that
   * format.
   */
  @HegelTest
  void aConfiguredExtensionIsTheOneTheResultEndsWith(TestCase tc) {
    File product = drawProduct(tc);
    String extension = tc.draw(word(1, 4), "extension");
    boolean keepExisting = tc.draw(booleans(), "keepExisting");

    FileSettings settings = new FileSettings();
    settings.setFileExtension(extension);
    settings.setKeepExistingExtension(keepExisting);

    String prepared = settings.getPreparedFileString(product);

    assertTrue(prepared.endsWith("." + extension), "'" + prepared + "' does not end in the extension");
  }
}
