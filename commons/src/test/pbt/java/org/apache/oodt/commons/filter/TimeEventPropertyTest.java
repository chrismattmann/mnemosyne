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

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.doubles;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.text;
import static dev.hegel.Generators.tuples;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import dev.hegel.Tuple3;
import java.util.ArrayList;
import java.util.List;

/**
 * Properties of {@link TimeEvent} and {@link ObjectTimeEvent}, the value types
 * that {@link TimeEventWeightedHash} sorts, de-duplicates and hashes.
 *
 * <p>These are ordinary value objects, so the contracts under test are the
 * ordinary ones: {@code equals} is symmetric, {@code equals} implies equal hash
 * codes, and {@code compareTo} is a total order. Everything downstream —
 * putting events in a {@code HashSet}, sorting a schedule, comparing two
 * queries' results — is built on exactly those three sentences.
 *
 * <p>Times are drawn from a small range so that coincident and overlapping
 * events actually occur; drawing across the whole {@code long} range would make
 * every pair distinct and test nothing.
 *
 * <p>Neither class had unit tests.
 */
class TimeEventPropertyTest {

  /** A start time, an end time at or after it, and a priority. */
  private static Generator<Tuple3<Long, Long, Double>> spans() {
    return tuples(
        longs().min(0).max(20),
        longs().min(0).max(20),
        doubles().min(0.0).max(5.0));
  }

  private static TimeEvent event(Tuple3<Long, Long, Double> span) {
    long start = Math.min(span.value1(), span.value2());
    long end = Math.max(span.value1(), span.value2());
    return new TimeEvent(start, end, span.value3());
  }

  /**
   * Two events the class calls equal must hash alike, or a {@code HashSet} of
   * events silently keeps duplicates and {@code TimeEventWeightedHash} scores
   * the same slot twice.
   *
   * <p>Both events are built over the same span, because that is the whole set
   * of pairs {@code TimeEvent.equals} calls equal — it compares start and end
   * and nothing else.
   */
  @HegelTest
  void equalEventsHashAlike(TestCase tc) {
    Tuple3<Long, Long, Double> a = tc.draw(spans(), "a");
    double otherPriority = tc.draw(doubles().min(0.0).max(5.0), "otherPriority");

    TimeEvent first = event(a);
    TimeEvent second = new TimeEvent(first.getStartTime(), first.getEndTime(), otherPriority);

    assertTrue(first.equals(second), "events over the same span were not equal");
    assertEquals(
        first.hashCode(),
        second.hashCode(),
        "equal events " + first + " and " + second + " have different hash codes");
  }

  /**
   * Equality is symmetric. Java's own {@code equals} contract requires it, and
   * collections quietly misbehave without it: whether a lookup succeeds would
   * depend on which of the two objects is in the set.
   *
   * <p>{@code sameSpan} is drawn so that the pair is sometimes equal and
   * sometimes not; symmetry has to hold either way.
   */
  @HegelTest
  void equalityIsSymmetric(TestCase tc) {
    Tuple3<Long, Long, Double> a = tc.draw(spans(), "a");
    Tuple3<Long, Long, Double> b = tc.draw(spans(), "b");
    boolean sameSpan = tc.draw(booleans(), "sameSpan");
    String payload = tc.draw(text().maxSize(4), "payload");

    TimeEvent plain = event(a);
    TimeEvent bare = sameSpan ? plain : event(b);
    ObjectTimeEvent<String> carrying =
        new ObjectTimeEvent<>(bare.getStartTime(), bare.getEndTime(), bare.getPriority(), payload);

    assertEquals(
        plain.equals(carrying),
        carrying.equals(plain),
        "TimeEvent " + plain + " and ObjectTimeEvent " + carrying + " disagree about equality");
  }

  /**
   * An event carrying no payload is still an event. {@code null} is what a
   * caller gets from a map miss or an unset field, and the class's own
   * {@code hashCode} is written to tolerate it, so {@code equals} must too.
   */
  @HegelTest
  void anEventWithNoPayloadCanBeCompared(TestCase tc) {
    Tuple3<Long, Long, Double> a = tc.draw(spans(), "a");

    TimeEvent span = event(a);
    ObjectTimeEvent<String> empty =
        new ObjectTimeEvent<>(span.getStartTime(), span.getEndTime(), null);
    ObjectTimeEvent<String> alsoEmpty =
        new ObjectTimeEvent<>(span.getStartTime(), span.getEndTime(), null);

    assertTrue(empty.equals(alsoEmpty), "two identical payload-less events were not equal");
  }

  /** Equality is reflexive and consistent with itself for a single instance. */
  @HegelTest
  void anEventEqualsItself(TestCase tc) {
    Tuple3<Long, Long, Double> a = tc.draw(spans(), "a");
    String payload = tc.draw(text().maxSize(4), "payload");

    TimeEvent plain = event(a);
    ObjectTimeEvent<String> carrying =
        new ObjectTimeEvent<>(plain.getStartTime(), plain.getEndTime(), payload);

    assertTrue(plain.equals(plain));
    assertTrue(carrying.equals(carrying));
    assertEquals(plain.hashCode(), plain.hashCode());
    assertFalse(plain.equals("not an event"));
  }

