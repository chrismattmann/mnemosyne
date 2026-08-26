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

package org.apache.oodt.cas.cli.construct;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.oodt.cas.cli.exception.CmdLineConstructionException;
import org.apache.oodt.cas.cli.option.CmdLineOption;
import org.apache.oodt.cas.cli.option.CmdLineOptionInstance;
import org.apache.oodt.cas.cli.option.GroupCmdLineOption;
import org.apache.oodt.cas.cli.option.GroupSubOption;
import org.apache.oodt.cas.cli.option.SimpleCmdLineOption;
import org.apache.oodt.cas.cli.util.CmdLineIterable;
import org.apache.oodt.cas.cli.util.CmdLineUtils;
import org.apache.oodt.cas.cli.util.ParsedArg;

/**
 * Properties of {@link StdCmdLineConstructor#construct}, the step that turns the
 * flat list of tokens a parser produced into the set of options the user
 * actually specified, with each token attached to the option it belongs to.
 *
 * <p>This is where a command line stops being text and starts being intent, so
 * the obligations are concrete: every option the user named must come out
 * exactly once, carrying exactly the values that followed it, and a name that
 * was never declared must be refused rather than quietly dropped.
 *
 * <p>The result is a {@link HashSet}, so the properties assert on membership and
 * never on order. Declared options are given distinct short and long names,
 * which is the only way a command line can be unambiguous in the first place.
 */
class StdCmdLineConstructorPropertyTest {

  private static final int DECLARED_OPTIONS = 5;

  private static String longNameOf(int index) {
    return "option" + index;
  }

  private static SimpleCmdLineOption declaredOption(int index, boolean hasArgs) {
    return new SimpleCmdLineOption("s" + index, longNameOf(index), "description", hasArgs);
  }

  private static Generator<Set<Integer>> specifiedIndices() {
    return sets(integers().min(0).max(DECLARED_OPTIONS - 1)).maxSize(DECLARED_OPTIONS);
  }

  private static Generator<List<String>> argumentValues() {
    return lists(integers().min(0).max(5).map(i -> "value" + i)).minSize(1).maxSize(3);
  }

  private static void appendOption(List<ParsedArg> args, String name, List<String> values) {
    args.add(new ParsedArg(name, ParsedArg.Type.OPTION));
    for (String value : values) {
      args.add(new ParsedArg(value, ParsedArg.Type.VALUE));
    }
  }

  /**
   * Every option the user named comes back exactly once, carrying the values
   * that followed it on the command line. An option that is dropped runs with a
   * default the user did not ask for; a value attached to the wrong option runs
   * the right action on the wrong input.
   */
  @HegelTest
  void everySpecifiedOptionComesBackWithItsOwnValues(TestCase tc) throws Exception {
    Set<Integer> specified = tc.draw(specifiedIndices(), "specified");

    Set<CmdLineOption> declared = new HashSet<CmdLineOption>();
    List<ParsedArg> args = new ArrayList<ParsedArg>();
    List<List<String>> expectedValues = new ArrayList<List<String>>();
    List<Integer> order = new ArrayList<Integer>(new TreeSet<Integer>(specified));

    for (int index = 0; index < DECLARED_OPTIONS; index++) {
      boolean hasArgs = tc.draw(booleans(), "hasArgs." + index);
      declared.add(declaredOption(index, hasArgs));
    }
    for (Integer index : order) {
      CmdLineOption option = CmdLineUtils.getOptionByName(longNameOf(index), declared);
      List<String> values =
          option.hasArgs() ? tc.draw(argumentValues(), "values." + index) : new ArrayList<String>();
      expectedValues.add(values);
      appendOption(args, longNameOf(index), values);
    }

    Set<CmdLineOptionInstance> constructed =
        new StdCmdLineConstructor().construct(new CmdLineIterable<ParsedArg>(args), declared);

    assertEquals(order.size(), constructed.size(), "wrong number of options came back");
    for (int i = 0; i < order.size(); i++) {
      String name = longNameOf(order.get(i));
      CmdLineOptionInstance instance = CmdLineUtils.getOptionInstanceByName(name, constructed);
      assertNotNull(instance, "option '" + name + "' was specified but did not come back");
      assertEquals(expectedValues.get(i), instance.getValues(), "wrong values for '" + name + "'");
    }
  }

