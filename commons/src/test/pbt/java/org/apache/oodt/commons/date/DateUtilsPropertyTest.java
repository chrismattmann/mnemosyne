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

package org.apache.oodt.commons.date;

import static dev.hegel.Generators.longs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Calendar;
import java.util.TimeZone;
import org.apache.oodt.commons.exceptions.CommonsException;

/**
 * Properties of {@link DateUtils}, which carries product timestamps between the
 * UTC, local and TAI representations.
 *
 * <p>The methods under test are a formatter and a matching parser for each
 * representation, plus the leap-second table they share. A format its own
 * parser cannot read back is silent corruption of an acquisition time, so every
 * property here is a round trip.
 *
 * <p>Times are drawn from 1993 to 2038: after the first row of the leap-second
 * table has any meaning, and inside the range the four-digit-year formats can
 * express.
 *
 * <p>The class had no unit tests.
 */
class DateUtilsPropertyTest {

  /** 1993-01-01, the TAI93 epoch the class ships as a constant. */
  private static final long MIN_MILLIS = 725_846_400_000L;

  /** 2038-01-01, comfortably inside every format used here. */
  private static final long MAX_MILLIS = 2_145_916_800_000L;

  private static Calendar utcAt(long millis) {
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    cal.setTimeInMillis(millis);
    return cal;
  }

  /**
   * A UTC timestamp printed and parsed back is the same instant. This pair is
   * how a time crosses a catalog boundary, so a mismatch is a product filed
   * under the wrong date.
   */
  @HegelTest
  void utcTimesRoundTrip(TestCase tc) throws Exception {
    long millis = tc.draw(longs().min(MIN_MILLIS).max(MAX_MILLIS), "millis");

    Calendar original = utcAt(millis);
    String printed = DateUtils.toString(original);
    Calendar parsed = DateUtils.toCalendar(printed, DateUtils.FormatType.UTC_FORMAT);

    assertEquals(millis, parsed.getTimeInMillis(), "round trip through [" + printed + "] moved the instant");
  }

  /**
   * Printing a parsed timestamp gives the text back. The two directions have to
   * agree on the format, not just on the instant.
   */
  @HegelTest
  void utcTextRoundTrips(TestCase tc) throws Exception {
    long millis = tc.draw(longs().min(MIN_MILLIS).max(MAX_MILLIS), "millis");

    String printed = DateUtils.toString(utcAt(millis));
    Calendar parsed = DateUtils.toCalendar(printed, DateUtils.FormatType.UTC_FORMAT);

    assertEquals(printed, DateUtils.toString(parsed), "reprinting the parsed timestamp changed it");
  }

  /**
   * A UTC time converted to TAI, printed, parsed and converted back is the
   * instant it started as. TAI is the representation this class exists to
   * support; if the conversion is not reversible the leap-second handling is
   * worthless.
   */
  @HegelTest
  void taiTimesRoundTrip(TestCase tc) throws Exception {
    long millis = tc.draw(longs().min(MIN_MILLIS).max(MAX_MILLIS), "millis");

    Calendar tai = DateUtils.toTai(utcAt(millis));
    String printed = DateUtils.toString(tai);
    Calendar parsed = DateUtils.toCalendar(printed, DateUtils.FormatType.TAI_FORMAT);

    assertEquals(
        tai.getTimeInMillis(),
        parsed.getTimeInMillis(),
        "TAI round trip through [" + printed + "] moved the instant");
    assertEquals(
        millis,
        DateUtils.toUtc(parsed).getTimeInMillis(),
        "converting back out of TAI did not recover the UTC instant");
  }

  /**
   * Converting to UTC and back to UTC is the identity, whichever representation
   * the caller started from. Callers normalise defensively — {@code toUtc} on a
   * calendar that may already be UTC — so it must not shift anything.
   */
  @HegelTest
  void normalisingToUtcIsStable(TestCase tc) {
    long millis = tc.draw(longs().min(MIN_MILLIS).max(MAX_MILLIS), "millis");

    Calendar once = DateUtils.toUtc(utcAt(millis));
    Calendar twice = DateUtils.toUtc(once);

    assertEquals(millis, once.getTimeInMillis());
    assertEquals(millis, twice.getTimeInMillis(), "normalising twice moved the instant");
    assertEquals(millis, DateUtils.toLocal(once).getTimeInMillis(), "the local view is a different instant");
  }

  /**
   * Every instant at or after the Unix epoch has a leap-second count. The table
   * opens with a row keyed at zero precisely so that the earliest supported
   * dates resolve to ten seconds, and callers reach this method indirectly
   * through {@code toTai} on whatever timestamp a product carries.
   */
  @HegelTest
  void everyInstantSinceTheEpochHasALeapSecondCount(TestCase tc) throws CommonsException {
    long millis = tc.draw(longs().min(0).max(MAX_MILLIS), "millis");

    int leapSecs = DateUtils.getLeapSecsForDate(utcAt(millis));

    assertTrue(leapSecs >= 10, "implausible leap second count " + leapSecs);
    assertTrue(leapSecs <= 40, "implausible leap second count " + leapSecs);
  }

  /**
   * Leap seconds only ever accumulate: a later instant never has fewer of them
   * than an earlier one. The table is read by scanning backwards, so a
   * mis-ordered row would show up as a non-monotonic answer.
   */
  @HegelTest
  void leapSecondsNeverGoBackwards(TestCase tc) throws CommonsException {
    long earlier = tc.draw(longs().min(1).max(MAX_MILLIS), "earlier");
    long later = tc.draw(longs().min(1).max(MAX_MILLIS), "later");
    long low = Math.min(earlier, later);
    long high = Math.max(earlier, later);

    assertTrue(
        DateUtils.getLeapSecsForDate(utcAt(low)) <= DateUtils.getLeapSecsForDate(utcAt(high)),
        "leap seconds decreased between " + low + " and " + high);
  }

  /**
   * Elapsed time from an epoch is measured in the same units the caller thinks
   * in: seconds are milliseconds over a thousand, and the difference grows one
   * for one with the instant.
   */
  @HegelTest
  void elapsedTimeTracksTheInstant(TestCase tc) throws CommonsException {
    long millis = tc.draw(longs().min(MIN_MILLIS).max(MAX_MILLIS), "millis");
    long shift = tc.draw(longs().min(0).max(1_000_000_000L), "shift");

    Calendar epoch = DateUtils.tai93epoch;
    long base = DateUtils.getTimeInMillis(utcAt(millis), epoch);
    long shifted = DateUtils.getTimeInMillis(utcAt(millis + shift), epoch);

    assertEquals(shift, shifted - base, "elapsed time did not track the instant");
    assertEquals(
        base / 1000.0,
        DateUtils.getTimeInSecs(utcAt(millis), epoch),
        1e-9,
        "seconds and milliseconds disagree");
  }
}
