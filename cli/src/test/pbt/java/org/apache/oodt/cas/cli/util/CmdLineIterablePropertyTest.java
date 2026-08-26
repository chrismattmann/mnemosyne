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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Properties of the cursor in {@link CmdLineIterable}.
 *
 * <p>This class is a hand-rolled cursor over a list of arguments, and its
 * whole job is to let a parser walk forwards, look at where it is, and step
 * back when it has read one argument too many. That is a small enough job to
 * write down as a model: a single {@code int} clamped to the range
 * {@code [-1, numArgs]}, where {@code -1} is the "not started yet" state the
 * constructor puts the object in. The command-sequence property below drives
 * a real iterable and the model through the same random script of calls and
 * checks that they never disagree.
 *
 * <p>The step-back is not incidental. {@code StdCmdLineConstructor.getValues}
 * reads arguments until it runs off the end of a run of values and then calls
 * {@code descrementIndex()} exactly once to put the last one back, so
 * decrementing has to undo incrementing wherever incrementing had an effect.
 */
class CmdLineIterablePropertyTest {

   /** The calls a caller can make that either move or observe the cursor. */
   private enum Command {
      INCREMENT,
      DECREMENT,
      HAS_NEXT
   }

   /** Short lowercase words, standing in for command line arguments. */
   private static Generator<String> arg() {
      return text().minSize(1).maxSize(3).categories("Ll");
   }

   private static Generator<List<String>> argLists() {
      return lists(arg()).maxSize(6);
   }

   /**
    * The cursor behaves like an {@code int} clamped to {@code [-1, numArgs]}.
    *
    * <p>Incrementing moves forward until it reaches the end and then stops;
    * decrementing moves back until it reaches the initial state and then
    * stops; {@code hasNext()} reports whether there is another argument after
    * the current one. Any script of those calls must leave the real cursor
    * where the model says it is.
    */
   @HegelTest
   void cursorFollowsABoundedCounter(TestCase tc) {
      List<String> args = tc.draw(argLists(), "args");
      List<Command> script =
            tc.draw(lists(sampledFrom(Command.values())).minSize(1).maxSize(12), "script");

      CmdLineIterable<String> iterable = new CmdLineIterable<String>(args);
      int model = -1;

      for (Command command : script) {
         switch (command) {
            case INCREMENT:
               iterable.incrementIndex();
               model = Math.min(model + 1, args.size());
               break;
            case DECREMENT:
               iterable.descrementIndex();
               model = Math.max(model - 1, -1);
               break;
            case HAS_NEXT:
               assertEquals(
                     model + 1 < args.size(),
                     iterable.hasNext(),
                     "hasNext() disagrees with the model at index " + model);
               break;
            default:
               throw new AssertionError(command);
         }
         assertEquals(model, iterable.getCurrentIndex(), "cursor diverged after " + command);
      }
   }

   /**
    * Reading the current argument is a read. A caller that asks what the
    * cursor is pointing at must not thereby consume an argument, otherwise
    * {@code hasNext()} answers differently either side of the question and a
    * peek silently eats input.
    */
   @HegelTest
   void readingCurrentArgDoesNotAdvanceTheCursor(TestCase tc) {
      List<String> args = tc.draw(lists(arg()).minSize(1).maxSize(6), "args");
      int steps = tc.draw(integers().min(0).max(args.size()), "steps");

      CmdLineIterable<String> iterable = new CmdLineIterable<String>(args);
      for (int i = 0; i < steps; i++) {
         iterable.incrementIndex();
      }

      int indexBefore = iterable.getCurrentIndex();
      boolean hasNextBefore = iterable.hasNext();
      iterable.getCurrentArg();

      assertEquals(indexBefore, iterable.getCurrentIndex(), "getCurrentArg() moved the cursor");
      assertEquals(hasNextBefore, iterable.hasNext(), "getCurrentArg() consumed an argument");
   }

   /**
    * {@code getArgsLeft()} reports the arguments that have not been walked
    * past yet. On a freshly constructed iterable that is all of them, and at
    * no reachable cursor position may a caller asking what is left be handed
    * an exception instead of a list.
    */
   @HegelTest
   void argsLeftIsAlwaysTheUnreadSuffix(TestCase tc) {
      List<String> args = tc.draw(argLists(), "args");
      int steps = tc.draw(integers().min(0).max(args.size()), "steps");

      CmdLineIterable<String> iterable = new CmdLineIterable<String>(args);
      for (int i = 0; i < steps; i++) {
         iterable.incrementIndex();
      }

      int from = Math.max(iterable.getCurrentIndex(), 0);
      assertEquals(args.subList(from, args.size()), iterable.getArgsLeft());
   }

   /**
    * A for-each over a fresh iterable hands back every argument, in order,
    * once each, and then stops.
    */
   @HegelTest
   void iteratorVisitsEveryArgumentInOrder(TestCase tc) {
      List<String> args = tc.draw(argLists(), "args");

      List<String> visited =
            assertTimeoutPreemptively(
                  Duration.ofSeconds(5),
                  () -> {
                     List<String> seen = new ArrayList<String>();
                     for (String arg : new CmdLineIterable<String>(args)) {
                        seen.add(arg);
                     }
                     return seen;
                  });

      assertEquals(args, visited);
   }

   /**
    * The traversal the parser actually performs: call {@code incrementAndGet}
    * until it hands back {@code null}. That must yield every argument in
    * order and then terminate, however many extra times it is called.
    */
   @HegelTest
   void incrementAndGetYieldsEveryArgumentThenNull(TestCase tc) {
      List<String> args = tc.draw(argLists(), "args");

      CmdLineIterable<String> iterable = new CmdLineIterable<String>(args);
      List<String> drained =
            assertTimeoutPreemptively(
                  Duration.ofSeconds(5),
                  () -> {
                     List<String> seen = new ArrayList<String>();
                     String next = iterable.incrementAndGet();
                     while (next != null) {
                        seen.add(next);
                        next = iterable.incrementAndGet();
                     }
                     return seen;
                  });

      assertEquals(args, drained);
      assertNull(iterable.incrementAndGet(), "an exhausted iterable started producing again");
   }
}
