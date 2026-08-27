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

package org.apache.oodt.cas.cli.option.validator;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.oodt.cas.cli.option.CmdLineOptionInstance;
import org.apache.oodt.cas.cli.option.SimpleCmdLineOption;
import org.apache.oodt.cas.cli.option.validator.CmdLineOptionValidator.Result.Grade;

/**
 * Properties of the argument validators. A validator is the last thing standing
 * between a user's typo and the action that runs on it, so its verdict has to be
 * a pure function of the arguments it was given and the list it was configured
 * with - nothing about which option it happens to be attached to, and nothing
 * about how many times it has been called before.
 *
 * <p>Arguments and allowed values are drawn from the same small pool so that
 * matches and near-misses both occur often.
 */
class CmdLineOptionValidatorPropertyTest {

  private static Generator<List<String>> words() {
    return lists(integers().min(0).max(3).map(i -> "v" + i)).maxSize(5);
  }

  private static CmdLineOptionInstance instanceWith(List<String> values) {
    CmdLineOptionInstance instance = new CmdLineOptionInstance();
    instance.setOption(new SimpleCmdLineOption("s", "some-option", "description", true));
    instance.setValues(values);
    return instance;
  }

  private static AllowedArgsCmdLineOptionValidator allowing(List<String> allowed) {
    AllowedArgsCmdLineOptionValidator validator = new AllowedArgsCmdLineOptionValidator();
    validator.setAllowedArgs(allowed);
    return validator;
  }

  /**
   * The whole specification of {@link AllowedArgsCmdLineOptionValidator}: it
   * passes exactly when every value the user gave is on the allowed list.
   */
  @HegelTest
  void allowedArgsPassesExactlyWhenEveryValueIsAllowed(TestCase tc) {
    List<String> allowed = tc.draw(words(), "allowed");
    List<String> values = tc.draw(words(), "values");

    CmdLineOptionValidator.Result result = allowing(allowed).validate(instanceWith(values));

    Grade expected = allowed.containsAll(values) ? Grade.PASS : Grade.FAIL;
    assertEquals(expected, result.getGrade(), "allowed=" + allowed + " values=" + values);
    assertNotNull(result.getMessage(), "a verdict with no message");
  }

  /**
   * The verdict does not drift. A validator is held on an option for the life of
   * the process and consulted once per specified instance; if repeated calls
   * could disagree, the same command line would be accepted or rejected
   * depending on option ordering.
   */
  @HegelTest
  void aVerdictDependsOnlyOnTheArguments(TestCase tc) {
    List<String> allowed = tc.draw(words(), "allowed");
    List<String> values = tc.draw(words(), "values");

    AllowedArgsCmdLineOptionValidator validator = allowing(allowed);
    Grade first = validator.validate(instanceWith(values)).getGrade();
    Grade second = validator.validate(instanceWith(values)).getGrade();
    Grade fresh = allowing(new ArrayList<String>(allowed)).validate(instanceWith(values)).getGrade();

    assertEquals(first, second, "the same validator changed its mind");
    assertEquals(first, fresh, "an identically configured validator disagreed");
  }

  /**
   * {@link ArgRegExpCmdLineOptionValidator} is the same validator with the
   * allowed values read as patterns. Given patterns that match one literal each,
   * the two must reach the same verdict - otherwise "allowed values" means two
   * different things depending on which subclass an option was configured with.
   */
  @HegelTest
  void literalPatternsBehaveLikePlainAllowedValues(TestCase tc) {
    List<String> allowed = tc.draw(words(), "allowed");
    List<String> values = tc.draw(words(), "values");

    List<String> quoted = new ArrayList<String>();
    for (String value : allowed) {
      quoted.add(Pattern.quote(value));
    }
    ArgRegExpCmdLineOptionValidator regExpValidator = new ArgRegExpCmdLineOptionValidator();
    regExpValidator.setAllowedArgs(quoted);

    assertEquals(
        allowing(allowed).validate(instanceWith(values)).getGrade(),
        regExpValidator.validate(instanceWith(values)).getGrade(),
        "allowed=" + allowed + " values=" + values);
  }

  /** The validator that restricts nothing restricts nothing. */
  @HegelTest
  void noRestrictionsAlwaysPasses(TestCase tc) {
    List<String> values = tc.draw(words(), "values");

    assertSame(
        Grade.PASS,
        new NoRestrictionsCmdLineOptionValidator().validate(instanceWith(values)).getGrade());
  }

  /**
   * An option can declare static arguments, which stand in when the user gives
   * none. Validation happens on whatever the option will actually be run with,
   * so an instance with no values must be judged on its static arguments.
   */
  @HegelTest
  void staticArgsAreWhatGetsValidated(TestCase tc) {
    List<String> allowed = tc.draw(words(), "allowed");
    List<String> staticArgs = tc.draw(words(), "staticArgs");

    SimpleCmdLineOption option =
        new SimpleCmdLineOption("s", "some-option", "description", true);
    option.setStaticArgs(staticArgs);
    CmdLineOptionInstance unspecified = new CmdLineOptionInstance();
    unspecified.setOption(option);

    assertEquals(
        allowing(allowed).validate(instanceWith(staticArgs)).getGrade(),
        allowing(allowed).validate(unspecified).getGrade(),
        "allowed=" + allowed + " staticArgs=" + staticArgs);
  }
}
