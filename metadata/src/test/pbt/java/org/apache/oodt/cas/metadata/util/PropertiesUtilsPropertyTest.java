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

package org.apache.oodt.cas.metadata.util;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Properties of {@link PropertiesUtils}, which reads comma-delimited system
 * properties into arrays.
 *
 * <p>This is how the extractors and the crawler read their configuration, so a
 * dropped or mis-trimmed element is a mis-configured component that starts
 * anyway. Every property here sets a system property, reads it back, and
 * restores whatever was there before in a {@code finally}.
 *
 * <p>The class had no unit tests.
 */
class PropertiesUtilsPropertyTest {

  /** A property name no other test or component will be using. */
  private static final String NAME = "org.apache.oodt.cas.metadata.pbt.testProperty";

  /**
   * Values with no comma, no bracket and no leading or trailing space of their
   * own, so that the comma is unambiguously the delimiter and
   * {@code PathUtils.replaceEnvVariables} has nothing to substitute.
   */
  private static Generator<String> values() {
    return fromRegex("[a-zA-Z0-9_./-]{1,8}");
  }

  /** Padding a hand-written configuration file plausibly contains. */
  private static Generator<String> padding() {
    return sampledFrom("", " ", "  ", "\t");
  }

  /** Run a body with the property set, restoring the previous value afterwards. */
  private static void withProperty(String value, Runnable body) {
    String previous = System.getProperty(NAME);
    try {
      if (value == null) {
        System.clearProperty(NAME);
      } else {
        System.setProperty(NAME, value);
      }
      body.run();
    } finally {
      if (previous == null) {
        System.clearProperty(NAME);
      } else {
        System.setProperty(NAME, previous);
      }
    }
  }

  /**
   * A comma-delimited property reads back as exactly the values that were
   * written into it, in order, with the whitespace around each comma removed.
   * That whitespace is present in every hand-written configuration file.
   */
  @HegelTest
  void aDelimitedPropertyReadsBackAsItsElements(TestCase tc) {
    List<String> values = tc.draw(lists(values()).minSize(1).maxSize(6), "values");
    List<String> before = tc.draw(lists(padding()).minSize(1).maxSize(6), "before");
    List<String> after = tc.draw(lists(padding()).minSize(1).maxSize(6), "after");

    List<String> pieces = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      pieces.add(before.get(i % before.size()) + values.get(i) + after.get(i % after.size()));
    }
    String property = String.join(",", pieces);

    withProperty(
        property,
        () ->
            assertArrayEquals(
                values.toArray(new String[0]),
                PropertiesUtils.getProperties(NAME),
                "property [" + property + "] parsed into the wrong elements"));
  }

  /**
   * A property that was never set falls back to the defaults it was given, and
   * the caller gets its own array back rather than a handle on the defaults it
   * passed in — otherwise writing to the result corrupts the fallback for
   * everyone else.
   */
  @HegelTest
  void anUnsetPropertyFallsBackToItsDefaults(TestCase tc) {
    List<String> defaults = tc.draw(lists(values()).minSize(1).maxSize(4), "defaults");
    String[] defaultArray = defaults.toArray(new String[0]);

    withProperty(
        null,
        () -> {
          String[] read = PropertiesUtils.getProperties(NAME, defaultArray);
          assertArrayEquals(defaultArray, read, "the defaults were not used");
          assertNotSame(defaultArray, read, "the caller's default array was handed straight back");
          assertEquals(defaults.get(0), PropertiesUtils.getProperty(NAME, defaults.get(0)));
        });
  }

  /**
   * A property that <em>is</em> set wins over the defaults. A default that
   * shadowed real configuration would be silently ignored settings.
   */
  @HegelTest
  void aSetPropertyBeatsItsDefaults(TestCase tc) {
    List<String> values = tc.draw(lists(values()).minSize(1).maxSize(4), "values");
    List<String> defaults = tc.draw(lists(values()).minSize(1).maxSize(4), "defaults");
    String property = String.join(",", values);

    withProperty(
        property,
        () -> {
          assertArrayEquals(
              values.toArray(new String[0]),
              PropertiesUtils.getProperties(NAME, defaults.toArray(new String[0])),
              "the defaults shadowed a property that was set");
          assertEquals(values.get(0), PropertiesUtils.getProperty(NAME, defaults.get(0)));
        });
  }

  /**
   * An empty property is no elements, not one empty element. A component that
   * saw one blank entry where the operator meant none would try to act on it.
   */
  @HegelTest
  void anEmptyPropertyHasNoElements(TestCase tc) {
    String blank = tc.draw(sampledFrom("", ",", ",,", ",,,"), "blank");

    withProperty(
        blank,
        () ->
            assertEquals(
                0,
                PropertiesUtils.getProperties(NAME).length,
                "property [" + blank + "] produced elements"));
  }

  /**
   * Reading a property that was never set answers rather than failing with an
   * index error. Callers reach for the single-value read to fetch optional
   * configuration, and an {@link ArrayIndexOutOfBoundsException} out of a
   * lookup is not a condition any of them are written to handle — the
   * component simply dies on startup with a stack trace that names an array,
   * not the missing setting.
   */
  @HegelTest
  void readingAnUnsetPropertyDoesNotFailWithAnIndexError(TestCase tc) {
    tc.draw(values(), "unused");

    withProperty(
        null,
        () ->
            assertDoesNotThrow(
                () -> PropertiesUtils.getProperty(NAME),
                "reading the unset property [" + NAME + "] threw"));
  }
}
