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

package org.apache.oodt.cas.pge.writers;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of {@link TextConfigFileWriter}, which fills a template out of
 * CAS-PGE metadata and writes the result where the PGE expects to find it.
 *
 * <p>Each property writes into a fresh temporary directory and deletes it in a
 * {@code finally} block. Templates are generated without square brackets: an
 * unresolvable {@code [...]} expression sends
 * {@link org.apache.oodt.cas.pge.util.XmlHelper#fillIn} into a loop that
 * allocates while it spins, which is a separate known defect and not something
 * a test may provoke.
 */
class TextConfigFileWriterPropertyTest {

  /** Text a PGE author would put in a template. */
  private static final Generator<String> WORD =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  /** A character that is significant to something in the chain. */
  private static final Generator<String> AWKWARD =
      sampledFrom(List.of("<", ">", "&", "\"", "'", "%", "\n", "\t", "é", "中"));

  private static File freshDir() throws IOException {
    return Files.createTempDirectory("pge-pbt").toFile();
  }

  private static void delete(File dir) {
    File[] children = dir.listFiles();
    if (children != null) {
      for (File child : children) {
        delete(child);
      }
    }
    if (!dir.delete()) {
      dir.deleteOnExit();
    }
  }

  /**
   * A template with nothing to substitute is written out as it stands. The PGE
   * reads this file as its input; a writer that edits a template it was not
   * asked to edit changes the run.
   */
  @HegelTest(testCases = 30)
  void aTemplateIsWrittenOutUnchanged(TestCase tc) throws Exception {
    List<String> lines = tc.draw(lists(WORD).minSize(1).maxSize(6), "lines");
    String template = String.join("\n", lines);

    File dir = freshDir();
    try {
      File target = new File(dir, "pge-input.txt");
      File written = new TextConfigFileWriter().createConfigFile(
          target.getAbsolutePath(), new Metadata(), template);

      assertTrue(written.exists(), "the writer reported a file it did not create");
      String contents = new String(
          Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8);
      assertEquals(template + System.lineSeparator(), contents,
          "the template was altered on its way to the file");
    } finally {
      delete(dir);
    }
  }

  /**
   * Characters that are significant to XML, to shells, or to the metadata
   * substitution itself pass through into the file unchanged. A PGE input file
   * routinely carries regular expressions, quoted paths and free text.
   */
  @HegelTest(testCases = 30)
  void significantCharactersReachTheFile(TestCase tc) throws Exception {
    String prefix = tc.draw(WORD, "prefix");
    String marker = tc.draw(AWKWARD, "marker");
    String suffix = tc.draw(WORD, "suffix");
    String template = prefix + marker + suffix;

    File dir = freshDir();
    try {
      File target = new File(dir, "pge-input.txt");
      File written = new TextConfigFileWriter().createConfigFile(
          target.getAbsolutePath(), new Metadata(), template);

      String contents = new String(
          Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8);
      assertTrue(contents.startsWith(template),
          "the template was mangled: wrote [" + contents + "] for [" + template + "]");
    } finally {
      delete(dir);
    }
  }

  /**
   * Asked to write with no template at all, the writer reports the problem
   * rather than leaving a half-written file behind. CAS-PGE turns this into a
   * failed task; silently writing nothing would let the PGE run on an empty
   * input.
   */
  @HegelTest(testCases = 20)
  void aMissingTemplateIsReported(TestCase tc) throws Exception {
    String name = tc.draw(WORD, "name");

    File dir = freshDir();
    try {
      String target = new File(dir, name + ".txt").getAbsolutePath();
      assertThrows(Exception.class,
          () -> new TextConfigFileWriter().createConfigFile(target, new Metadata()));
    } finally {
      delete(dir);
    }
  }
}