  /**
   * The short name and the long name of an option name the same option. A user
   * who types the abbreviation must get the same command as one who types it
   * out.
   */
  @HegelTest
  void shortAndLongNamesConstructTheSameOption(TestCase tc) throws Exception {
    int index = tc.draw(integers().min(0).max(DECLARED_OPTIONS - 1), "index");
    boolean hasArgs = tc.draw(booleans(), "hasArgs");
    List<String> values =
        hasArgs ? tc.draw(argumentValues(), "values") : new ArrayList<String>();

    SimpleCmdLineOption option = declaredOption(index, hasArgs);
    Set<CmdLineOption> declared = new HashSet<CmdLineOption>();
    declared.add(option);

    List<ParsedArg> viaLong = new ArrayList<ParsedArg>();
    appendOption(viaLong, option.getLongOption(), values);
    List<ParsedArg> viaShort = new ArrayList<ParsedArg>();
    appendOption(viaShort, option.getShortOption(), values);

    StdCmdLineConstructor constructor = new StdCmdLineConstructor();
    assertEquals(
        constructor.construct(new CmdLineIterable<ParsedArg>(viaLong), declared),
        constructor.construct(new CmdLineIterable<ParsedArg>(viaShort), declared));
  }

  /**
   * A name that was never declared is refused. Silently ignoring it would run
   * the command without the behaviour the user was asking for and without
   * telling them.
   */
  @HegelTest
  void anUndeclaredNameIsRefused(TestCase tc) {
    int index = tc.draw(integers().min(0).max(DECLARED_OPTIONS - 1), "index");

    Set<CmdLineOption> declared = new HashSet<CmdLineOption>();
    declared.add(declaredOption(index, false));

    List<ParsedArg> args = new ArrayList<ParsedArg>();
    appendOption(args, "not-a-declared-option", new ArrayList<String>());

    assertThrows(
        CmdLineConstructionException.class,
        () ->
            new StdCmdLineConstructor()
                .construct(new CmdLineIterable<ParsedArg>(args), declared));
  }

  /**
   * An option declared to take arguments and given none is refused, and an
   * option declared to take none and given some is refused as well. Both are the
   * user contradicting the option's own declaration, and running anyway would
   * mean guessing which of the two they meant.
   */
  @HegelTest
  void argumentCountsMustMatchTheDeclaration(TestCase tc) {
    boolean hasArgs = tc.draw(booleans(), "hasArgs");
    List<String> values =
        hasArgs ? new ArrayList<String>() : tc.draw(argumentValues(), "values");

    SimpleCmdLineOption option = declaredOption(0, hasArgs);
    Set<CmdLineOption> declared = new HashSet<CmdLineOption>();
    declared.add(option);

    List<ParsedArg> args = new ArrayList<ParsedArg>();
    appendOption(args, option.getLongOption(), values);

    assertThrows(
        CmdLineConstructionException.class,
        () ->
            new StdCmdLineConstructor()
                .construct(new CmdLineIterable<ParsedArg>(args), declared));
  }

  /**
   * A group option collects the sub-options that follow it. The group comes back
   * as one instance and the sub-options are recorded underneath it rather than
   * alongside it, which is what makes {@code -group -a -b} mean something
   * different from three separate options.
   */
  @HegelTest
  void subOptionsAreRecordedUnderTheirGroup(TestCase tc) throws Exception {
    Set<Integer> chosen =
        tc.draw(sets(integers().min(0).max(2)).minSize(1).maxSize(3), "chosenSubOptions");

    GroupCmdLineOption group = new GroupCmdLineOption();
    group.setShortOption("g");
    group.setLongOption("group");
    group.setDescription("description");

    List<SimpleCmdLineOption> children = new ArrayList<SimpleCmdLineOption>();
    for (int index = 0; index < 3; index++) {
      SimpleCmdLineOption child =
          new SimpleCmdLineOption("c" + index, "child" + index, "description", false);
      child.setIsSubOption(true);
      children.add(child);
      group.addSubOption(new GroupSubOption(child, false));
    }

    Set<CmdLineOption> declared = new HashSet<CmdLineOption>();
    declared.add(group);

    List<ParsedArg> args = new ArrayList<ParsedArg>();
    appendOption(args, group.getLongOption(), new ArrayList<String>());
    for (Integer index : new TreeSet<Integer>(chosen)) {
      appendOption(args, children.get(index).getLongOption(), new ArrayList<String>());
    }

    Set<CmdLineOptionInstance> constructed =
        new StdCmdLineConstructor().construct(new CmdLineIterable<ParsedArg>(args), declared);

    assertEquals(1, constructed.size(), "the group did not come back as a single option");
    CmdLineOptionInstance instance = constructed.iterator().next();
    assertTrue(instance.isGroup(), "the option that came back is not the group");
    assertEquals(chosen.size(), instance.getSubOptions().size(), "wrong number of sub-options");
    for (Integer index : chosen) {
      assertNotNull(
          CmdLineUtils.getOptionInstanceByName(
              children.get(index).getLongOption(), instance.getSubOptions()),
          "sub-option 'child" + index + "' was specified but did not come back");
    }
  }
}
