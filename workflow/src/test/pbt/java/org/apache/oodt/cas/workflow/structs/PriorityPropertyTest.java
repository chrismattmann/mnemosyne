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

package org.apache.oodt.cas.workflow.structs;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Properties of {@link Priority}, the ordering the engine runs work in.
 *
 * <p>A {@link Priority} is handed to {@link java.util.Collections#sort} by
 * every {@link PrioritySorter} in this package, so its {@code compareTo} has
 * to be a total order: a comparator that contradicts itself makes the sort
 * throw rather than merely order things oddly. It is also used as a value —
 * compared with {@code equals} and stored in hash-based collections — so the
 * usual equals/hashCode agreement matters as much as the ordering does.
 *
 * <p>Priorities here are built through {@link Priority#getPriority(double)},
 * which is the only way a caller makes one: the five named priorities are
 * constants, and anything else arrives as a number off the wire or out of a
 * workflow file.
 */
class PriorityPropertyTest {

  /** The priorities the class names, in the order it declares them. */
  private static final List<Priority> NAMED = List.of(Priority.LOW,
      Priority.MEDIUM_LOW, Priority.MEDIUM, Priority.MEDIUM_HIGH,
      Priority.HIGH);

  /**
   * Priority values, in tenths, over a range that spans the named values and
   * reaches either side of them. Tenths rather than arbitrary doubles because
   * a priority is a number someone wrote down, and because negative zero and
   * NaN are not values any caller of this class produces.
   */
  private static double value(int tenths) {
    return tenths / 10.0;
  }

  private static Priority draw(TestCase tc, String label) {
    return Priority.getPriority(value(tc.draw(integers().min(-50).max(150),
        label)));
  }

  /**
   * Handing a value in and reading it back out gives the same value. The
   * workflow manager writes a priority over the wire as a bare double and
   * rebuilds it with this method at the other end, so anything lost here is
   * lost on every request.
   */
  @HegelTest
  void aPriorityRemembersTheValueItWasBuiltFrom(TestCase tc) {
    int tenths = tc.draw(integers().min(-50).max(150), "tenths");

    Priority priority = Priority.getPriority(value(tenths));

    assertEquals(0, Double.compare(value(tenths), priority.getValue()),
        "getPriority(" + value(tenths) + ") reported " + priority.getValue());
  }

  /**
   * A value the class has a name for comes back as that named priority, and
   * any other value comes back named CUSTOM. The name is what a workflow file
   * and the monitor UI show, so a priority of 5.0 must not be displayed as
   * CUSTOM alongside a MEDIUM that means the same thing.
   */
  @HegelTest
  void aNamedValueComesBackUnderItsName(TestCase tc) {
    int tenths = tc.draw(integers().min(-50).max(150), "tenths");
    double value = value(tenths);

    Priority priority = Priority.getPriority(value);

    Priority named = null;
    for (Priority candidate : NAMED) {
      if (Double.compare(candidate.getValue(), value) == 0) {
        named = candidate;
      }
    }
    if (named != null) {
      assertSame(named, priority, value + " should be " + named.getName());
    } else {
      assertEquals("CUSTOM", priority.getName(),
          value + " is not a declared priority but was named "
              + priority.getName());
    }
  }

  /**
   * The named priorities increase strictly from LOW to HIGH. Their order is
   * the whole point of naming them, and {@link HighestPrioritySorter} runs
   * work in exactly this order, reversed.
   */
  @HegelTest
  void theNamedPrioritiesIncreaseFromLowToHigh(TestCase tc) {
    int i = tc.draw(integers().min(0).max(NAMED.size() - 2), "i");

    Priority lower = NAMED.get(i);
    Priority higher = NAMED.get(i + 1);

    assertTrue(lower.compareTo(higher) < 0,
        lower.getName() + " did not sort below " + higher.getName());
    assertFalse(lower.equals(higher),
        lower.getName() + " compared equal to " + higher.getName());
  }

  /**
   * Comparing two priorities answers the same thing both ways round, with the
   * sign flipped, and calls them equal exactly when {@code equals} does.
   * {@link java.util.Collections#sort} refuses to sort a list whose comparator
   * breaks this, and a sort that throws stops the engine picking any work at
   * all.
   */
  @HegelTest
  void comparingIsSymmetricAndAgreesWithEquals(TestCase tc) {
    Priority left = draw(tc, "left");
    Priority right = draw(tc, "right");

    assertEquals(Integer.signum(left.compareTo(right)),
        -Integer.signum(right.compareTo(left)),
        left + " and " + right + " compare inconsistently");
    assertEquals(left.compareTo(right) == 0, left.equals(right),
        left + " and " + right + " disagree between compareTo and equals");
  }

  /**
   * Ordering is transitive. Sorting depends on it, and so does any caller
   * asking whether one queued instance outranks another.
   */
  @HegelTest
  void orderingIsTransitive(TestCase tc) {
    Priority first = draw(tc, "first");
    Priority second = draw(tc, "second");
    Priority third = draw(tc, "third");

    if (first.compareTo(second) <= 0 && second.compareTo(third) <= 0) {
      assertTrue(first.compareTo(third) <= 0,
          first + " <= " + second + " <= " + third + " but not " + first
              + " <= " + third);
    }
  }

  /**
   * Two priorities that are equal hash alike. A priority is a plain value and
   * ends up in maps and sets keyed by workflow configuration.
   */
  @HegelTest
  void equalPrioritiesHashAlike(TestCase tc) {
    Priority left = draw(tc, "left");
    Priority right = draw(tc, "right");

    if (left.equals(right)) {
      assertEquals(left.hashCode(), right.hashCode(),
          left + " equals " + right + " but hashes differently");
    }
    assertTrue(left.equals(left), "a priority did not equal itself");
    assertFalse(left.equals(left.toString()),
        "a priority equalled something that is not a priority");
  }

  /**
   * Sorting a list of priorities is a permutation of it that comes out in
   * non-decreasing order. This is the sort the queue depends on, stated
   * directly.
   */
  @HegelTest
  void sortingOrdersWithoutLosingAnything(TestCase tc) {
    int count = tc.draw(integers().min(0).max(8), "count");
    List<Priority> priorities = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      priorities.add(draw(tc, "priority" + i));
    }
    List<Priority> sorted = new ArrayList<>(priorities);

    Collections.sort(sorted);

    assertEquals(priorities.size(), sorted.size(),
        "sorting changed how many priorities there are");
    List<Priority> remaining = new ArrayList<>(priorities);
    for (Priority each : sorted) {
      assertTrue(remaining.remove(each), each + " appeared out of nowhere");
    }
    for (int i = 1; i < sorted.size(); i++) {
      assertTrue(sorted.get(i - 1).compareTo(sorted.get(i)) <= 0,
          "sorted list is not ordered: " + sorted);
    }
  }

  /**
   * The default priority is one of the named ones, and every instance starts
   * with it. {@link WorkflowInstance} leans on this: an instance nobody gave a
   * priority still has to be comparable with one that was given a priority.
   */
  @HegelTest
  void theDefaultIsANamedPriority(TestCase tc) {
    Priority drawn = draw(tc, "drawn");

    Priority fallback = Priority.getDefault();

    assertNotNull(fallback, "there is no default priority");
    assertTrue(NAMED.contains(fallback),
        "the default priority is not one of the named ones: " + fallback);
    assertEquals(0, Integer.signum(fallback.compareTo(fallback)),
        "the default priority does not compare equal to itself");
    assertEquals(fallback.getValue() < drawn.getValue(),
        fallback.compareTo(drawn) < 0,
        "the default sorts inconsistently with its own value against "
            + drawn);
  }

  /**
   * A priority prints its name and its value. The monitor writes this string
   * straight into a page, and a priority that printed only a number would be
   * unreadable next to one that printed only a name.
   */
  @HegelTest
  void printingShowsBothTheNameAndTheValue(TestCase tc) {
    Priority priority = draw(tc, "priority");

    String printed = priority.toString();

    assertTrue(printed.contains(priority.getName()),
        printed + " does not name the priority");
    assertTrue(printed.contains(Double.toString(priority.getValue())),
        printed + " does not show the value " + priority.getValue());
  }

  /**
   * A priority built from a named priority's own value is interchangeable with
   * it. The wire carries the value alone, so the priority that arrives has to
   * behave as the one that was sent.
   */
  @HegelTest
  void aRebuiltNamedPriorityIsInterchangeableWithIt(TestCase tc) {
    Priority named = tc.draw(sampledFrom(NAMED), "named");

    Priority rebuilt = Priority.getPriority(named.getValue());

    assertEquals(named, rebuilt, named.getName() + " did not survive a rebuild");
    assertEquals(named.hashCode(), rebuilt.hashCode(),
        named.getName() + " hashes differently once rebuilt");
    assertEquals(named.getName(), rebuilt.getName(),
        named.getName() + " came back named " + rebuilt.getName());
  }
}
