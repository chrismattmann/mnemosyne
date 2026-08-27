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

package org.apache.oodt.cas.filemgr.structs.query;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * The {@link java.util.Comparator} contract for {@link QueryResultComparator},
 * which is what {@code SORT_BY} on a complex query runs on.
 *
 * <p>A comparator that breaks the contract is not merely untidy:
 * {@code Collections.sort} is entitled to throw
 * "Comparison method violates its general contract!" partway through sorting a
 * user's results, and the order it does produce is unspecified. The sort key is
 * a metadata element that need not be present on every product, so the
 * properties are stated over results where the key is sometimes missing.
 */
class QueryResultComparatorPropertyTest {

  private static final String SORT_KEY = "DataVersion";

  /** {@code "-"} stands in for a result that has no value for the sort key. */
  private static final String ABSENT = "-";

  private static Generator<String> sortValues() {
    return sampledFrom(List.of("a", "b", "c", ABSENT));
  }

  private static QueryResultComparator comparator() {
    QueryResultComparator comparator = new QueryResultComparator();
    comparator.setSortByMetKey(SORT_KEY);
    return comparator;
  }

  private static QueryResult resultWith(String value) {
    Metadata metadata = new Metadata();
    if (!ABSENT.equals(value)) {
      metadata.addMetadata(SORT_KEY, value);
    }
    return new QueryResult(new Product(), metadata);
  }

  private static List<QueryResult> resultsFor(List<String> values) {
    List<QueryResult> results = new ArrayList<>(values.size());
    for (String value : values) {
      results.add(resultWith(value));
    }
    return results;
  }

  /** Reversing the arguments must reverse the answer, and nothing else. */
  @HegelTest
  void comparisonIsAntisymmetric(TestCase tc) {
    String left = tc.draw(sortValues(), "left");
    String right = tc.draw(sortValues(), "right");

    QueryResultComparator comparator = comparator();
    int forwards = comparator.compare(resultWith(left), resultWith(right));
    int backwards = comparator.compare(resultWith(right), resultWith(left));

    assertEquals(
        Integer.signum(forwards),
        -Integer.signum(backwards),
        "compare(" + left + ", " + right + ") and its reverse do not agree");
  }

  /** A result is neither before nor after itself. */
  @HegelTest
  void comparisonIsReflexive(TestCase tc) {
    String value = tc.draw(sortValues(), "value");

    assertEquals(0, comparator().compare(resultWith(value), resultWith(value)));
  }

  /**
   * If a sorts at or before b, and b at or before c, then a must sort at or
   * before c. Without this the sort has no fixed point to work towards.
   */
  @HegelTest
  void comparisonIsTransitive(TestCase tc) {
    String a = tc.draw(sortValues(), "a");
    String b = tc.draw(sortValues(), "b");
    String c = tc.draw(sortValues(), "c");

    QueryResultComparator comparator = comparator();
    tc.assume(comparator.compare(resultWith(a), resultWith(b)) <= 0);
    tc.assume(comparator.compare(resultWith(b), resultWith(c)) <= 0);

    assertTrue(
        comparator.compare(resultWith(a), resultWith(c)) <= 0,
        a + " <= " + b + " <= " + c + " but " + a + " sorts after " + c);
  }

  /**
   * Sorting a page of results must return the same results, in an order the
   * comparator itself agrees with. Losing or duplicating a product here means
   * a user is shown a result set that does not match their query.
   */
  @HegelTest
  void sortIsAnOrderingPermutationOfTheResults(TestCase tc) {
    List<String> values = tc.draw(lists(sortValues()).minSize(0).maxSize(20), "values");

    QueryResultComparator comparator = comparator();
    List<QueryResult> results = resultsFor(values);
    List<QueryResult> sorted = new ArrayList<>(results);
    try {
      sorted.sort(comparator);
    } catch (IllegalArgumentException e) {
      fail("sort rejected the comparator: " + e.getMessage());
      return;
    }

    assertEquals(results.size(), sorted.size(), "sorting changed the number of results");
    List<QueryResult> before = new ArrayList<>(results);
    List<QueryResult> after = new ArrayList<>(sorted);
    for (QueryResult result : before) {
      assertTrue(after.remove(result), "a result went missing during the sort");
    }
    assertTrue(after.isEmpty(), "the sort invented results");

    for (int i = 1; i < sorted.size(); i++) {
      assertTrue(
          comparator.compare(sorted.get(i - 1), sorted.get(i)) <= 0,
          "results at " + (i - 1) + " and " + i + " came out in the wrong order");
    }
  }

  /**
   * Results with no value for the sort key are "the least interesting" per the
   * comparator's own comment, so they must land at the end of the page rather
   * than in among the results that do have one.
   */
  @HegelTest
  void resultsMissingTheSortKeyGoLast(TestCase tc) {
    List<String> values = tc.draw(lists(sortValues()).minSize(1).maxSize(20), "values");

    List<QueryResult> sorted = resultsFor(values);
    Collections.sort(sorted, comparator());

    boolean seenAbsent = false;
    for (QueryResult result : sorted) {
      boolean absent = result.getMetadata().getMetadata(SORT_KEY) == null;
      if (absent) {
        seenAbsent = true;
      } else {
        assertTrue(!seenAbsent, "a result with a sort value came after one without");
      }
    }
  }
}
