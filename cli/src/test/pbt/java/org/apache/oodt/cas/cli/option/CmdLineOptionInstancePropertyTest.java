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

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Properties of {@link CmdLineOptionInstance}, the record of one option as the
 * user actually specified it on the command line, together with the argument
 * values they gave it.
 *
 * <p>Two things a caller relies on: the values it reads back are the values that
 * were put in - including the fallback to the option's static arguments when the
 * user gave none - and the instance behaves as a value when it is put into the
 * {@link java.util.HashSet} that {@code StdCmdLineConstructor} returns.
 */
class CmdLineOptionInstancePropertyTest {

  private static Generator<List<String>> values() {
    return lists(text().minSize(1).maxSize(6).categories("Lu", "Ll", "Nd")).maxSize(4);
  }

  private static SimpleCmdLineOption simpleOption() {
    return new SimpleCmdLineOption("s", "some-option", "description", true);
  }

  private static GroupCmdLineOption groupOption() {
    GroupCmdLineOption group = new GroupCmdLineOption();
    group.setShortOption("g");
    group.setLongOption("group-option");
    group.setDescription("description");
    return group;
  }

  /**
   * The values a caller reads back are the values it set. Static arguments stand
   * in only when the user supplied nothing, which is the documented behaviour of
   * a default-valued option.
   */
  @HegelTest
  void valuesFallBackToStaticArgsOnlyWhenNoneWereGiven(TestCase tc) {
    List<String> given = tc.draw(values(), "given");
    List<String> staticArgs = tc.draw(values(), "staticArgs");

    SimpleCmdLineOption option = simpleOption();
    option.setStaticArgs(staticArgs);

    CmdLineOptionInstance instance = new CmdLineOptionInstance();
    instance.setOption(option);
    instance.setValues(given);

    assertEquals(given.isEmpty() ? staticArgs : given, instance.getValues());
  }

  /** Values added one at a time come back in the order they were added. */
  @HegelTest
  void addedValuesComeBackInOrder(TestCase tc) {
    List<String> given = tc.draw(values(), "given");

    CmdLineOptionInstance instance = new CmdLineOptionInstance();
    instance.setOption(simpleOption());
    for (String value : given) {
      instance.addValue(value);
    }

    assertEquals(given, instance.getValues());
  }

  /**
   * An instance holds its own copy of the values. The list a caller passes in
   * usually belongs to the parser and is reused; if the instance aliased it, a
   * later parse would rewrite an option the user already specified.
   *
   * <p>{@link CmdLineOptionInstance#setValues(List)} copies. The constructor
   * takes the same argument for the same purpose and must do the same.
   */
  @HegelTest
  void anInstanceKeepsItsOwnCopyOfTheValues(TestCase tc) {
    List<String> given = tc.draw(values().filter(vs -> !vs.isEmpty()), "given");

    List<String> mutable = new ArrayList<String>(given);
    CmdLineOptionInstance viaSetter = new CmdLineOptionInstance();
    viaSetter.setOption(simpleOption());
    viaSetter.setValues(mutable);
    CmdLineOptionInstance viaConstructor = new CmdLineOptionInstance(simpleOption(), mutable);

    mutable.clear();

    assertEquals(given, viaSetter.getValues(), "setter aliased the caller's list");
    assertEquals(given, viaConstructor.getValues(), "constructor aliased the caller's list");
  }

  /**
   * Equal instances hash the same. {@code StdCmdLineConstructor} collects the
   * options a user specified into a {@link java.util.HashSet}; that set can only
   * do its job if two instances that compare equal also land in the same bucket.
   *
   * <p>The two instances below are the same group option with the same values
   * and differ only in the sub-options recorded under them.
   */
  @HegelTest
  void equalInstancesShareAHashCode(TestCase tc) {
    List<String> subValues = tc.draw(values(), "subValues");

    GroupCmdLineOption group = groupOption();
    SimpleCmdLineOption child = new SimpleCmdLineOption("c", "child", "child", true);
    group.addSubOption(new GroupSubOption(child, false));

    CmdLineOptionInstance withoutSub = new CmdLineOptionInstance();
    withoutSub.setOption(group);

    CmdLineOptionInstance withSub = new CmdLineOptionInstance();
    withSub.setOption(group);
    CmdLineOptionInstance childInstance = new CmdLineOptionInstance();
    childInstance.setOption(child);
    childInstance.setValues(subValues);
    withSub.addSubOption(childInstance);

    tc.assume(withoutSub.equals(withSub));
    assertEquals(
        withoutSub.hashCode(),
        withSub.hashCode(),
        "equal instances hash differently: " + withoutSub + " / " + withSub);
  }
}
