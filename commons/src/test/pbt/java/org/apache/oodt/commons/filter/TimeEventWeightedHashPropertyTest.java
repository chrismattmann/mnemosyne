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

package org.apache.oodt.commons.filter;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.tuples;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import dev.hegel.Tuple2;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Properties of {@link TimeEventWeightedHash}, which picks a schedule out of a
 * pile of possibly-overlapping time events.
 *
 * <p>The class documents what it returns: "the path from root node to a leaf
 * node which fills the most time". Three things follow from that sentence and
 * are checked here — the answer is a schedule that could actually be run, it is
 * built out of the events it was given, and it is at least as good as the
 * obvious one-event schedule.
 *
 * <p>Building the hash walks a graph with nested unbounded loops, so the build
 * runs under a preemptive timeout: a hang is a failure, not a test that never
 * finishes.
 */
class TimeEventWeightedHashPropertyTest {

  private static final Duration BUDGET = Duration.ofSeconds(5);

  /**
   * A handful of events on a short timeline. The window is kept small on
   * purpose so that overlaps, abutting events and exact duplicates all turn up
   * rather than being lost in a sparse timeline.
   */
  private static List<TimeEvent> drawEvents(TestCase tc) {
    List<Tuple2<Integer, Integer>> raw =
        tc.draw(
            lists(tuples(integers().min(0).max(30), integers().min(0).max(10)))
                .minSize(1)
                .maxSize(8),
            "events");

    List<TimeEvent> events = new ArrayList<>(raw.size());
    for (Tuple2<Integer, Integer> pair : raw) {
      long start = pair.value1();
      events.add(new TimeEvent(start, start + pair.value2()));
    }
    return events;
  }

  /** Build the hash and read off its answer, under a time budget. */
  private static List<? extends TimeEvent> greatestWeightedPath(List<TimeEvent> events) {
    List<List<? extends TimeEvent>> answer = new ArrayList<>(1);
    assertTimeoutPreemptively(
        BUDGET,
        () ->
            answer.add(
                TimeEventWeightedHash.buildHash(events).getGreatestWeightedPathAsOrderedList()));
    return answer.get(0);
  }

  /**
   * The chosen path is a schedule that could be run: each event starts no
   * earlier than the previous one ends. With an epsilon of zero — no overlap
   * allowed — a returned path whose events collide is a plan the caller cannot
   * execute.
   */
  @HegelTest
  void greatestWeightedPathIsANonOverlappingSchedule(TestCase tc) {
    List<TimeEvent> events = drawEvents(tc);

    List<? extends TimeEvent> path = greatestWeightedPath(events);
    tc.note("path = " + path);

    for (int i = 1; i < path.size(); i++) {
      TimeEvent previous = path.get(i - 1);
      TimeEvent next = path.get(i);
      assertTrue(
          next.getStartTime() >= previous.getEndTime(),
          "path event " + next + " overlaps the one before it, " + previous);
    }
  }

  /**
   * Every event on the chosen path is one of the events handed in. The hash
   * seeds itself with an internal sentinel event, and a path that leaks it — or
   * anything else the caller never supplied — is describing work that does not
   * exist.
   */
  @HegelTest
  void greatestWeightedPathUsesOnlyTheEventsItWasGiven(TestCase tc) {
    List<TimeEvent> events = drawEvents(tc);

    List<? extends TimeEvent> path = greatestWeightedPath(events);
    tc.note("path = " + path);

    for (TimeEvent event : path) {
      assertTrue(events.contains(event), "path contains an event that was never added: " + event);
    }
  }

  /**
   * The chosen path fills at least as much time as the single longest event.
   * Any one event is itself on some path from the root down to a leaf, so the
   * path that "fills the most time" cannot be worse than taking that event
   * alone. This is the weakest possible reading of the optimality the class
   * advertises.
   */
  @HegelTest
  void greatestWeightedPathIsNoWorseThanASingleEvent(TestCase tc) {
    List<TimeEvent> events = drawEvents(tc);

    List<? extends TimeEvent> path = greatestWeightedPath(events);
    tc.note("path = " + path);

    long best = 0;
    for (TimeEvent event : events) {
      best = Math.max(best, event.getDuration());
    }

    long filled = 0;
    for (TimeEvent event : path) {
      filled += event.getDuration();
    }

    assertTrue(filled >= best, "path fills " + filled + " but a single event fills " + best);
  }
}
