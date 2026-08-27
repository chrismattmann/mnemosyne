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

package org.apache.oodt.pcs.tools;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.pcs.PcsConfigFixture;
import org.apache.oodt.pcs.input.PGEConfigurationFile;
import org.apache.oodt.pcs.input.PGEGroup;
import org.apache.oodt.pcs.input.PGEScalar;
import org.apache.oodt.pcs.input.PGEVector;
import org.apache.oodt.pcs.listing.ListingConfKeys;

/**
 * Properties of the listing {@link PCSLongLister} produces.
 *
 * <p>The long lister prints a table: a header row of column names, then one row
 * per product. The file manager here is a closed port, so every cell is
 * "unknown" — which is exactly the case worth pinning down, because the shape
 * of the table must not depend on whether the catalog had an answer.
 */
class PCSLongListerPropertyTest {

  /** A URL nothing is listening on. */
  private static final String DEAD_URL = "http://127.0.0.1:1";

  private static final Generator<String> KEY =
      text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");

  private static final Generator<String> PRODUCT_NAME =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  private static PGEConfigurationFile listingConf(
      List<String> columns, List<String> collectionColumns) {
    PGEConfigurationFile conf = new PGEConfigurationFile();

    PGEGroup cols = new PGEGroup(ListingConfKeys.MET_FIELD_COLS_GROUP);
    cols.addVector(new PGEVector(
        ListingConfKeys.MET_FIELDS_ORDER_VECTOR, new ArrayList<Object>(columns)));
    for (String column : columns) {
      cols.addScalar(new PGEScalar(column, column));
    }
    conf.getPgeSpecificGroups().put(cols.getName(), cols);

    // The file format requires a vector to hold at least one element, so a
    // configuration with no collection columns simply omits the group.
    if (!collectionColumns.isEmpty()) {
      PGEGroup collections = new PGEGroup(ListingConfKeys.COLLECTION_FIELDS_GROUP);
      collections.addVector(new PGEVector(
          ListingConfKeys.COLLECTION_FIELDS_NAMES, new ArrayList<Object>(collectionColumns)));
      conf.getPgeSpecificGroups().put(collections.getName(), collections);
    }

    return conf;
  }

  private static List<String> distinct(List<String> values) {
    Set<String> set = new LinkedHashSet<>(values);
    return new ArrayList<>(set);
  }

  /**
   * The listing is a rectangle: a header row plus exactly one row per product
   * asked for, every row carrying one cell per configured column. Anything else
   * misaligns the table, and this tool is routinely piped into {@code column}
   * or {@code awk}.
   */
  @HegelTest(testCases = 8)
  void theListingIsOneRowPerProductWithOneCellPerColumn(TestCase tc) throws Exception {
    List<String> columns = distinct(tc.draw(lists(KEY).minSize(1).maxSize(5), "columns"));
    List<String> products =
        tc.draw(lists(PRODUCT_NAME).minSize(1).maxSize(5), "products");

    File dir = PcsConfigFixture.freshDir();
    PrintStream originalOut = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      File confFile = PcsConfigFixture.write(
          listingConf(columns, new ArrayList<String>()), dir, "pcs-ll-conf.xml");
      PCSLongLister lister = new PCSLongLister(DEAD_URL, confFile.getAbsolutePath());

      System.setOut(new PrintStream(captured, true, "UTF-8"));
      lister.doList(products);
      System.setOut(originalOut);

      String[] lines = captured.toString("UTF-8").split("\n", -1);
      // The trailing element after the final newline is not a row.
      int rowCount = lines.length - 1;
      assertEquals(products.size() + 1, rowCount,
          "expected a header plus one row per product, got " + rowCount + " rows");

      for (int i = 0; i < rowCount; i++) {
        // Each cell is followed by a tab, so splitting keeps a trailing empty.
        assertEquals(columns.size() + 1, lines[i].split("\t", -1).length,
            "row " + i + " does not have one cell per column");
      }
    } finally {
      System.setOut(originalOut);
      PcsConfigFixture.delete(dir);
    }
  }

  /**
   * A column with no value for a product renders as a marker, not as the four
   * characters {@code null}.
   *
   * <p>The class already has {@code outputOrBlank}, whose whole job is to turn
   * "nothing to show" into {@code N/A} — but only collection columns go through
   * it. A plain column with no metadata is appended straight from
   * {@code Metadata.getMetadata}, which returns null, and {@code StringBuilder}
   * renders that as the literal text {@code null} in the operator's table.
   */
  @HegelTest(testCases = 8)
  void anEmptyCellIsMarkedNotPrintedAsNull(TestCase tc) throws Exception {
    List<String> columns = distinct(tc.draw(lists(KEY).minSize(1).maxSize(4), "columns"));
    String product = tc.draw(PRODUCT_NAME, "product");

    File dir = PcsConfigFixture.freshDir();
    PrintStream originalOut = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      File confFile = PcsConfigFixture.write(
          listingConf(columns, new ArrayList<String>()), dir, "pcs-ll-conf.xml");
      PCSLongLister lister = new PCSLongLister(DEAD_URL, confFile.getAbsolutePath());

      System.setOut(new PrintStream(captured, true, "UTF-8"));
      lister.doList(List.of(product));
      System.setOut(originalOut);

      String[] lines = captured.toString("UTF-8").split("\n", -1);
      String row = lines[1];
      for (String cell : row.split("\t", -1)) {
        assertFalse("null".equals(cell),
            "an unknown cell was printed as the literal text 'null': [" + row + "]");
      }
    } finally {
      System.setOut(originalOut);
      PcsConfigFixture.delete(dir);
    }
  }
}
