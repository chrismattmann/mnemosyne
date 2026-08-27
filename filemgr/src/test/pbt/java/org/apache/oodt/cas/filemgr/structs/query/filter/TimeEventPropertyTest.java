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

package org.apache.oodt.cas.filemgr.structs.query.filter;

import static dev.hegel.Generators.doubles;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.HashSet;
import java.util.Set;

/**
 * Equality properties for {@link TimeEvent} and {@link ObjectTimeEvent}, the
 * value types a {@link FilterAlgor} sorts and de-duplicates query results with.
 *
 * <p>Both classes override {@code equals} and {@code hashCode}. Filter
 * algorithms put these into hash-based collections to drop overlapping events
 * and to look an event's product back up, so the two methods have to agree:
 * anything the class calls equal must hash the same, and equality has to be
 * symmetric. Where they do not agree, an event silently fails to be found in a
 * set that contains it and a duplicate product is handed back to the user.
 *
 * <p>Times are drawn from a modest range so that a start and an end can be
 * generated in order without arithmetic overflow entering into it.
 */
class TimeEventPropertyTest {

  private static Generator<Long> times() {
    return longs().min(0).max(1_000_000L);
  }

  private static Generator<Double> priorities() {
    return doubles().min(0.0).max(100.0);
  }

  private static Generator<String> labels() {
    return text().minSize(1).maxSize(6).categories("Lu", "Ll");
  }

  /**
   * Two events over the same interval are equal, so they must hash alike.
   *
   * <p>{@code equals} compares only the start and end times. Anything else the
   * class carries must therefore stay out of {@code hashCode}, or two events
   * the class itself calls equal land in different buckets and a
   * {@link java.util.HashSet} keeps both.
   */
  @HegelTest
  void equalEventsHashAlike(TestCase tc) {
    long start = tc.draw(times(), "start");
    long end = tc.draw(times().map(t -> t + start), "end");
    double priorityA = tc.draw(priorities(), "priorityA");
    double priorityB = tc.draw(priorities(), "priorityB");

    TimeEvent a = new TimeEvent(start, end, priorityA);
    TimeEvent b = new TimeEvent(start, end, priorityB);
    tc.assume(a.equals(b));

    assertEquals(
        a.hashCode(),
        b.hashCode(),
        "equal events hash differently: " + a + " vs " + b);
  }

  /**
   * A set that has been given an event must report that it contains an equal
   * one.
   *
   * <p>This is the same defect as above stated the way a caller meets it:
   * de-duplicating a result list through a {@code HashSet} keeps both copies of
   * an event whose priority happens to differ.
   */
  @HegelTest
  void aSetDeDuplicatesEqualEvents(TestCase tc) {
    long start = tc.draw(times(), "start");
    long end = tc.draw(times().map(t -> t + start), "end");
    double priorityA = tc.draw(priorities(), "priorityA");
    double priorityB = tc.draw(priorities(), "priorityB");
    tc.assume(priorityA != priorityB);

    Set<TimeEvent> seen = new HashSet<>();
    seen.add(new TimeEvent(start, end, priorityA));
    seen.add(new TimeEvent(start, end, priorityB));

    assertEquals(1, seen.size(), "two events the class calls equal both survived de-duplication");
  }

  /** Equality must be reflexive and symmetric between two plain time events. */
  @HegelTest
  void plainEventEqualityIsSymmetric(TestCase tc) {
    long startA = tc.draw(times(), "startA");
    long endA = tc.draw(times().map(t -> t + startA), "endA");
    long startB = tc.draw(times(), "startB");
    long endB = tc.draw(times().map(t -> t + startB), "endB");

    TimeEvent a = new TimeEvent(startA, endA);
    TimeEvent b = new TimeEvent(startB, endB);

    assertTrue(a.equals(a), "an event is not equal to itself");
    assertEquals(a.equals(b), b.equals(a), "equality is asymmetric between " + a + " and " + b);
  }

  /**
   * Equality must stay symmetric when one side carries an attached object.
   *
   * <p>A filter's working list holds {@link ObjectTimeEvent}s while callers and
   * the surrounding code hold plain {@link TimeEvent}s — {@code
   * FilterAlgor.filterEvents} is declared over the base type. Asymmetric
   * equality means {@code list.contains(event)} answers differently depending
   * on which of the two the list happens to hold, and {@code List.remove}
   * removes an event that {@code List.indexOf} would not have found.
   */
  @HegelTest
  void equalityIsSymmetricAcrossTheObjectSubclass(TestCase tc) {
    long start = tc.draw(times(), "start");
    long end = tc.draw(times().map(t -> t + start), "end");
    String attached = tc.draw(labels(), "attached");

    TimeEvent plain = new TimeEvent(start, end);
    ObjectTimeEvent<String> withObject = new ObjectTimeEvent<>(start, end, attached);

    assertEquals(
        plain.equals(withObject),
        withObject.equals(plain),
        "plain.equals(withObject) = "
            + plain.equals(withObject)
            + " but withObject.equals(plain) = "
            + withObject.equals(plain));
  }

  /**
   * Two events with different attached objects over the same interval are not
   * equal, so a set must keep both.
   *
   * <p>{@code ObjectTimeEvent.equals} compares the attached object as well as
   * the interval, which is the point of the class: two products covering the
   * same time window are two distinct results.
   */
  @HegelTest
  void distinctAttachedObjectsAreKeptApart(TestCase tc) {
    long start = tc.draw(times(), "start");
    long end = tc.draw(times().map(t -> t + start), "end");
    String first = tc.draw(labels(), "first");
    String second = tc.draw(labels(), "second");
    tc.assume(!first.equals(second));

    ObjectTimeEvent<String> a = new ObjectTimeEvent<>(start, end, first);
    ObjectTimeEvent<String> b = new ObjectTimeEvent<>(start, end, second);

    assertFalse(a.equals(b), "two different products over the same window compared equal");

    Set<ObjectTimeEvent<String>> seen = new HashSet<>();
    seen.add(a);
    seen.add(b);
    assertEquals(2, seen.size(), "a distinct product was dropped from the result set");
  }

  /**
   * An attached object must survive being wrapped, and the derived duration
   * must agree with the interval it was built from.
   */
  @HegelTest
  void durationAgreesWithTheInterval(TestCase tc) {
    long start = tc.draw(times(), "start");
    long end = tc.draw(times().map(t -> t + start), "end");
    String attached = tc.draw(labels(), "attached");

    ObjectTimeEvent<String> event = new ObjectTimeEvent<>(start, end, attached);

    assertEquals(attached, event.getTimeObject());
    assertEquals(end - start, event.getDuration(), "duration disagrees with start and end");
    assertTrue(event.getDuration() >= 0, "an event that ends after it starts has a negative span");
    assertTrue(
        TimeEvent.happenAtSameTime(event, new TimeEvent(start, end)),
        "an event does not happen at the same time as its own interval");
  }
}
