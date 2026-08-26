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
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.sets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.cli.action.CmdLineAction;
import org.apache.oodt.cas.cli.option.CmdLineOption;
import org.apache.oodt.cas.cli.option.GroupCmdLineOption;
import org.apache.oodt.cas.cli.option.GroupSubOption;
import org.apache.oodt.cas.cli.option.SimpleCmdLineOption;
import org.apache.oodt.cas.cli.option.require.ActionDependencyRule;
import org.apache.oodt.cas.cli.option.require.RequirementRule;
import org.apache.oodt.cas.cli.option.require.RequirementRule.Relation;

/**
 * Properties of the requirement arithmetic and option lookup in
 * {@link CmdLineUtils}. These decide two user-visible things: which options a
 * command refuses to run without, and which option a name on the command line
 * refers to.
 *
 * <p>Requirement is stated over options carrying at most one
 * {@link ActionDependencyRule}, which is the shape every declaration in the
 * module uses - an option says how it relates to one action. Two rules naming
 * the same action with different relations is a contradictory declaration, and
 * no sensible answer exists for it.
 */
class CmdLineUtilsRequirementPropertyTest {

  private static final String[] ACTION_NAMES = {"ingest", "query", "delete"};

  private static CmdLineAction action(String name) {
    return new CmdLineAction(name, "does " + name) {
      @Override
      public void execute(ActionMessagePrinter printer) {}
    };
  }

  private static SimpleCmdLineOption option(int index) {
    return new SimpleCmdLineOption("s" + index, "option" + index, "description", false);
  }

  /**
   * An option carrying a rule and marked always-required, or a rule alone, or
   * neither.
   */
  private static SimpleCmdLineOption drawOption(TestCase tc, int index) {
    SimpleCmdLineOption option = option(index);
    option.setRequired(tc.draw(booleans(), "alwaysRequired." + index));
    if (tc.draw(booleans(), "hasRule." + index)) {
      List<RequirementRule> rules = new ArrayList<RequirementRule>();
      rules.add(
          new ActionDependencyRule(
              tc.draw(sampledFrom(ACTION_NAMES), "ruleAction." + index),
              tc.draw(sampledFrom(Relation.values()), "ruleRelation." + index)));
      option.setRequirementRules(rules);
    }
    return option;
  }

  /**
   * No option is both required and optional for the same action. Help text
   * prints the two under separate headings and the missing-options check reads
   * only the first, so an option in both lists is presented to the user as
   * something they may omit and then rejected for omitting it.
   */
  @HegelTest
  void anOptionIsNeverBothRequiredAndOptional(TestCase tc) {
    CmdLineAction specified = action(tc.draw(sampledFrom(ACTION_NAMES), "action"));
    SimpleCmdLineOption option = drawOption(tc, 0);

    boolean required = CmdLineUtils.isRequired(specified, option);
    boolean optional = CmdLineUtils.isOptional(specified, option);

    assertFalse(
        required && optional,
        "option " + option + " is both required and optional for " + specified.getName());
  }

  /**
   * Being optional only because of the specified action is a special case of
   * being optional. {@code determineRelevantSubOptions} branches on the stricter
   * test, so if the two could disagree in that direction a sub-option would be
   * shown under a group it is not actually available for.
   */
  @HegelTest
  void strictlyOptionalImpliesOptional(TestCase tc) {
    CmdLineAction specified = action(tc.draw(sampledFrom(ACTION_NAMES), "action"));
    SimpleCmdLineOption option = drawOption(tc, 0);

    if (CmdLineUtils.isStrictlyOptional(specified, option)) {
      assertTrue(
          CmdLineUtils.isOptional(specified, option),
          "option " + option + " is strictly optional but not optional");
    }
  }

  /**
   * The two bulk queries are exactly the per-option tests applied to each
   * option: nothing is added, nothing is dropped, and the two results do not
   * overlap.
   */
  @HegelTest
  void requiredAndOptionalSetsAgreeWithThePerOptionTests(TestCase tc) {
    CmdLineAction specified = action(tc.draw(sampledFrom(ACTION_NAMES), "action"));
    Set<Integer> chosen = tc.draw(sets(integers().min(0).max(5)).maxSize(6), "options");

    Set<CmdLineOption> options = new HashSet<CmdLineOption>();
    for (Integer index : chosen) {
      options.add(drawOption(tc, index));
    }

    Set<CmdLineOption> required = CmdLineUtils.determineRequired(specified, options);
    Set<CmdLineOption> optional = CmdLineUtils.determineOptional(specified, options);

    assertTrue(options.containsAll(required), "required options came from nowhere");
    assertTrue(options.containsAll(optional), "optional options came from nowhere");
    for (CmdLineOption option : options) {
      assertEquals(
          CmdLineUtils.isRequired(specified, option),
          required.contains(option),
          "required set disagrees about " + option);
      assertEquals(
          CmdLineUtils.isOptional(specified, option),
          optional.contains(option),
          "optional set disagrees about " + option);
    }
  }

  /**
   * Every declared option is reachable by either of its names, and the option
   * found is the one that carries that name. This is the lookup the constructor
   * uses for every token on the command line.
   */
  @HegelTest
  void everyDeclaredOptionIsFoundByEitherOfItsNames(TestCase tc) {
    Set<Integer> chosen = tc.draw(sets(integers().min(0).max(5)).minSize(1).maxSize(6), "options");

    Set<CmdLineOption> options = new HashSet<CmdLineOption>();
    for (Integer index : chosen) {
      options.add(option(index));
    }

    for (CmdLineOption option : options) {
      assertSame(
          option,
          CmdLineUtils.getOptionByName(option.getLongOption(), options),
          "--" + option.getLongOption() + " found the wrong option");
      assertSame(
          option,
          CmdLineUtils.getOptionByName(option.getShortOption(), options),
          "-" + option.getShortOption() + " found the wrong option");
    }
    assertNull(
        CmdLineUtils.getOptionByName("not-a-declared-option", options),
        "an undeclared name matched an option");
  }

  /**
   * A group's sub-options are reachable by name through the group, and the group
   * recognises them as its own. Both are needed for {@code -group -sub} to be
   * understood as one command rather than two.
   */
  @HegelTest
  void aGroupOwnsAndExposesItsSubOptions(TestCase tc) {
    Set<Integer> chosen = tc.draw(sets(integers().min(0).max(3)).minSize(1).maxSize(4), "subOptions");

    GroupCmdLineOption group = new GroupCmdLineOption();
    group.setShortOption("g");
    group.setLongOption("group");
    group.setDescription("description");

    List<CmdLineOption> children = new ArrayList<CmdLineOption>();
    for (Integer index : chosen) {
      SimpleCmdLineOption child =
          new SimpleCmdLineOption("c" + index, "child" + index, "description", false);
      child.setIsSubOption(true);
      children.add(child);
      group.addSubOption(new GroupSubOption(child, tc.draw(booleans(), "childRequired." + index)));
    }

    Set<CmdLineOption> options = new HashSet<CmdLineOption>();
    options.add(group);

    CmdLineOption stranger = new SimpleCmdLineOption("x", "stranger", "description", false);
    assertFalse(CmdLineUtils.isSubOption(group, stranger), "the group claimed a foreign option");

    for (CmdLineOption child : children) {
      assertTrue(
          CmdLineUtils.isSubOption(group, child),
          "the group disowned its sub-option --" + child.getLongOption());
      assertNotNull(
          CmdLineUtils.getOptionByName(child.getLongOption(), options),
          "sub-option --" + child.getLongOption() + " is unreachable by name");
    }
  }
}
