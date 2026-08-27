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

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.commons.exec.EnvUtilities;

/**
 * Properties of {@link PathUtils#replaceEnvVariables(String, Metadata, boolean)},
 * the substitution pass every configured OODT path goes through.
 *
 * <p>Most of {@code PathUtils} is not pure: the dynamic-date entry points read
 * the wall clock, the default {@link java.util.TimeZone} and the default
 * {@link java.util.Locale}, and an unresolved variable falls through to the
 * process environment. These properties stay on the pure path — every bracketed
 * name they generate is either present in a supplied {@link Metadata}, or is
 * asserted absent from the environment before the call — and none of them
 * generate a {@code [DATE...]} token.
 */
class PathUtilsPropertyTest {

  /** A variable name: letters and digits only, so it can never contain a bracket. */
  private static Generator<String> names() {
    return text().minSize(1).maxSize(6).categories("Lu", "Ll", "Nd");
  }

  /** Literal path text surrounding the variables: never a bracket. */
  private static Generator<String> literals() {
    return text().maxSize(6).codepoints(0x20, 0x7E).excludeCharacters("[]");
  }

  private static Generator<String> values() {
    return text().maxSize(8).codepoints(0x20, 0x7E).excludeCharacters("[]");
  }

  private static List<String> distinct(List<String> in) {
    return new ArrayList<>(new LinkedHashSet<>(in));
  }

  /**
   * The core contract: {@code [NAME]} is replaced by the metadata value for
   * {@code NAME}, and everything outside the brackets is copied through
   * untouched.
   */
  @HegelTest
  void bracketedNamesAreReplacedByTheirMetadataValues(TestCase tc) {
    List<String> names = distinct(tc.draw(lists(names()).minSize(1).maxSize(3), "names"));
    List<String> values =
        tc.draw(lists(values()).minSize(names.size()).maxSize(names.size()), "values");
    List<String> literals =
        tc.draw(lists(literals()).minSize(names.size() + 1).maxSize(names.size() + 1), "literals");

    Metadata metadata = new Metadata();
    for (int i = 0; i < names.size(); i++) {
      metadata.addMetadata(names.get(i), values.get(i));
    }

    StringBuilder input = new StringBuilder();
    StringBuilder expected = new StringBuilder();
    for (int i = 0; i < names.size(); i++) {
      input.append(literals.get(i)).append('[').append(names.get(i)).append(']');
      expected.append(literals.get(i)).append(values.get(i));
    }
    input.append(literals.get(names.size()));
    expected.append(literals.get(names.size()));

    assertEquals(
        expected.toString(), PathUtils.replaceEnvVariables(input.toString(), metadata, false));
  }

  /**
   * A path that contains a {@code '['} with no matching {@code ']'} is
   * malformed, and rejecting it would be a defensible answer. Walking off the
   * end of the string is not: {@code IndexOutOfBoundsException} is a programming
   * error, not a way to report bad input, and the caller has no way to
   * distinguish it from a bug of their own.
   */
  @HegelTest
  void anUnclosedBracketIsNotAnIndexOutOfBounds(TestCase tc) {
    String prefix = tc.draw(literals(), "prefix");
    String name = tc.draw(names(), "name");

    Metadata metadata = new Metadata();
    metadata.addMetadata(name, "value");

    String input = prefix + "[" + name;
    try {
      tc.note("result = " + PathUtils.replaceEnvVariables(input, metadata, false));
    } catch (IndexOutOfBoundsException e) {
      fail("replaceEnvVariables walked off the end of \"" + input + "\": " + e);
    }
  }

  /**
   * When a name resolves to nothing, whatever the method produces must not be
   * the four-character word {@code null}. Splicing that into a path yields a
   * plausible-looking directory name that no one asked for, and the failure only
   * surfaces later, as a file written to the wrong place.
   */
  @HegelTest
  void anUnresolvableNameIsNotReplacedByTheLiteralNull(TestCase tc) {
    String name = tc.draw(names(), "name");
    tc.assume(EnvUtilities.getEnv(name) == null);

    // Deliberately empty: the name resolves neither from metadata nor the environment.
    String result = PathUtils.replaceEnvVariables("[" + name + "]", new Metadata(), false);

    assertNotEquals(
        "null", result, "an unresolvable variable was spliced into the path as the word \"null\"");
  }

  /**
   * {@code recursivelyReplaceEnvVariables} loops until the string stops looking
   * like it contains a variable, so it has to make progress on every pass and
   * leave nothing bracketed behind.
   *
   * <p>Names are asserted absent from the environment first: a name that
   * happened to resolve to a value containing brackets would make the loop
   * spin forever, which the timeout is here to catch rather than hang the build.
   */
  @HegelTest
  void recursiveReplacementTerminates(TestCase tc) {
    List<String> names = tc.draw(lists(names()).minSize(1).maxSize(3), "names");
    List<String> literals =
        tc.draw(lists(literals()).minSize(names.size() + 1).maxSize(names.size() + 1), "literals");
    for (String name : names) {
      tc.assume(EnvUtilities.getEnv(name) == null);
    }

    StringBuilder input = new StringBuilder();
    for (int i = 0; i < names.size(); i++) {
      input.append(literals.get(i)).append('[').append(names.get(i)).append(']');
    }
    input.append(literals.get(names.size()));
    String path = input.toString();

    String result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5), () -> PathUtils.recursivelyReplaceEnvVariables(path));

    assertFalse(
        result.contains("["),
        "recursive replacement stopped with an unreplaced variable still in the path");
  }
}
