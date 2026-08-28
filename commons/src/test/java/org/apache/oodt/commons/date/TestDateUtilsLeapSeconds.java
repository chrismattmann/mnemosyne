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

import org.apache.oodt.commons.exceptions.CommonsException;

import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

/**
 * The leap-second table's first row is keyed at exactly 0. The scan compared
 * with {@code <}, so the epoch itself matched no row and raised "No Leap Second
 * found for given date!" -- one millisecond either side worked.
 */
public class TestDateUtilsLeapSeconds {

  @Test
  public void theEpochItselfHasALeapSecondCount() throws CommonsException {
    assertEquals(10, DateUtils.getLeapSecsForDate(atMillis(0L)));
  }

  @Test
  public void theMillisecondAfterTheEpochIsUnchanged() throws CommonsException {
    assertEquals(10, DateUtils.getLeapSecsForDate(atMillis(1L)));
  }

  @Test
  public void alaterDateStillSelectsItsOwnRow() throws CommonsException {
    Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    cal.set(2010, Calendar.JUNE, 1, 0, 0, 0);
    assertEquals(34, DateUtils.getLeapSecsForDate(cal));
  }

  @Test(expected = CommonsException.class)
  public void aDateBeforeTheTableStillFails() throws CommonsException {
    DateUtils.getLeapSecsForDate(atMillis(-1L));
  }

  private Calendar atMillis(long millis) {
    Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    cal.setTimeInMillis(millis);
    return cal;
  }
}
