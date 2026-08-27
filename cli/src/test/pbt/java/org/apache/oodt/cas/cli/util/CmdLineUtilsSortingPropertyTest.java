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

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.sets;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.cli.action.CmdLineAction;
import org.apache.oodt.cas.cli.option.CmdLineOption;
import org.apache.oodt.cas.cli.option.SimpleCmdLineOption;
import org.apache.oodt.cas.cli.option.require.RequirementRule;

/**
 * Properties of the two option orderings used to lay out help text:
 * {@link CmdLineUtils#sortOptionsByRequiredStatus(Set)} and
 * {@link CmdLineUtils#sortOptions(Set)}.
 *
 * <p>A sort has two obligations. It must not change the contents - the user
 * has to be shown every option and no option twice - and the order it
 * produces has to be a real order, meaning it depends only on the options
 * themselves and not on how they happened to arrive. The second obligation is
 * where a comparator that is not transitive or not antisymmetric shows up:
 * the relative order of two options starts depending on what else is in the
 * set.
 */
class CmdLineUtilsSortingPropertyTest {

   /** A rule that says nothing; only its presence in the list is scored. */
   private static final RequirementRule ANY_RULE =
         new RequirementRule() {
            public Relation getRelation(CmdLineAction action) {
               return Relation.OPTIONAL;
            }
         };

   private static CmdLineOption option(String longOption, boolean required, boolean hasRules) {
      SimpleCmdLineOption option =
            new SimpleCmdLineOption(longOption + "-short", longOption, "description", false);
      option.setRequired(required);
      if (hasRules) {
         option.setRequirementRules(new ArrayList<RequirementRule>(List.of(ANY_RULE)));
      }
      return option;
   }

   /** The score the ordering documents: always required beats has-rules beats neither. */
   private static int requirementScore(CmdLineOption option) {
      return (option.isRequired() ? 2 : 0) + (option.getRequirementRules().isEmpty() ? 0 : 1);
   }

   private static List<CmdLineOption> drawOptions(TestCase tc) {
      List<String> names =
            new ArrayList<String>(
                  tc.draw(
                        sets(text().minSize(1).maxSize(3).categories("Ll"))
                              .minSize(1)
                              .maxSize(6),
                        "names"));

      List<CmdLineOption> options = new ArrayList<CmdLineOption>(names.size());
      for (int i = 0; i < names.size(); i++) {
         options.add(
               option(
                     names.get(i),
                     tc.draw(booleans(), "required[" + i + "]"),
                     tc.draw(booleans(), "hasRules[" + i + "]")));
      }
      return options;
   }

   private static List<String> longOptionsOf(List<CmdLineOption> options) {
      List<String> names = new ArrayList<String>(options.size());
      for (CmdLineOption option : options) {
         names.add(option.getLongOption());
      }
      return names;
   }

   /** Sorting by requirement shows every option exactly once. */
   @HegelTest
   void sortByRequiredStatusIsAPermutation(TestCase tc) {
      List<CmdLineOption> options = drawOptions(tc);
      Set<CmdLineOption> asSet = new LinkedHashSet<CmdLineOption>(options);

      List<String> sorted = longOptionsOf(CmdLineUtils.sortOptionsByRequiredStatus(asSet));

      List<String> expected = longOptionsOf(options);
      Collections.sort(expected);
      List<String> actual = new ArrayList<String>(sorted);
      Collections.sort(actual);
      assertEquals(expected, actual);
   }

   /**
    * The documented order: options that are always required first, then those
    * carrying requirement rules, then the rest, and alphabetically by long
    * name inside each band.
    */
   @HegelTest
   void sortByRequiredStatusOrdersByRequirementThenName(TestCase tc) {
      List<CmdLineOption> options = drawOptions(tc);

      List<CmdLineOption> sorted =
            CmdLineUtils.sortOptionsByRequiredStatus(new LinkedHashSet<CmdLineOption>(options));

      for (int i = 1; i < sorted.size(); i++) {
         CmdLineOption previous = sorted.get(i - 1);
         CmdLineOption current = sorted.get(i);
         int previousScore = requirementScore(previous);
         int currentScore = requirementScore(current);

         assertTrue(
               previousScore >= currentScore,
               "less required option '"
                     + previous.getLongOption()
                     + "' sorted above '"
                     + current.getLongOption()
                     + "'");
         if (previousScore == currentScore) {
            assertTrue(
                  previous.getLongOption().compareTo(current.getLongOption()) < 0,
                  "equally required options out of alphabetical order: '"
                        + previous.getLongOption()
                        + "' then '"
                        + current.getLongOption()
                        + "'");
         }
      }
   }

   /**
    * The order two options end up in does not depend on which other options
    * were being sorted alongside them. A comparator that is not a total order
    * fails this even though sorting still "succeeds".
    */
   @HegelTest
   void relativeOrderDoesNotDependOnTheRestOfTheSet(TestCase tc) {
      List<CmdLineOption> options = drawOptions(tc);

      List<String> whole =
            longOptionsOf(
                  CmdLineUtils.sortOptionsByRequiredStatus(
                        new LinkedHashSet<CmdLineOption>(options)));

      for (int i = 0; i < options.size(); i++) {
         for (int j = i + 1; j < options.size(); j++) {
            CmdLineOption first = options.get(i);
            CmdLineOption second = options.get(j);
            Set<CmdLineOption> pair = new LinkedHashSet<CmdLineOption>();
            pair.add(first);
            pair.add(second);

            List<String> pairwise =
                  longOptionsOf(CmdLineUtils.sortOptionsByRequiredStatus(pair));

            boolean firstLeadsInPair =
                  pairwise.indexOf(first.getLongOption())
                        < pairwise.indexOf(second.getLongOption());
            boolean firstLeadsOverall =
                  whole.indexOf(first.getLongOption()) < whole.indexOf(second.getLongOption());

            assertEquals(
                  firstLeadsInPair,
                  firstLeadsOverall,
                  "'"
                        + first.getLongOption()
                        + "' and '"
                        + second.getLongOption()
                        + "' swap places depending on what else is sorted with them");
         }
      }
   }

   /** Sorting by name is a permutation, in ascending long-name order. */
   @HegelTest
   void sortOptionsOrdersByLongNameAndKeepsEveryOption(TestCase tc) {
      List<CmdLineOption> options = drawOptions(tc);

      List<String> sorted =
            longOptionsOf(CmdLineUtils.sortOptions(new LinkedHashSet<CmdLineOption>(options)));

      List<String> expected = longOptionsOf(options);
      Collections.sort(expected);
      assertEquals(expected, sorted);
   }

   /**
    * Two callers holding the same options in different iteration orders are
    * shown the same help.
    */
   @HegelTest
   void sortingIgnoresInsertionOrder(TestCase tc) {
      List<CmdLineOption> options = drawOptions(tc);
      List<CmdLineOption> reversed = new ArrayList<CmdLineOption>(options);
      Collections.reverse(reversed);

      assertEquals(
            longOptionsOf(
                  CmdLineUtils.sortOptionsByRequiredStatus(
                        new LinkedHashSet<CmdLineOption>(options))),
            longOptionsOf(
                  CmdLineUtils.sortOptionsByRequiredStatus(
                        new LinkedHashSet<CmdLineOption>(reversed))));
   }
}
