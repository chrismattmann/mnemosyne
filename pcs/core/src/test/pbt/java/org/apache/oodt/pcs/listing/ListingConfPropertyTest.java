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

package org.apache.oodt.pcs.listing;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.apache.oodt.pcs.listing.ListingConfKeys.COLLECTION_FIELDS_GROUP;
import static org.apache.oodt.pcs.listing.ListingConfKeys.COLLECTION_FIELDS_NAMES;
import static org.apache.oodt.pcs.listing.ListingConfKeys.EXCLUDED_PRODUCT_TYPE_GROUP;
import static org.apache.oodt.pcs.listing.ListingConfKeys.EXCLUDED_VECTOR;
import static org.apache.oodt.pcs.listing.ListingConfKeys.MET_FIELDS_ORDER_VECTOR;
import static org.apache.oodt.pcs.listing.ListingConfKeys.MET_FIELD_COLS_GROUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.pcs.PcsConfigFixture;
import org.apache.oodt.pcs.input.PGEConfigurationFile;
import org.apache.oodt.pcs.input.PGEGroup;
import org.apache.oodt.pcs.input.PGEScalar;
import org.apache.oodt.pcs.input.PGEVector;

/**
 * Properties of {@link ListingConf}, the configuration behind the PCS long
 * lister.
 *
 * <p>Every accessor on this class answers a question the lister asks once per
 * column of output: which columns, what to call them, which are collections,
 * which product types to skip. Each property here writes a configuration file
 * into a fresh temporary directory and asserts the answer matches what was
 * written.
 */
class ListingConfPropertyTest {

  /** A metadata key name, as it appears in {@code pcs-ll-conf.xml}. */
  private static final Generator<String> KEY =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  /** A column display name. */
  private static final Generator<String> LABEL =
      text().minSize(0).maxSize(16).categories("Lu", "Ll", "Nd");

  private static List<String> distinct(List<String> values) {
    Set<String> set = new LinkedHashSet<>(values);
    return new ArrayList<>(set);
  }

