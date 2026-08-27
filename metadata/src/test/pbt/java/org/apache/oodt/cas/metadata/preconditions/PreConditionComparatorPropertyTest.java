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

package org.apache.oodt.cas.metadata.preconditions;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.oodt.cas.metadata.exceptions.PreconditionComparatorException;

/**
 * Properties of the precondition comparators, which decide whether a metadata
 * extractor runs against a given product at all.
 *
 * <p>A precondition that answers wrongly does not fail loudly — it silently
 * skips extraction, so a product is catalogued with no metadata, or an
 * extractor runs against a file it cannot read. That makes the truth table in
 * {@code PreConditionComparator.passes} and the matching logic in the two
 * path-only comparators worth stating exactly.
 *
 * <p>Only the comparators that reach no further than the product's <em>name</em>
 * or <em>path</em> are covered: {@code EndsWithComparator} and
 * {@code RegExExcludeComparator}. The comparators that stat, open or hash the
 * file are deliberately left alone.
 *
 * <p>None of these classes had unit tests.
 */
class PreConditionComparatorPropertyTest {

  private static final List<String> OPERATORS =
      List.of("EQUAL_TO", "NOT_EQUAL_TO", "GREATER_THAN", "LESS_THAN");

  /**
   * A comparator whose check answers with a number the test chose, so that the
   * operator logic can be exercised without touching a file.
   */
  private static final class FixedComparator extends PreConditionComparator<String> {
    private final int result;

    FixedComparator(int result) {
      this.result = result;
    }

    @Override
    protected int performCheck(File product, String compareItem)
        throws PreconditionComparatorException {
      return this.result;
    }
  }

  private static Generator<String> operators() {
    return sampledFrom(OPERATORS);
  }

  /** Bare file names, no directory separators. */
  private static Generator<String> fileNames() {
    return fromRegex("[a-z]{1,6}(\\.[a-z]{1,4}){0,2}");
  }

  private static Generator<String> extensions() {
    return fromRegex("[a-z]{1,4}");
  }

  /**
   * Exactly one of the three ordering operators accepts any given comparison
   * result. A configuration that names one operator must therefore get a
   * definite yes or no, and swapping the operator must flip the answer.
   */
  @HegelTest
  void exactlyOneOrderingOperatorAccepts(TestCase tc) {
    int result = tc.draw(integers().min(-1000).max(1000), "result");

    int accepted = 0;
    for (String operator : List.of("EQUAL_TO", "GREATER_THAN", "LESS_THAN")) {
      if (passes(result, operator)) {
        accepted++;
      }
    }

    assertEquals(1, accepted, "comparison result " + result + " matched " + accepted + " operators");
  }

  /**
   * {@code NOT_EQUAL_TO} is the exact negation of {@code EQUAL_TO}. Callers
   * write preconditions in both polarities and expect them to partition the
   * products between them.
   */
  @HegelTest
  void notEqualIsTheNegationOfEqual(TestCase tc) {
    int result = tc.draw(integers().min(-1000).max(1000), "result");

    assertEquals(
        passes(result, "EQUAL_TO"),
        !passes(result, "NOT_EQUAL_TO"),
        "EQUAL_TO and NOT_EQUAL_TO agreed on comparison result " + result);
  }

  /**
   * The operator is read case-insensitively, as its own implementation intends
   * by upper-casing it. Spring configuration files are hand-written, so
   * {@code equal_to} has to mean what {@code EQUAL_TO} means.
   */
  @HegelTest
  void theOperatorIsCaseInsensitive(TestCase tc) {
    int result = tc.draw(integers().min(-1000).max(1000), "result");
    String operator = tc.draw(operators(), "operator");

    assertEquals(
        passes(result, operator),
        passes(result, operator.toLowerCase()),
        "the operator [" + operator + "] was read differently in lower case");
  }

  /** An operator nobody defined accepts nothing, rather than accepting everything. */
  @HegelTest
  void anUnknownOperatorAcceptsNothing(TestCase tc) {
    int result = tc.draw(integers().min(-1000).max(1000), "result");
    String operator = tc.draw(fromRegex("[A-Z]{3,10}"), "operator");
    tc.assume(!OPERATORS.contains(operator));

    assertFalse(passes(result, operator), "the unknown operator [" + operator + "] accepted");
  }

