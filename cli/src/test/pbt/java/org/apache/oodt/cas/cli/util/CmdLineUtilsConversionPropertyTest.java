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

package org.apache.oodt.cas.cli.util;

import static dev.hegel.Generators.doubles;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Properties of {@link CmdLineUtils#convertToType(List, Class)}, the step that
 * turns the raw strings a user typed into the values an action is handed.
 *
 * <p>Only the pure branches are covered here: {@code String}, {@code Integer},
 * {@code Long}, {@code Double} and {@code Boolean}. The {@code Class}, {@code
 * URL} and default branches call {@code Class.forName} and {@code
 * newInstance}, so they depend on the classpath rather than on their
 * arguments and are not properties of this method.
 *
 * <p>The contract a caller depends on is a round trip: whatever the user
 * typed for a typed option is what the action should receive back.
 */
class CmdLineUtilsConversionPropertyTest {

   /** Every spelling of "true" and a few things that are not it. */
   private static Generator<String> booleanish() {
      return sampledFrom(
            "true", "TRUE", "True", "tRuE", " true ", "true\t", "false", "FALSE", "yes", "", "1");
   }

   private static List<String> asStrings(List<?> values) {
      List<String> strings = new ArrayList<String>(values.size());
      for (Object value : values) {
         strings.add(String.valueOf(value));
      }
      return strings;
   }

   /**
    * An integer option gets back the integers the user typed, in order, one
    * per argument.
    */
   @HegelTest
   void integerValuesRoundTrip(TestCase tc) throws Exception {
      List<Integer> values = tc.draw(lists(integers()).maxSize(8), "values");

      assertEquals(values, CmdLineUtils.convertToType(asStrings(values), Integer.class));
      assertEquals(values, CmdLineUtils.convertToType(asStrings(values), Integer.TYPE));
   }

   /** The same round trip for a long option. */
   @HegelTest
   void longValuesRoundTrip(TestCase tc) throws Exception {
      List<Long> values = tc.draw(lists(longs()).maxSize(8), "values");

      assertEquals(values, CmdLineUtils.convertToType(asStrings(values), Long.class));
      assertEquals(values, CmdLineUtils.convertToType(asStrings(values), Long.TYPE));
   }

   /** The same round trip for a double option. */
   @HegelTest
   void doubleValuesRoundTrip(TestCase tc) throws Exception {
      List<Double> values = tc.draw(lists(doubles()).maxSize(8), "values");

      assertEquals(values, CmdLineUtils.convertToType(asStrings(values), Double.class));
      assertEquals(values, CmdLineUtils.convertToType(asStrings(values), Double.TYPE));
   }

   /**
    * A boolean option is true exactly when the argument spells "true", in any
    * case, with surrounding whitespace allowed. Anything else is false; the
    * method deliberately does not reject unrecognised words.
    */
   @HegelTest
   void booleanValuesFollowTheSpellingOfTrue(TestCase tc) throws Exception {
      List<String> values = tc.draw(lists(booleanish()).maxSize(8), "values");

      List<Boolean> expected = new ArrayList<Boolean>(values.size());
      for (String value : values) {
         expected.add("true".equalsIgnoreCase(value.trim()));
      }

      assertEquals(expected, CmdLineUtils.convertToType(values, Boolean.class));
      assertEquals(expected, CmdLineUtils.convertToType(values, Boolean.TYPE));
   }

   /**
    * The branch lowercases with the default locale. A command line means the
    * same thing whatever locale the JVM happens to have been started in, so
    * the answer must not move when the default changes - Turkish and
    * Lithuanian being the two that reshape the Latin letter I.
    */
   @HegelTest
   void booleanConversionIsLocaleIndependent(TestCase tc) throws Exception {
      List<String> values = tc.draw(lists(booleanish()).minSize(1).maxSize(6), "values");

      Locale original = Locale.getDefault();
      try {
         Locale.setDefault(Locale.ROOT);
         List<?> inRoot = CmdLineUtils.convertToType(values, Boolean.class);

         for (String tag : Arrays.asList("tr-TR", "lt-LT", "az-AZ")) {
            Locale.setDefault(Locale.forLanguageTag(tag));
            assertEquals(
                  inRoot,
                  CmdLineUtils.convertToType(values, Boolean.class),
                  "boolean conversion changed under locale " + tag);
         }
      } finally {
         Locale.setDefault(original);
      }
   }

   /**
    * A string option given exactly one argument gets that argument back. The
    * branch exists to join several arguments into one string, but the
    * one-argument case is the common one and there is nothing there to join.
    *
    * <p>Padding is drawn explicitly because that is where the interesting
    * inputs are: a shell hands {@code --name " Bob "} to the parser as a
    * single argument whose spaces the user quoted on purpose.
    */
   @HegelTest
   void aSingleStringValueSurvivesConversion(TestCase tc) throws Exception {
      String core = tc.draw(text().maxSize(8).categories("Lu", "Ll", "Nd"), "core");
      int leading = tc.draw(integers().min(0).max(3), "leadingSpaces");
      int trailing = tc.draw(integers().min(0).max(3), "trailingSpaces");
      String value = " ".repeat(leading) + core + " ".repeat(trailing);

      assertEquals(
            Arrays.asList(value),
            CmdLineUtils.convertToType(Arrays.asList(value), String.class));
   }

   /**
    * Conversion is per argument for the typed branches: one value out for
    * every value in, never a merged or dropped one.
    */
   @HegelTest
   void typedConversionIsOneValuePerArgument(TestCase tc) throws Exception {
      List<Integer> values = tc.draw(lists(integers()).maxSize(8), "values");
      List<String> strings = asStrings(values);

      assertEquals(values.size(), CmdLineUtils.convertToType(strings, Integer.class).size());
      assertEquals(values.size(), CmdLineUtils.convertToType(strings, Long.class).size());
      assertEquals(values.size(), CmdLineUtils.convertToType(strings, Double.class).size());
      assertEquals(values.size(), CmdLineUtils.convertToType(strings, Boolean.class).size());
   }
}