  /**
   * The ordered column keys come back in the order configured. The lister
   * prints one column per key in that order, so a reordering is a visibly wrong
   * listing.
   */
  @HegelTest(testCases = 25)
  void headerColumnKeysKeepTheirOrder(TestCase tc) throws Exception {
    List<String> keys = tc.draw(lists(KEY).minSize(1).maxSize(6), "keys");

    PGEConfigurationFile conf = new PGEConfigurationFile();
    PGEGroup cols = new PGEGroup(MET_FIELD_COLS_GROUP);
    cols.addVector(new PGEVector(MET_FIELDS_ORDER_VECTOR, new ArrayList<Object>(keys)));
    conf.getPgeSpecificGroups().put(cols.getName(), cols);

    File dir = PcsConfigFixture.freshDir();
    try {
      File file = PcsConfigFixture.write(conf, dir, "pcs-ll-conf.xml");
      ListingConf listingConf = new ListingConf(file);

      assertEquals(keys, listingConf.getHeaderColKeys(), "the column order changed");
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }

  /**
   * Each column's display name is the one configured for it. This is the text
   * a user reads at the top of the listing.
   */
  @HegelTest(testCases = 25)
  void everyColumnKeepsItsDisplayName(TestCase tc) throws Exception {
    List<String> keys = distinct(tc.draw(lists(KEY).minSize(1).maxSize(6), "keys"));
    List<String> labels = tc.draw(lists(LABEL).minSize(1).maxSize(6), "labels");

    PGEConfigurationFile conf = new PGEConfigurationFile();
    PGEGroup cols = new PGEGroup(MET_FIELD_COLS_GROUP);
    cols.addVector(new PGEVector(MET_FIELDS_ORDER_VECTOR, new ArrayList<Object>(keys)));
    for (int i = 0; i < keys.size(); i++) {
      cols.addScalar(new PGEScalar(keys.get(i), labels.get(i % labels.size())));
    }
    conf.getPgeSpecificGroups().put(cols.getName(), cols);

    File dir = PcsConfigFixture.freshDir();
    try {
      File file = PcsConfigFixture.write(conf, dir, "pcs-ll-conf.xml");
      ListingConf listingConf = new ListingConf(file);

      for (int i = 0; i < keys.size(); i++) {
        assertEquals(labels.get(i % labels.size()),
            listingConf.getHeaderColDisplayName(keys.get(i)),
            "display name for column [" + keys.get(i) + "] changed");
      }
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }

  /**
   * A field is a collection field exactly when the configuration says so. The
   * lister takes a completely different code path for collection fields, so a
   * wrong answer either way produces the wrong cell.
   *
   * <p>At least one collection field is generated: the configuration file
   * format requires a vector to have at least one element, and the reader says
   * so explicitly, so an empty {@code <vector/>} is not a file an operator can
   * write.
   */
  @HegelTest(testCases = 25)
  void collectionFieldsAreExactlyTheOnesConfigured(TestCase tc) throws Exception {
    List<String> collectionFields =
        distinct(tc.draw(lists(KEY).minSize(1).maxSize(5), "collectionFields"));
    String other = tc.draw(KEY, "other");

    PGEConfigurationFile conf = new PGEConfigurationFile();
    PGEGroup group = new PGEGroup(COLLECTION_FIELDS_GROUP);
    group.addVector(
        new PGEVector(COLLECTION_FIELDS_NAMES, new ArrayList<Object>(collectionFields)));
    conf.getPgeSpecificGroups().put(group.getName(), group);

    File dir = PcsConfigFixture.freshDir();
    try {
      File file = PcsConfigFixture.write(conf, dir, "pcs-ll-conf.xml");
      ListingConf listingConf = new ListingConf(file);

      for (String field : collectionFields) {
        assertTrue(listingConf.isCollectionField(field),
            "configured collection field [" + field + "] was not recognised");
      }
      if (!collectionFields.contains(other)) {
        assertFalse(listingConf.isCollectionField(other),
            "field [" + other + "] was treated as a collection without being configured as one");
      }
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }

  /**
   * The excluded product types come back as configured. The lister passes this
   * list straight to the catalog query, so an omission silently widens the
   * search.
   */
  @HegelTest(testCases = 25)
  void excludedProductTypesSurviveTheFile(TestCase tc) throws Exception {
    List<String> excluded = tc.draw(lists(KEY).minSize(1).maxSize(6), "excluded");

    PGEConfigurationFile conf = new PGEConfigurationFile();
    PGEGroup group = new PGEGroup(EXCLUDED_PRODUCT_TYPE_GROUP);
    group.addVector(new PGEVector(EXCLUDED_VECTOR, new ArrayList<Object>(excluded)));
    conf.getPgeSpecificGroups().put(group.getName(), group);

    File dir = PcsConfigFixture.freshDir();
    try {
      File file = PcsConfigFixture.write(conf, dir, "pcs-ll-conf.xml");
      ListingConf listingConf = new ListingConf(file);

      assertEquals(excluded, listingConf.getExcludedTypes(), "the exclude list changed");
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }

  /**
   * A configuration that leaves out an optional section is answerable, not
   * fatal. {@link ListingConf} null-checks every group precisely so that a
   * minimal configuration file works; this pins that behaviour down for a file
   * that declares only one of the three sections.
   */
  @HegelTest(testCases = 20)
  void aMinimalConfigurationAnswersEveryQuestion(TestCase tc) throws Exception {
    List<String> keys = tc.draw(lists(KEY).minSize(1).maxSize(4), "keys");
    String probe = tc.draw(KEY, "probe");

    PGEConfigurationFile conf = new PGEConfigurationFile();
    PGEGroup cols = new PGEGroup(MET_FIELD_COLS_GROUP);
    cols.addVector(new PGEVector(MET_FIELDS_ORDER_VECTOR, new ArrayList<Object>(keys)));
    conf.getPgeSpecificGroups().put(cols.getName(), cols);

    File dir = PcsConfigFixture.freshDir();
    try {
      File file = PcsConfigFixture.write(conf, dir, "pcs-ll-conf.xml");
      ListingConf listingConf = new ListingConf(file);

      assertTrue(listingConf.getExcludedTypes().isEmpty(),
          "exclusions appeared from a file with no exclusion section");
      assertFalse(listingConf.isCollectionField(probe),
          "a collection field appeared from a file with no collection section");
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }
}
