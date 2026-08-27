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

package org.apache.oodt.cas.curation.util;

import static dev.hegel.Generators.longs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Properties of the two date formats the curator publishes,
 * {@link DateUtils#getDateAsISO8601String(Date)} and
 * {@link DateUtils#getDateAsRFC822String(Date)}.
 *
 * <p>Both are written into responses that other systems read: the ISO string
 * stamps ingestion tasks, the RFC822 string is the timestamp format feed readers
 * expect. A timestamp that does not parse back to the instant it was written for
 * is not a formatting nit - it is the wrong time, delivered confidently.
 *
 * <p>Instants are drawn across roughly 1970 to 2100 in milliseconds. Both
 * formats stop at whole seconds, so the round trip is asserted to the second.
 */
class DateUtilsPropertyTest {

  private static final long LATEST_MILLIS = 4_102_444_800_000L;

  private static Date drawDate(TestCase tc, String label) {
    long millis = tc.draw(longs().min(0).max(LATEST_MILLIS), label);
    return new Date(millis);
  }

  /**
   * An ISO 8601 timestamp reads back as the instant it was written for. The
   * class exists to add the colon a bare {@code SimpleDateFormat} leaves out of
   * the zone offset, so the string it produces has to be one a standard ISO 8601
   * parser accepts.
   */
  @HegelTest
  void iso8601TimestampsReadBackAsTheSameInstant(TestCase tc) throws Exception {
    Date date = drawDate(tc, "date");

    String written = DateUtils.getDateAsISO8601String(date);
    tc.note("written = " + written);

    Date read = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").parse(written);
    assertEquals(date.getTime() / 1000L * 1000L, read.getTime(), "written as " + written);
  }

  /**
   * The zone offset always carries its colon. This is the single thing the
   * method adds over the raw format, and a reader that requires strict ISO 8601
   * rejects the string without it.
   */
  @HegelTest
  void iso8601TimestampsCarryAColonInTheZoneOffset(TestCase tc) {
    Date date = drawDate(tc, "date");

    String written = DateUtils.getDateAsISO8601String(date);
    tc.note("written = " + written);

    assertTrue(
        written.matches(".*[+\\-]\\d{2}:\\d{2}$"), "no colon in the zone offset of " + written);
  }

  /** An RFC822 timestamp reads back as the instant it was written for. */
  @HegelTest
  void rfc822TimestampsReadBackAsTheSameInstant(TestCase tc) throws Exception {
    Date date = drawDate(tc, "date");

    String written = DateUtils.getDateAsRFC822String(date);
    tc.note("written = " + written);

    Date read =
        new SimpleDateFormat("EEE', 'dd' 'MMM' 'yyyy' 'HH:mm:ss' 'Z", Locale.US).parse(written);
    assertEquals(date.getTime() / 1000L * 1000L, read.getTime(), "written as " + written);
  }

  /** Formatting the same instant twice gives the same string. */
  @HegelTest
  void formattingIsDeterministic(TestCase tc) {
    Date date = drawDate(tc, "date");

    assertEquals(
        DateUtils.getDateAsISO8601String(date), DateUtils.getDateAsISO8601String(date));
    assertEquals(DateUtils.getDateAsRFC822String(date), DateUtils.getDateAsRFC822String(date));
  }
}
