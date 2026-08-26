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

package org.apache.oodt.commons.util;

import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Round-trip properties for {@link DateConvert}.
 *
 * <p>These formatters carry product timestamps into the catalog and back out
 * again, so a format the matching parser cannot read is silent data loss. The
 * class had no unit tests.
 *
 * <p>{@code isoFormat} and {@code isoParse} read the default time zone rather
 * than taking one as an argument, so each property pins the default around the
 * call and restores it afterwards. The zone list is drawn deliberately: whole
 * hour offsets, half-hour offsets, negative half-hour offsets, and a
 * three-quarter-hour offset.
 */
class DateConvertPropertyTest {

  /** Epoch millis inside a range SimpleDateFormat renders with a 4-digit year. */
  private static final long MIN_MILLIS = -2_208_988_800_000L; // 1900-01-01
  private static final long MAX_MILLIS = 4_102_444_800_000L; //  2100-01-01

  private static final List<String> ZONES =
      List.of(
          "UTC",
          "Europe/London",
          "America/New_York",
          "Asia/Tokyo",
          "Australia/Adelaide", // +09:30
          "Asia/Kolkata", // +05:30
          "America/St_Johns", // -03:30, the negative half hour
          "Pacific/Marquesas", // -09:30
          "Asia/Kathmandu", // +05:45
          "Pacific/Chatham"); // +12:45

  /**
   * A timestamp written by {@code isoFormat} must read back as the same instant
   * through {@code isoParse}. The format carries a zone offset, so this has to
   * hold in every zone, not just the one the developer happened to be in.
   */
  @HegelTest
  void isoFormatRoundTripsInEveryTimeZone(TestCase tc) throws Exception {
    long millis = tc.draw(longs().min(MIN_MILLIS).max(MAX_MILLIS), "millis");
    String zoneId = tc.draw(sampledFrom(ZONES), "zone");

    TimeZone previous = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
      Date original = new Date(millis);

      String formatted = DateConvert.isoFormat(original);
      tc.note("formatted = " + formatted);

      assertEquals(original, DateConvert.isoParse(formatted));
    } finally {
      TimeZone.setDefault(previous);
    }
  }

  /**
   * The same instant is the same instant regardless of the JVM's locale. These
   * formatters build SimpleDateFormat without an explicit Locale, so a
   * deployment in a non-English locale is the case nobody tests by hand.
   */
  @HegelTest
  void isoFormatRoundTripsInEveryLocale(TestCase tc) throws Exception {
    long millis = tc.draw(longs().min(MIN_MILLIS).max(MAX_MILLIS), "millis");
    String tag = tc.draw(sampledFrom(List.of("en-US", "tr-TR", "de-DE", "ja-JP", "ar-EG")), "locale");

    Locale previous = Locale.getDefault();
    TimeZone previousZone = TimeZone.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag(tag));
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      Date original = new Date(millis);

      assertEquals(original, DateConvert.isoParse(DateConvert.isoFormat(original)));
    } finally {
      Locale.setDefault(previous);
      TimeZone.setDefault(previousZone);
    }
  }

  /** The day-of-year format and its parser must agree. */
  @HegelTest
  void doyFormatRoundTrips(TestCase tc) throws Exception {
    long millis = tc.draw(longs().min(MIN_MILLIS).max(MAX_MILLIS), "millis");
    Date original = new Date(millis);
    assertEquals(original, DateConvert.doyParse(DateConvert.doyFormat(original)));
  }

  /** The timestamp format and its parser must agree. */
  @HegelTest
  void tsFormatRoundTrips(TestCase tc) throws Exception {
    long millis = tc.draw(longs().min(MIN_MILLIS).max(MAX_MILLIS), "millis");
    Date original = new Date(millis);
    assertEquals(original, DateConvert.tsParse(DateConvert.tsFormat(original)));
  }
}
