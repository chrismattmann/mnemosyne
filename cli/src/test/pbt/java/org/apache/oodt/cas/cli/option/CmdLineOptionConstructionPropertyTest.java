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

package org.apache.oodt.cas.cli.option;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.apache.oodt.cas.cli.option.validator.NoRestrictionsCmdLineOptionValidator;

/**
 * Every {@link CmdLineOption} subclass in this module offers two ways to build
 * one: a no-argument constructor, which Spring uses when options are declared in
 * a bean file, and a four-argument convenience constructor for callers building
 * options in code.
 *
 * <p>The two constructors have to leave the option in the same usable state.
 * They are the only two entry points, so if one of them skips a field there is
 * no later opportunity to notice - the option looks fine until the first call
 * that touches the field it forgot.
 *
 * <p>Names and descriptions are drawn only so that the properties are stated
 * over more than one option; nothing here depends on their content.
 */
class CmdLineOptionConstructionPropertyTest {

  private static Generator<String> names(String prefix) {
    return integers().min(0).max(4).map(i -> prefix + i);
  }

  /**
   * Control: an option built the Spring way, through the no-argument
   * constructor and setters, is fully usable. Everything the two properties
   * below ask for is satisfied on this path.
   */
  @HegelTest
  void theNoArgumentConstructorProducesAUsableOption(TestCase tc) {
    String shortName = tc.draw(names("s"), "short");
    String longName = tc.draw(names("long"), "long");

    AdvancedCmdLineOption advanced = new AdvancedCmdLineOption();
    advanced.setShortOption(shortName);
    advanced.setLongOption(longName);
    assertNotNull(advanced.getValidators(), "validators were never created");
    advanced.addValidator(new NoRestrictionsCmdLineOptionValidator());
    assertEquals(1, advanced.getValidators().size());

    GroupCmdLineOption group = new GroupCmdLineOption();
    group.setShortOption(shortName);
    group.setLongOption(longName);
    assertNotNull(group.getSubOptions(), "subOptions were never created");
    assertFalse(group.hasSubOptions(), "a fresh group already claims sub-options");
    group.addSubOption(new GroupSubOption(advanced, false));
    assertTrue(group.hasSubOptions());
  }

  /**
   * An {@link AdvancedCmdLineOption} exists to carry validators, so however it
   * was built it has to have a validator list to carry them in. A caller that
   * adds a validator to a freshly built option is doing the one thing the class
   * is for.
   */
  @HegelTest
  void anAdvancedOptionCanAlwaysTakeAValidator(TestCase tc) {
    String shortName = tc.draw(names("s"), "short");
    String longName = tc.draw(names("long"), "long");
    boolean hasArgs = tc.draw(booleans(), "hasArgs");

    AdvancedCmdLineOption option =
        new AdvancedCmdLineOption(shortName, longName, "description", hasArgs);

    assertNotNull(option.getValidators(), "validators were never created");
    option.addValidator(new NoRestrictionsCmdLineOptionValidator());
    assertEquals(1, option.getValidators().size());
  }

  /**
   * A {@link GroupCmdLineOption} exists to carry sub-options. However it was
   * built, asking it whether it has any must give an answer, and adding one must
   * work - {@code StdCmdLineConstructor} calls both on every group it meets.
   */
  @HegelTest
  void aGroupOptionCanAlwaysTakeASubOption(TestCase tc) {
    String shortName = tc.draw(names("s"), "short");
    String longName = tc.draw(names("long"), "long");

    GroupCmdLineOption group =
        new GroupCmdLineOption(shortName, longName, "description", false);

    assertNotNull(group.getSubOptions(), "subOptions were never created");
    assertFalse(group.hasSubOptions(), "a fresh group already claims sub-options");
    assertFalse(group.isAllowAnySubOptions(), "a fresh group already allows any sub-option");

    group.addSubOption(
        new GroupSubOption(new SimpleCmdLineOption("x", "child", "child", false), false));
    assertTrue(group.hasSubOptions());
    assertEquals(1, group.getSubOptions().size());
  }
}
