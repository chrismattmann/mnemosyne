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

package org.apache.oodt.cas.cli.printer;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sets;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.cli.action.CmdLineAction;
import org.apache.oodt.cas.cli.option.AdvancedCmdLineOption;
import org.apache.oodt.cas.cli.option.CmdLineOption;
import org.apache.oodt.cas.cli.option.SimpleCmdLineOption;
import org.apache.oodt.cas.cli.option.validator.CmdLineOptionValidator.Result;
import org.apache.oodt.cas.cli.option.validator.CmdLineOptionValidator.Result.Grade;

/**
 * Properties of the help text {@link StdCmdLinePrinter} produces. Help text is
 * the only documentation a command line tool has, so the obligations are the
 * ones a reader depends on: everything they were told about is mentioned,
 * nothing they were told about is invented, and asking twice gives the same
 * answer.
 *
 * <p>Nothing here asserts on layout. Column widths are a presentation choice
 * that a reader can live with being wrong; an option missing from the help is
 * an option they will never discover.
 */
class StdCmdLinePrinterPropertyTest {

  /** A concrete action; only its name and description reach the printer. */
  private static CmdLineAction action(String name) {
    return new CmdLineAction(name, "does " + name) {
      @Override
      public void execute(ActionMessagePrinter printer) {}
    };
  }

  private static Generator<Set<Integer>> indices() {
    return sets(integers().min(0).max(6)).maxSize(7);
  }

  private static SimpleCmdLineOption option(int index, boolean required) {
    SimpleCmdLineOption option =
        new SimpleCmdLineOption("s" + index, "option" + index, "description " + index, index % 2 == 0);
    option.setRequired(required);
    return option;
  }

  /**
   * Every option handed to the printer is named in the help. Both names are
   * checked, because a user who only ever sees the long form cannot type the
   * short one.
   */
  @HegelTest
  void optionsHelpNamesEveryOption(TestCase tc) {
    Set<Integer> chosen = tc.draw(indices(), "options");

    Set<CmdLineOption> options = new HashSet<CmdLineOption>();
    for (Integer index : chosen) {
      options.add(option(index, tc.draw(booleans(), "required." + index)));
    }

    String help = new StdCmdLinePrinter().printOptionsHelp(options);
    tc.note("help = " + help);

    for (CmdLineOption option : options) {
      assertTrue(
          help.contains(option.getLongOption()),
          "help never mentions --" + option.getLongOption());
      assertTrue(
          help.contains(option.getShortOption()),
          "help never mentions -" + option.getShortOption());
    }
  }

  /** Every action handed to the printer is named, with its description. */
  @HegelTest
  void actionsHelpNamesEveryAction(TestCase tc) {
    Set<Integer> chosen = tc.draw(indices(), "actions");

    Set<CmdLineAction> actions = new HashSet<CmdLineAction>();
    for (Integer index : chosen) {
      actions.add(action("action" + index));
    }

    String help = new StdCmdLinePrinter().printActionsHelp(actions);
    tc.note("help = " + help);

    for (CmdLineAction candidate : actions) {
      assertTrue(help.contains(candidate.getName()), "help never mentions " + candidate.getName());
      assertTrue(
          help.contains(candidate.getDescription()),
          "help never describes " + candidate.getName());
    }
  }

  /** Asking for the same help twice gives the same text. */
  @HegelTest
  void helpTextIsDeterministic(TestCase tc) {
    Set<Integer> chosen = tc.draw(indices(), "options");

    Set<CmdLineOption> options = new HashSet<CmdLineOption>();
    for (Integer index : chosen) {
      options.add(option(index, tc.draw(booleans(), "required." + index)));
    }

    StdCmdLinePrinter printer = new StdCmdLinePrinter();
    assertEquals(printer.printOptionsHelp(options), printer.printOptionsHelp(options));
  }

  /**
   * Action help is printed for whatever options the tool declares. An
   * {@link AdvancedCmdLineOption} is the module's option type for validation, and
   * carrying a handler is optional - {@code AdvancedCmdLineOption.hasHandler()}
   * exists precisely because an option may not have one. Asking for help must
   * not depend on whether one was configured.
   */
  @HegelTest
  void actionHelpIsPrintableForAnyDeclaredOption(TestCase tc) {
    boolean required = tc.draw(booleans(), "required");
    boolean hasArgs = tc.draw(booleans(), "hasArgs");
    String name = tc.draw(integers().min(0).max(4).map(i -> "option" + i), "name");

    AdvancedCmdLineOption option = new AdvancedCmdLineOption();
    option.setShortOption("s");
    option.setLongOption(name);
    option.setDescription("description");
    option.setHasArgs(hasArgs);
    option.setRequired(required);

    Set<CmdLineOption> options = new HashSet<CmdLineOption>();
    options.add(option);

    String help = new StdCmdLinePrinter().printActionHelp(action("do-something"), options);
    tc.note("help = " + help);

    assertTrue(help.contains(name), "action help never mentions --" + name);
  }

  /** Printed action messages are the messages, in order, and nothing else. */
  @HegelTest
  void actionMessagesArePrintedInOrder(TestCase tc) {
    List<String> messages =
        tc.draw(lists(text().minSize(0).maxSize(6).categories("Lu", "Ll", "Nd")).maxSize(6),
            "messages");

    StringBuilder expected = new StringBuilder();
    for (String message : messages) {
      expected.append(message);
    }

    assertEquals(expected.toString(), new StdCmdLinePrinter().printActionMessages(messages));
  }

  /** Every validation failure the user caused is reported back to them. */
  @HegelTest
  void everyValidationFailureIsReported(TestCase tc) {
    List<String> reasons =
        tc.draw(
            lists(integers().min(0).max(5).map(i -> "reason" + i)).maxSize(5), "reasons");

    List<Result> results = new ArrayList<Result>();
    for (String reason : reasons) {
      results.add(new Result(Grade.FAIL, reason));
    }

    String printed = new StdCmdLinePrinter().printOptionValidationErrors(results);
    tc.note("printed = " + printed);

    for (String reason : reasons) {
      assertTrue(printed.contains(reason), "failure '" + reason + "' was not reported");
    }
  }

  /** Every missing required option is named, so the user knows what to add. */
  @HegelTest
  void everyMissingRequiredOptionIsNamed(TestCase tc) {
    Set<Integer> chosen = tc.draw(indices(), "missing");

    Set<CmdLineOption> missing = new HashSet<CmdLineOption>();
    for (Integer index : chosen) {
      missing.add(option(index, true));
    }

    String printed = new StdCmdLinePrinter().printRequiredOptionsMissingError(missing);
    tc.note("printed = " + printed);

    for (CmdLineOption option : missing) {
      assertTrue(
          printed.contains(option.getLongOption()),
          "missing option --" + option.getLongOption() + " was not named");
    }
  }
}
