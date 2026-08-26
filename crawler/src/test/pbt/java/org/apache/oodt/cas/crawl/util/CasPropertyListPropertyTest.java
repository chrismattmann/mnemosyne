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

package org.apache.oodt.cas.crawl.util;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Properties of the comma-separated value parsing in {@link CasPropertyList}.
 *
 * <p>The only caller is Spring, filling a bean property from an attribute such
 * as {@code value="a, b, c"}. So the contract is the one a bean author assumes:
 * the values come back in the order written, one entry each, with the spacing
 * around the commas discarded.
 */
class CasPropertyListPropertyTest {

  /** A value as it would appear in a bean file: no comma, no leading space. */
  private static Generator<String> value() {
    return text()
        .minSize(1)
        .maxSize(12)
        .categories("Lu", "Ll", "Nd")
        .includeCharacters(".-_/");
  }

  /** Whitespace a bean author might leave around a comma. */
  private static String padding(TestCase tc, String label) {
    int spaces = tc.draw(integers().min(0).max(3), label);
    return " ".repeat(spaces);
  }

  /**
   * A list of values written out with commas comes back as that list. This is
   * the round trip the bean file depends on and the reason the class exists.
   */
  @HegelTest
  void aCommaSeparatedListRoundTrips(TestCase tc) {
    List<String> values = tc.draw(lists(value()).minSize(1).maxSize(8), "values");

    StringBuilder written = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        written.append(padding(tc, "before" + i)).append(',').append(padding(tc, "after" + i));
      }
      written.append(values.get(i));
    }

    CasPropertyList list = new CasPropertyList();
    list.setValues(written.toString());

    assertEquals(values, new ArrayList<>(list));
  }

  /**
   * No entry keeps a comma or the spacing around it. Every entry is handed on
   * as a property name or path, so a stray space would be part of the key.
   */
  @HegelTest
  void everyEntryIsTrimmedAndCommaFree(TestCase tc) {
    String written = tc.draw(text().maxSize(60).includeCharacters(", \t"), "written");

    CasPropertyList list = new CasPropertyList();
    list.setValues(written);

    for (String entry : list) {
      assertEquals(entry.trim(), entry, "entry '" + entry + "' is not trimmed");
      assertTrue(entry.indexOf(',') < 0, "entry '" + entry + "' still contains a comma");
    }
  }

  /**
   * Setting values adds to the list rather than replacing it, so a bean file
   * that supplies two sets of values keeps both, in the order given.
   */
  @HegelTest
  void settingValuesTwiceKeepsBothInOrder(TestCase tc) {
    List<String> first = tc.draw(lists(value()).minSize(1).maxSize(4), "first");
    List<String> second = tc.draw(lists(value()).minSize(1).maxSize(4), "second");

    CasPropertyList list = new CasPropertyList();
    list.setValues(String.join(",", first));
    list.setValues(String.join(",", second));

    List<String> expected = new ArrayList<>(first);
    expected.addAll(second);
    assertEquals(expected, new ArrayList<>(list));
  }

  /** A value list of nothing but separators contributes no entries. */
  @HegelTest
  void separatorsAloneContributeNothing(TestCase tc) {
    int commas = tc.draw(integers().min(0).max(6), "commas");

    CasPropertyList list = new CasPropertyList();
    list.setValues(",".repeat(commas));

    assertTrue(list.isEmpty(), "separators alone produced entries: " + list);
  }
}
