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

package org.apache.oodt.commons.util;

import static dev.hegel.Generators.doubles;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Properties of the pure string helpers in {@link Utility}.
 *
 * <p>Only the methods that do not touch the filesystem, spawn a thread or read
 * a classpath resource are covered here: {@code escapeSingleQuote},
 * {@code parseCommaList} and {@code isNumeric}.
 *
 * <p>{@code escapeSingleQuote} is the one that matters. Its Javadoc says it
 * "will ready the string for insertion into a database" by doubling single
 * quotes, and that it is a no-op on input a considerate developer already
 * escaped. Those two sentences are in tension, so the properties below assert
 * only what both readings agree on: the output must be safe to paste between
 * two quote delimiters, and escaping twice must not escape twice over.
 *
 * <p>The class had no unit tests.
 */
class UtilityPropertyTest {

  /**
   * Text over a tiny alphabet in which the single quote is common. Drawing from
   * the whole of Unicode would put a quote in roughly no strings at all, and the
   * quote is the only character this code looks at.
   */
  private static Generator<String> quotableText() {
    return lists(sampledFrom("'", "a", "b", " ", "\\", "\""))
        .maxSize(12)
        .map(pieces -> String.join("", pieces));
  }

  /** Undo the escaping the way a SQL parser does: a doubled quote is one quote. */
  private static String unescape(String escaped) {
    return escaped.replace("''", "'");
  }

  /**
   * Every single quote in the output sits in a run of even length. That is
   * precisely the condition for the result to be a well-formed SQL string
   * literal body — an odd run closes the literal early and turns the remainder
   * of the value into syntax, which is what a SQL injection is.
   */
  @HegelTest
  void everyQuoteInTheOutputIsPaired(TestCase tc) {
    String input = tc.draw(quotableText(), "input");

    String escaped = Utility.escapeSingleQuote(input);

    int i = 0;
    while (i < escaped.length()) {
      if (escaped.charAt(i) != '\'') {
        i++;
        continue;
      }
      int run = 0;
      while (i < escaped.length() && escaped.charAt(i) == '\'') {
        run++;
        i++;
      }
      assertEquals(0, run % 2, "odd run of " + run + " quotes in [" + escaped + "]");
    }
  }

  /**
   * Escaping an already-escaped string changes nothing. Callers chain helpers
   * and lose track of what has been escaped; the Javadoc promises this case is
   * safe, and a second doubling would corrupt every stored value.
   */
  @HegelTest
  void escapingIsIdempotent(TestCase tc) {
    String input = tc.draw(quotableText(), "input");

    String once = Utility.escapeSingleQuote(input);
    String twice = Utility.escapeSingleQuote(once);

    assertEquals(once, twice, "escaping [" + input + "] twice changed it");
  }

  /**
   * A value with no pre-existing doubled quotes survives the round trip through
   * escaping and back. This is the case the method exists for: an arbitrary
   * user-supplied value on its way into a SQL literal has to come back out of
   * the database unchanged.
   */
  @HegelTest
  void aValueWithoutDoubledQuotesSurvivesTheRoundTrip(TestCase tc) {
    String input = tc.draw(quotableText(), "input");
    tc.assume(!input.contains("''"));

    String escaped = Utility.escapeSingleQuote(input);

    assertEquals(input, unescape(escaped), "the value did not survive escaping");
  }

  /** Escaping never removes content: the characters other than quotes are untouched. */
  @HegelTest
  void escapingOnlyEverAddsQuotes(TestCase tc) {
    String input = tc.draw(quotableText(), "input");

    String escaped = Utility.escapeSingleQuote(input);

    assertEquals(
        input.replace("'", ""),
        escaped.replace("'", ""),
        "escaping altered a character that was not a quote");
    assertTrue(escaped.length() >= input.length(), "escaping shortened the value");
  }

  /**
   * The elements of a parsed comma list are exactly the distinct trimmed
   * pieces between the commas. This is how configuration lists are read all
   * over this codebase, so a dropped or untrimmed element is a mis-parsed
   * config file.
   */
  @HegelTest
  void aCommaListYieldsItsDistinctTrimmedElements(TestCase tc) {
    List<String> pieces =
        tc.draw(
            lists(sampledFrom("alpha", " beta", "gamma ", " ", "", "delta")).maxSize(8),
            "pieces");
    String list = String.join(",", pieces);

    Set<String> parsed = new HashSet<>();
    for (Iterator<?> it = Utility.parseCommaList(list); it.hasNext(); ) {
      parsed.add((String) it.next());
    }

    Set<String> expected = new HashSet<>();
    for (String piece : pieces) {
      if (!piece.isEmpty()) {
        expected.add(piece.trim());
      }
    }

    assertEquals(expected, parsed, "parsed [" + list + "] into the wrong element set");
  }

  /** Every element handed back has already been trimmed. */
  @HegelTest
  void everyParsedElementIsTrimmed(TestCase tc) {
    String list = tc.draw(text().maxSize(24).includeCharacters(", \t"), "list");

    for (Iterator<?> it = Utility.parseCommaList(list); it.hasNext(); ) {
      String element = (String) it.next();
      assertEquals(element, element.trim(), "element [" + element + "] was not trimmed");
    }
  }

  /**
   * A missing list is an empty list, not a crash. Callers pass the result of
   * {@code System.getProperty} straight in, so {@code null} is the ordinary
   * "not configured" case.
   */
  @HegelTest
  void anAbsentListIsEmpty(TestCase tc) {
    tc.draw(text().maxSize(1), "ignored");

    Iterator<?> it = Utility.parseCommaList(null);

    assertFalse(it.hasNext(), "an absent list produced elements");
  }

  /**
   * Anything Java itself prints as a number reads back as numeric. This is the
   * shape of value that arrives from a database column or a previous
   * {@code toString}, so rejecting one would drop real data.
   */
  @HegelTest
  void javaSOwnNumberFormatsAreNumeric(TestCase tc) {
    double value = tc.draw(doubles().allowNan(false).allowInfinity(false), "value");

    assertTrue(Utility.isNumeric(Double.toString(value)), Double.toString(value) + " rejected");
    assertTrue(Utility.isNumeric(Long.toString((long) value)), "an integer literal was rejected");
  }

  /** A string of letters is not a number, and asking never throws. */
  @HegelTest
  void wordsAreNotNumbers(TestCase tc) {
    String word = tc.draw(text().minSize(1).maxSize(8).categories("Lu", "Ll"), "word");
    tc.assume(!word.equalsIgnoreCase("NaN"));
    tc.assume(!word.equalsIgnoreCase("Infinity"));

    assertFalse(Utility.isNumeric(word), "[" + word + "] was accepted as a number");
  }

  /**
   * Asking whether arbitrary text is numeric answers rather than throwing. It is
   * a predicate over untrusted input — a field pulled out of a met file — so
   * every string has to have an answer, and the same answer each time.
   */
  @HegelTest
  void askingIfTextIsNumericAlwaysAnswers(TestCase tc) {
    String candidate = tc.draw(text().maxSize(16), "candidate");

    boolean numeric = Utility.isNumeric(candidate);

    assertEquals(numeric, Utility.isNumeric(candidate), "the answer was not stable");
  }
}