  /**
   * An extension check matches a file when, and only when, the file actually
   * carries that extension. This comparator's whole job, per its own Javadoc,
   * is to "check a file's extension and then skip extracting metadata from
   * files that don't match this extension" — so a file with no extension at all
   * must not be treated as carrying one.
   */
  @HegelTest
  void anExtensionCheckMatchesOnlyRealExtensions(TestCase tc)
      throws PreconditionComparatorException {
    String stem = tc.draw(fromRegex("[a-z]{1,6}"), "stem");
    String extension = tc.draw(extensions(), "extension");
    String shape =
        tc.draw(
            sampledFrom(
                "stem.ext", // the file the precondition is looking for
                "stem.other", // a different extension
                "stem", // no extension at all
                "ext", // no extension, but named like one
                "stem.", // a trailing dot and nothing after it
                "stem.ext.gz"), // the extension is not the last one
            "shape");
    String name =
        switch (shape) {
          case "stem.ext" -> stem + "." + extension;
          case "stem.other" -> stem + ".zzq";
          case "stem" -> stem;
          case "ext" -> extension;
          case "stem." -> stem + ".";
          default -> stem + "." + extension + ".gz";
        };

    int result = new EndsWithComparator().performCheck(new File(name), extension);

    assertEquals(
        name.endsWith("." + extension),
        result == 0,
        "[" + name + "] against extension [" + extension + "]");
  }

  /** The extension check orders names the way string comparison does, consistently. */
  @HegelTest
  void anExtensionCheckIsAConsistentComparison(TestCase tc)
      throws PreconditionComparatorException {
    String name = tc.draw(fileNames(), "name");
    String extension = tc.draw(extensions(), "extension");

    EndsWithComparator comparator = new EndsWithComparator();
    int once = comparator.performCheck(new File(name), extension);
    int twice = comparator.performCheck(new File(name), extension);

    assertEquals(once, twice, "the same check answered differently twice");
  }

  /**
   * The exclusion regex means what the caller wrote, matched without regard to
   * case. The implementation lower-cases the pattern as well as the path, which
   * is only safe if lower-casing cannot change a regex's meaning — so this
   * property is stated against the case-insensitive matcher the caller
   * evidently wanted.
   */
  @HegelTest
  void exclusionMatchesTheCallersPatternIgnoringCase(TestCase tc)
      throws PreconditionComparatorException {
    String pattern =
        tc.draw(
            sampledFrom(
                ".*\\.tmp",
                ".*/[A-Z]+/.*",
                ".*\\d+.*",
                ".*\\D+.*",
                ".*\\w+.*",
                ".*[^0-9]+.*",
                ".*BACKUP.*"),
            "pattern");
    String name = tc.draw(fromRegex("[a-zA-Z0-9]{1,6}(\\.[a-z]{1,4})?"), "name");

    File product = new File(name);
    String path = product.getAbsolutePath();
    boolean expected =
        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(path).matches();

    int result = new RegExExcludeComparator().performCheck(product, pattern);

    assertEquals(
        expected,
        result == 0,
        "pattern [" + pattern + "] against [" + path + "]");
  }

  /** A blank exclusion pattern excludes nothing — an unset config must not skip everything. */
  @HegelTest
  void aBlankExclusionPatternExcludesNothing(TestCase tc)
      throws PreconditionComparatorException {
    String name = tc.draw(fileNames(), "name");
    String blank = tc.draw(sampledFrom("", " ", "   ", "\t"), "blank");

    int result = new RegExExcludeComparator().performCheck(new File(name), blank);

    assertTrue(result != 0, "a blank pattern excluded [" + name + "]");
  }

  /** Evaluate {@code passes} for a given comparison result and operator. */
  private static boolean passes(int result, String operator) {
    FixedComparator comparator = new FixedComparator(result);
    comparator.setType(operator);
    comparator.setCompareItem("ignored");
    return comparator.passes(new File("product.dat"));
  }
}