  /**
   * {@code compareTo} is a total order: antisymmetric on every pair and
   * transitive on every triple. {@code getTimeOrderedEvents} hands the array
   * straight to {@code Arrays.sort}, which throws
   * "Comparison method violates its general contract!" if either fails.
   */
  @HegelTest
  void comparisonIsATotalOrder(TestCase tc) {
    Tuple3<Long, Long, Double> a = tc.draw(spans(), "a");
    Tuple3<Long, Long, Double> b = tc.draw(spans(), "b");
    Tuple3<Long, Long, Double> c = tc.draw(spans(), "c");

    TimeEvent x = event(a);
    TimeEvent y = event(b);
    TimeEvent z = event(c);

    assertEquals(0, Integer.signum(x.compareTo(x)), "an event did not compare equal to itself");
    assertEquals(
        Integer.signum(x.compareTo(y)),
        -Integer.signum(y.compareTo(x)),
        "comparison is not antisymmetric for " + x + " and " + y);

    if (x.compareTo(y) <= 0 && y.compareTo(z) <= 0) {
      assertTrue(x.compareTo(z) <= 0, "comparison is not transitive across " + x + y + z);
    }
  }

  /**
   * Ordering a list of events yields the same events, sorted by start time, and
   * leaves the caller's list untouched. Callers pass in a query result they
   * still hold a reference to.
   */
  @HegelTest
  void orderingSortsWithoutLosingOrMutating(TestCase tc) {
    List<Tuple3<Long, Long, Double>> spans =
        tc.draw(lists(spans()).maxSize(20), "spans");

    List<TimeEvent> events = new ArrayList<>();
    for (Tuple3<Long, Long, Double> span : spans) {
      events.add(event(span));
    }
    List<TimeEvent> snapshot = new ArrayList<>(events);

    List<? extends TimeEvent> ordered = TimeEvent.getTimeOrderedEvents(events);

    assertEquals(snapshot, events, "the caller's list was reordered underneath it");
    assertEquals(events.size(), ordered.size(), "an event was dropped or duplicated");
    for (int i = 1; i < ordered.size(); i++) {
      assertTrue(
          ordered.get(i - 1).getStartTime() <= ordered.get(i).getStartTime(),
          "events came back out of order at index " + i);
    }
    List<String> before = descriptors(snapshot);
    List<String> after = descriptors(ordered);
    before.sort(null);
    after.sort(null);
    assertEquals(before, after, "the ordered list is not a permutation of the input");
  }

  /** Duration is the span the event was constructed with. */
  @HegelTest
  void durationIsTheSpanBetweenTheEnds(TestCase tc) {
    long start = tc.draw(longs().min(-1_000_000).max(1_000_000), "start");
    long length = tc.draw(longs().min(0).max(1_000_000), "length");

    TimeEvent event = new TimeEvent(start, start + length);

    assertEquals(length, event.getDuration());
    assertEquals(start, event.getStartTime());
    assertEquals(start + length, event.getEndTime());
  }

  /**
   * Coincidence is symmetric and agrees with equality — the class offers both
   * {@code happenAtSameTime} and {@code equals} over the same two fields, and a
   * caller picking either must get the same answer.
   */
  @HegelTest
  void coincidenceAgreesWithEquality(TestCase tc) {
    Tuple3<Long, Long, Double> a = tc.draw(spans(), "a");
    Tuple3<Long, Long, Double> b = tc.draw(spans(), "b");

    TimeEvent x = event(a);
    TimeEvent y = event(b);

    assertEquals(
        TimeEvent.happenAtSameTime(x, y),
        TimeEvent.happenAtSameTime(y, x),
        "coincidence is not symmetric");
    assertEquals(TimeEvent.happenAtSameTime(x, y), x.equals(y));
  }

  /**
   * A payload-carrying event hands back exactly the object it was given, and
   * two events with different payloads over the same span stay distinct.
   */
  @HegelTest
  void payloadsAreCarriedAndDistinguish(TestCase tc) {
    Tuple3<Long, Long, Double> a = tc.draw(spans(), "a");
    int first = tc.draw(integers().min(0).max(5), "first");
    int second = tc.draw(integers().min(0).max(5), "second");

    TimeEvent span = event(a);
    ObjectTimeEvent<Integer> one =
        new ObjectTimeEvent<>(span.getStartTime(), span.getEndTime(), first);
    ObjectTimeEvent<Integer> other =
        new ObjectTimeEvent<>(span.getStartTime(), span.getEndTime(), second);

    assertEquals(first, one.getTimeObject());
    assertEquals(first == second, one.equals(other));
  }

  /** A printable identity for an event, so two lists can be compared as multisets. */
  private static List<String> descriptors(List<? extends TimeEvent> events) {
    List<String> ids = new ArrayList<>(events.size());
    for (TimeEvent event : events) {
      ids.add(event.getStartTime() + ":" + event.getEndTime() + ":" + event.getPriority());
    }
    return ids;
  }
}
