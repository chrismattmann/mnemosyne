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

package org.apache.oodt.cas.cli.parser;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.apache.oodt.cas.cli.util.ParsedArg;

/**
 * Properties of {@link StdCmdLineParser}, the first pass over {@code argv}
 * that decides which arguments name an option and which are values belonging
 * to the option before them.
 *
 * <p>Everything downstream is built on this classification, and it is made
 * without reference to the set of valid options: the parser looks only at the
 * text of each argument. So the properties worth stating are about that text.
 * An argument the user meant as a value has to come out as a value, carrying
 * exactly the characters that were typed, and an argument the user meant as
 * an option has to come out with its leading dashes removed and nothing else
 * changed.
 */
class StdCmdLineParserPropertyTest {

   /** Letters and digits: never empty, never starts with a dash. */
   private static Generator<String> name() {
      return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
   }

   private static StdCmdLineParser parser() {
      return new StdCmdLineParser();
   }

   /** Every argument produces exactly one parsed argument, in order. */
   @HegelTest
   void parsePreservesArity(TestCase tc) {
      List<String> args = tc.draw(lists(name()).maxSize(8), "args");

      List<ParsedArg> parsed = parser().parse(args.toArray(new String[0]));

      assertEquals(args.size(), parsed.size());
   }

   /**
    * An argument with no leading dash is a value, and it survives verbatim -
    * a file name, a message, a number are handed on exactly as typed.
    */
   @HegelTest
   void plainArgumentsSurviveAsValues(TestCase tc) {
      List<String> args = tc.draw(lists(name()).minSize(1).maxSize(8), "args");

      List<ParsedArg> parsed = parser().parse(args.toArray(new String[0]));

      for (int i = 0; i < args.size(); i++) {
         assertEquals(ParsedArg.Type.VALUE, parsed.get(i).getType(), "arg " + i);
         assertEquals(args.get(i), parsed.get(i).getName(), "arg " + i);
      }
   }

   /**
    * Both spellings of an option name mean the same option: {@code --name}
    * and {@code -name} are recognised as options and both yield {@code name}.
    */
   @HegelTest
   void optionNamesRoundTripThroughBothSpellings(TestCase tc) {
      String name = tc.draw(name(), "name");
      String dashes = tc.draw(sampledFrom("-", "--"), "dashes");

      List<ParsedArg> parsed = parser().parse(new String[] {dashes + name});

      assertEquals(1, parsed.size());
      assertEquals(ParsedArg.Type.OPTION, parsed.get(0).getType());
      assertEquals(name, parsed.get(0).getName());
   }

   /**
    * The two halves of the classification agree: anything
    * {@link StdCmdLineParser#isOption(String)} calls an option has a name
    * {@link StdCmdLineParser#getOptionName(String)} can produce. A {@code
    * null} here would reach {@code StdCmdLineConstructor} as an option with
    * no name.
    */
   @HegelTest
   void everyOptionHasAName(TestCase tc) {
      String arg =
            tc.draw(
                  text().maxSize(8).categories("Lu", "Ll", "Nd").includeCharacters("-"), "arg");

      if (StdCmdLineParser.isOption(arg)) {
         assertNotNull(StdCmdLineParser.getOptionName(arg), "option '" + arg + "' has no name");
      }
   }

   /**
    * A negative number is a value, not an option. The typed branches of
    * {@code CmdLineUtils.convertToType} accept {@code Integer}, {@code Long}
    * and {@code Double}, so an option whose value is negative is a supported
    * thing to want, and the shell offers no way to hide the minus sign -
    * quoting {@code "-5"} still delivers {@code -5} in {@code argv}.
    */
   @HegelTest
   void negativeNumbersAreValues(TestCase tc) {
      int number = tc.draw(integers().min(-1000).max(-1), "number");
      String arg = String.valueOf(number);

      List<ParsedArg> parsed = parser().parse(new String[] {arg});

      assertEquals(1, parsed.size());
      assertEquals(
            ParsedArg.Type.VALUE,
            parsed.get(0).getType(),
            "'" + arg + "' was read as the option '" + parsed.get(0).getName() + "'");
      assertEquals(arg, parsed.get(0).getName());
   }
}
