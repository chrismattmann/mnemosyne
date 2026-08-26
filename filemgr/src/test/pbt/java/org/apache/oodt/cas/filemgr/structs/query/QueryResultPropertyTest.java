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
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.util.QueryUtils;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties for {@link QueryResult}, one row of a complex query's answer, and
 * for the formatting the CLI renders it with.
 *
 * <p>{@code QueryResult} defines both {@code equals} and {@code hashCode}, and
 * results are collected and de-duplicated on the way out of a multi-product-type
 * query, so the two have to agree. Its {@code toString} is the other half:
 * that is literally what a {@code --outputFormat} query prints, so a placeholder
 * left unfilled or a value dropped is what the user reads.
 */
class QueryResultPropertyTest {

  private static Generator<String> words() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  private static QueryResult result(Product product, Metadata metadata, String format) {
    QueryResult result = new QueryResult(product, metadata);
    result.setToStringFormat(format);
    return result;
  }

  private static Metadata metadata(String key, List<String> values) {
    Metadata metadata = new Metadata();
    metadata.replaceMetadata(key, values);
    return metadata;
  }

  /** Two results built from the same parts must be equal and hash alike. */
  @HegelTest
  void resultsOverTheSamePartsAreEqualAndHashAlike(TestCase tc) {
    String key = tc.draw(words(), "key");
    List<String> values = tc.draw(lists(words()).minSize(1).maxSize(3), "values");
    String format = tc.draw(words(), "format");
    Product product = Product.getDefaultFlatProduct(tc.draw(words(), "name"), "type-1");

    QueryResult first = result(product, metadata(key, values), format);
    QueryResult second = result(product, metadata(key, values), format);

    assertTrue(first.equals(second), "the same result compared unequal to itself");
    assertEquals(first.hashCode(), second.hashCode(), "equal results hash differently");
  }

  /** Duplicate results must collapse to one in a set, and distinct ones must not. */
  @HegelTest
  void aSetDeDuplicatesEqualResults(TestCase tc) {
    String key = tc.draw(words(), "key");
    List<String> values = tc.draw(lists(words()).minSize(1).maxSize(3), "values");
    Product product = Product.getDefaultFlatProduct(tc.draw(words(), "name"), "type-1");

    Set<QueryResult> seen = new HashSet<>();
    seen.add(result(product, metadata(key, values), null));
    seen.add(result(product, metadata(key, values), null));

    assertEquals(1, seen.size(), "the same result was counted twice");
  }

  /** Equality must be symmetric, and must notice a differing output format. */
  @HegelTest
  void equalityIsSymmetricAndSeesTheFormat(TestCase tc) {
    String key = tc.draw(words(), "key");
    List<String> values = tc.draw(lists(words()).minSize(1).maxSize(3), "values");
    String formatA = tc.draw(words(), "formatA");
    String formatB = tc.draw(words(), "formatB");
    tc.assume(!formatA.equals(formatB));
    Product product = Product.getDefaultFlatProduct(tc.draw(words(), "name"), "type-1");

    QueryResult first = result(product, metadata(key, values), formatA);
    QueryResult second = result(product, metadata(key, values), formatB);

    assertEquals(first.equals(second), second.equals(first), "equality is asymmetric");
    assertFalse(first.equals(second), "a differing output format went unnoticed");
  }

  /**
   * With no format set, every metadata value the result carries must be printed.
   *
   * <p>The unformatted rendering is what a plain query prints, and a value
   * missing from it is a value the user is never told about.
   */
  @HegelTest
  void theUnformattedRenderingPrintsEveryValue(TestCase tc) {
    String key = tc.draw(words(), "key");
    List<String> values = tc.draw(lists(words()).minSize(1).maxSize(3), "values");
    Product product = Product.getDefaultFlatProduct(tc.draw(words(), "name"), "type-1");

    String rendered = result(product, metadata(key, values), null).toString();
    tc.note(rendered);

    for (String value : values) {
      assertTrue(rendered.contains(value), "'" + value + "' is missing from: " + rendered);
    }
  }

  /**
   * A placeholder naming a key the result carries must be replaced by its value.
   *
   * <p>This is the {@code --outputFormat} contract. A placeholder that survives
   * into the output is a literal {@code $Key} printed where a value was asked
   * for.
   */
  @HegelTest
  void aPlaceholderIsReplacedByItsValue(TestCase tc) {
    String key = tc.draw(words().filter(w -> w.matches("[A-Za-z][A-Za-z0-9]*")), "key");
    String value = tc.draw(words(), "value");
    Product product = Product.getDefaultFlatProduct(tc.draw(words(), "name"), "type-1");

    String rendered = result(product, metadata(key, List.of(value)), "$" + key).toString();
    tc.note(rendered);

    assertEquals(value, rendered, "the placeholder was not replaced");
  }

  /**
   * Joining results into a string must keep every result and every separator
   * between them.
   *
   * <p>{@link QueryUtils#getQueryResultsAsString} is what the CLI prints for a
   * whole query; a dropped result or a trailing delimiter is visible in the
   * output and breaks anything parsing it.
   */
  @HegelTest
  void resultsJoinWithExactlyOneDelimiterBetweenThem(TestCase tc) {
    List<String> values = tc.draw(lists(words()).minSize(1).maxSize(4), "values");
    Product product = Product.getDefaultFlatProduct("granule", "type-1");
    List<QueryResult> results =
        values.stream()
            .map(v -> result(product, metadata("Key", List.of(v)), "$Key"))
            .collect(java.util.stream.Collectors.toList());

    String joined = QueryUtils.getQueryResultsAsString(results, "|");
    tc.note(joined);

    assertEquals(String.join("|", values), joined);
  }

  /** An empty result list must render as nothing rather than as a stray delimiter. */
  @HegelTest
  void anEmptyResultListRendersAsNothing(TestCase tc) {
    String delimiter = tc.draw(words(), "delimiter");

    assertEquals("", QueryUtils.getQueryResultsAsString(List.of(), delimiter));
  }
}
