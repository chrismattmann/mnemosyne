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

package org.apache.oodt.cas.crawl.typedetection;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;

/**
 * Properties of the precondition list carried by {@link MetExtractorSpec}.
 *
 * <p>{@code AutoDetectProductCrawler} iterates the comparator ids of every spec
 * it is handed, without a null check, for every product it looks at. A spec
 * built by the XML reader for an extractor with no {@code preCondComparators}
 * element never has that list set, so the accessor has to answer with an empty
 * list rather than nothing.
 */
class MetExtractorSpecPropertyTest {

  private static Generator<String> comparatorId() {
    return text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");
  }

  /** A spec with no preconditions configured still offers a list to walk. */
  @HegelTest
  void anUnconfiguredSpecOffersAnEmptyPreconditionList(TestCase tc) {
    tc.note("an <extractor> element with no <preCondComparators>");

    MetExtractorSpec spec = new MetExtractorSpec();

    List<String> ids = spec.getPreCondComparatorIds();
    assertNotNull(ids, "the precondition list was null");
    assertTrue(ids.isEmpty(), "an unconfigured spec claims preconditions: " + ids);
  }

  /**
   * The comparator ids come back in the order the XML listed them. The crawler
   * runs preconditions in that order and stops at the first that fails, so the
   * order is a decision the config author made.
   */
  @HegelTest
  void theConfiguredComparatorIdsComeBackInOrder(TestCase tc) {
    List<String> ids = tc.draw(lists(comparatorId()).maxSize(8), "ids");

    MetExtractorSpec spec = new MetExtractorSpec();
    spec.setPreConditionComparatorIds(ids);

    assertEquals(ids, spec.getPreCondComparatorIds());
  }

  /**
   * Clearing the preconditions leaves a list to walk, not a null. The reader
   * only sets the list when the element is present, so "no preconditions" and
   * "preconditions explicitly cleared" have to look the same to the crawler.
   */
  @HegelTest
  void clearingThePreconditionsStillOffersAListToWalk(TestCase tc) {
    List<String> ids = tc.draw(lists(comparatorId()).maxSize(4), "ids");

    MetExtractorSpec spec = new MetExtractorSpec();
    spec.setPreConditionComparatorIds(ids);
    spec.setPreConditionComparatorIds(null);

    List<String> after = spec.getPreCondComparatorIds();
    assertNotNull(after, "the precondition list was null after being cleared");
    assertTrue(after.isEmpty(), "a cleared spec still claims preconditions: " + after);
  }
}
