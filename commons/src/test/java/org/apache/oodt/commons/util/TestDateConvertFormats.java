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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.*;

/**
 * DateConvert had no unit tests. These cover the two issues against it: the
 * offset isoFormat writes (#105) and the locale every formatter in the class
 * depended on (#114).
 */
public class TestDateConvertFormats {

    private Locale locale;
    private TimeZone zone;

    @Before
    public void remember() {
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
    }

    @After
    public void restore() {
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
    }

    // ---- #105: the offset -------------------------------------------------

    /**
     * The shrunk counterexample. The UK was on British Standard Time -- UTC+1
     * year round -- from 1968 to 1971. getRawOffset() reports 0 for that zone
     * today, so the local time was rendered correctly as 01:00 and then
     * labelled Z, and reading it back gave an instant an hour later than the
     * one written.
     */
    @Test
    public void testAHistoricalOffsetIsTheOffsetAtThatInstant() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));

        assertEquals("1970-01-01T01:00:00.000+01:00",
                DateConvert.isoFormat(new Date(0L)));
    }

    /**
     * The minute field kept the sign of a negative offset and was never
     * zero-padded, so this formatted as "-02:-30" -- a string isoParse
     * itself rejects.
     */
    @Test
    public void testANegativeHalfHourZoneIsWellFormed() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("America/St_Johns"));

        String formatted = DateConvert.isoFormat(new Date(1755000000000L));

        assertFalse("the sign is emitted twice: " + formatted,
                formatted.contains(":-"));
        assertNotNull("isoFormat produced a string isoParse cannot read: "
                + formatted, DateConvert.isoParse(formatted));
    }

    /** Positive half-hour zones kept working. */
    @Test
    public void testAPositiveHalfHourZoneStillRoundTrips() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));

        String formatted = DateConvert.isoFormat(new Date(1755000000000L));

        assertTrue(formatted.endsWith("+05:30"));
        assertNotNull(DateConvert.isoParse(formatted));
    }

    /**
     * Lord Howe's daylight saving is thirty minutes. The old code added a
     * whole hour whenever inDaylightTime was true.
     */
    @Test
    public void testAThirtyMinuteDaylightShiftIsNotRoundedToAnHour() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Lord_Howe"));
        Date summer = new Date(1706000000000L);

        String formatted = DateConvert.isoFormat(summer);
        TimeZone zone = TimeZone.getTimeZone("Australia/Lord_Howe");
        int offsetMinutes = zone.getOffset(summer.getTime()) / 60000;

        assertTrue("the offset does not match the zone: " + formatted,
                formatted.endsWith(String.format("+%02d:%02d",
                        offsetMinutes / 60, offsetMinutes % 60)));
    }

    /** UTC is Z, and Z is not a display string in anybody's language. */
    @Test
    public void testUtcIsWrittenAsZUnderAnyLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Locale.setDefault(Locale.GERMANY);

        assertTrue(DateConvert.isoFormat(new Date(0L)).endsWith("Z"));
    }

    /** The round trip that started all this. */
    @Test
    public void testIsoFormatRoundTripsInSeveralZones() throws Exception {
        String[] zones = { "UTC", "Europe/London", "America/St_Johns",
                           "Asia/Kolkata", "Pacific/Marquesas",
                           "Australia/Lord_Howe", "America/Los_Angeles" };
        long[] instants = { 0L, 1755000000000L, 1706000000000L };

        for (String id : zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(id));
            for (long millis : instants) {
                Date original = new Date(millis);
                String formatted = DateConvert.isoFormat(original);
                assertEquals(id + " at " + millis + " formatted as " + formatted,
                        original, DateConvert.isoParse(formatted));
            }
        }
    }

    // ---- #114: the locale -------------------------------------------------

    /**
     * "yyyy-MM-dd" looks locale-proof because it is all digits, but yyyy is a
     * year in whatever calendar the locale nominates. Written under en-US and
     * read under th-TH, the same string was an instant 543 years away -- with
     * no exception thrown.
     */
    @Test
    public void testAYmdDateReadsTheSameUnderAnyLocale() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        Locale.setDefault(Locale.US);
        String written = DateConvert.ymdFormat(new Date(0L));

        Locale.setDefault(new Locale("th", "TH"));
        Date readInThai = DateConvert.ymdParse(written);

        Locale.setDefault(Locale.US);
        assertEquals("the same string is a different instant in another locale",
                DateConvert.ymdParse(written), readInThai);
    }

    /**
     * "dd-MMM-yyyy" writes the month abbreviation in the writer's language,
     * so a value written on an English host could not be parsed on a German
     * one at all -- and this is a database column format, where the two ends
     * are routinely different processes.
     */
    @Test
    public void testADbmsDateWrittenInOneLocaleIsReadableInAnother() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        Locale.setDefault(Locale.US);
        String written = DateConvert.dbmsFormat(new Date(0L));

        for (Locale reader : new Locale[] { Locale.GERMANY, Locale.FRANCE,
                                            new Locale("tr", "TR") }) {
            Locale.setDefault(reader);
            assertNotNull("unreadable under " + reader + ": " + written,
                    DateConvert.dbmsParse(written));
        }
    }

    /** and the value itself is unchanged under a different locale. */
    @Test
    public void testAYmdDateIsWrittenTheSameUnderAnyLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        Locale.setDefault(Locale.US);
        String inUs = DateConvert.ymdFormat(new Date(0L));
        Locale.setDefault(new Locale("th", "TH"));
        String inThai = DateConvert.ymdFormat(new Date(0L));

        assertEquals(inUs, inThai);
    }
}
