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
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of {@link CsvConfigFileWriter}, which turns named metadata fields
 * into a CSV file for a PGE to read.
 *
 * <p>The class documents its own contract in its Javadoc: given the header
 * {@code InputFiles,IsText} and multi-valued metadata for both, it produces a
 * header line followed by one line per value, comma separated. The properties
 * here are that contract, stated over generated columns and values.
 */
class CsvConfigFileWriterPropertyTest {

  /** A metadata field name. */
  private static final Generator<String> COLUMN =
      text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");

  /** A metadata value. Commas are excluded: they are the field separator. */
  private static final Generator<String> VALUE =
      text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");

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

  private static List<String> distinct(List<String> values) {
    Set<String> set = new LinkedHashSet<>(values);
    return new ArrayList<>(set);
  }

  /**
   * The header line of the generated file is the requested columns, comma
   * separated. The PGE reads this line to work out which column is which, so
   * anything else in it makes every value below it unattributable.
   */
  @HegelTest(testCases = 25)
  void theHeaderLineIsTheRequestedColumns(TestCase tc) throws Exception {
    List<String> columns = distinct(tc.draw(lists(COLUMN).minSize(1).maxSize(5), "columns"));

    File dir = freshDir();
    try {
      File target = new File(dir, "input.csv");
      // No metadata at all, so the file is header-only: this isolates the
      // header from the row generation.
      File written = new CsvConfigFileWriter().createConfigFile(
          target.getAbsolutePath(), new Metadata(), String.join(",", columns));

      String contents = new String(
          Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8);
      String headerLine = contents.split("\\R", -1)[0];

      assertEquals(String.join(",", columns), headerLine,
          "the header line is not the requested columns");
    } finally {
      delete(dir);
    }
  }

  /**
   * A CSV file has one row per value: the writer's own Javadoc says that three
   * input files and three flags produce three rows. This is the whole purpose
   * of the class — the PGE is handed a table, one row per product it is to
   * process.
   */
  @HegelTest(testCases = 25)
  void thereIsOneRowPerMetadataValue(TestCase tc) throws Exception {
    List<String> columns = distinct(tc.draw(lists(COLUMN).minSize(1).maxSize(3), "columns"));
    List<String> values = tc.draw(lists(VALUE).minSize(1).maxSize(4), "values");

    Metadata metadata = new Metadata();
    for (String column : columns) {
      metadata.addMetadata(column, new ArrayList<>(values));
    }

    File dir = freshDir();
    try {
      File target = new File(dir, "input.csv");
      File written = new CsvConfigFileWriter().createConfigFile(
          target.getAbsolutePath(), metadata, String.join(",", columns));

      String contents = new String(
          Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8);
      String[] lines = contents.split("\\R", -1);

      assertEquals(values.size() + 1, lines.length - 1,
          "expected a header plus one row per value, got: " + contents);
      for (int row = 0; row < values.size(); row++) {
        StringBuilder expected = new StringBuilder();
        for (int col = 0; col < columns.size(); col++) {
          if (col > 0) {
            expected.append(",");
          }
          expected.append(values.get(row));
        }
        assertEquals(expected.toString(), lines[row + 1], "row " + row + " is wrong");
      }
    } finally {
      delete(dir);
    }
  }
}
