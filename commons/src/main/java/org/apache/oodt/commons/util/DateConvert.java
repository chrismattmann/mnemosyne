// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements.  See the NOTICE.txt file distributed with this work for
// additional information regarding copyright ownership.  The ASF licenses this
// file to you under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License.  You may obtain a copy of
// the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
// License for the specific language governing permissions and limitations under
// the License.

package org.apache.oodt.commons.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.Locale;

/**
	The <code>DateConvert</code> class is intended to provide date/time
	conversion and parse routines. For a description of the syntax of the
	format strings see {@link SimpleDateFormat}.

	@author S. Hardman
	@version $Revision: 1.1.1.1 $
 */
public class DateConvert {

  /**
		The number of milliseconds in a minute.
	*/
	private final static long MS_IN_MINUTE = 60000;

	/**
		The number of milliseconds in an hour.
	*/
	private final static long MS_IN_HOUR = 3600000;

	/**
		The number of milliseconds in a day.
	*/
	private final static long MS_IN_DAY = 86400000;

	/**
		The format string representing the ISO 8601 format. The format
		is close to CCSDS ASCII Time Code A. 
	*/
	// Every formatter below is built with Locale.ROOT. They were built with
	// none, so each depended on whichever locale the JVM started in -- and two
	// of them broke across hosts. "yyyy-MM-dd" looks locale-proof because it
	// is all digits, but yyyy is a year in whatever calendar the locale
	// nominates: written under en-US and read under th-TH, the same string is
	// an instant 543 years away, with no exception thrown. "dd-MMM-yyyy" is
	// louder -- it writes the month abbreviation in the writer's language, so
	// a value written on an English host cannot be parsed on a German one at
	// all, and that one is a database column format, where the two ends are
	// routinely different processes.
	private final static String ISO_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS";

	/**
		The format string representing the CCSDS ASCII Time Code B format
		excluding the trailing "Z".
	*/
	private final static String DOY_FORMAT = "yyyy-DDD'T'HH:mm:ss.SSS";

	/**
		The format string representing the EDA time stamp format.
	*/
	private final static String TS_FORMAT = "yyyyMMddHHmmssSSS";

	/**
		The format string representing the DBMS format.
	*/
	private final static String DBMS_FORMAT = "dd-MMM-yyyy HH:mm:ss";

	/**
		The format string representing the Year-Month-Day format.
	*/
	private final static String YMD_FORMAT = "yyyy-MM-dd";
  public static final int INT = 24;
  public static final int BEGIN_INDEX = 23;
  public static final int ERROR_OFFSET = 24;
  public static final int ERROR_OFFSET1 = 25;


  /**
		Constructor given no arguments.

		This is a static-only class that may not be instantiated.

		@throws IllegalStateException If the class is instantiated.
	*/
	public DateConvert() throws IllegalStateException {
		throw new IllegalStateException("Instantiation of this class is not allowed.");
	}


	/**
		Format the given date and return the resulting string in ISO 8601 format.

		The format is as follows: "yyyy-MM-dd'T'HH:mm:ss.SSS[Z|[+|-]HH:mm]".

		@param inputDate The date to be converted into string format.
		@return The formatted date/time string.
	*/
	/** Zero-padded to the two digits an ISO 8601 offset field carries. */
	private static String twoDigits(long value) {
		return value < 10 ? "0" + value : String.valueOf(value);
	}

	public static String isoFormat(Date inputDate) {

		// Setup the date format and convert the given date.
		SimpleDateFormat dateFormat = new SimpleDateFormat(ISO_FORMAT, Locale.ROOT);
		String dateString = dateFormat.format(inputDate);

		// Determine the time zone and concatenate the time zone designator
		// onto the formatted date/time string.
		// The offset is asked for rather than reconstructed. What was here
		// before got three things wrong at once, all of them following from
		// building the designator by hand out of getRawOffset():
		//
		//   - getRawOffset() is the zone's standard offset *today*, not the
		//     offset in effect at the instant being formatted, so every
		//     historical timestamp was labelled with a modern offset. At
		//     millis 0 in Europe/London -- on British Standard Time, UTC+1
		//     year round from 1968 to 1971 -- the local time was rendered
		//     correctly as 01:00 and then labelled Z, so reading it back
		//     gave an instant an hour later than the one written;
		//   - the daylight correction added a whole hour, which is wrong for
		//     Australia/Lord_Howe's thirty-minute DST;
		//   - the minute field kept the sign of a negative offset and was
		//     never zero-padded, so America/St_Johns formatted as
		//     "-02:-30" -- a string isoParse itself rejects.
		//
		// getOffset(long) answers all three: it is the offset at that
		// instant, DST included, at whatever granularity the zone uses.
		//
		// The Z branch used to test tz.getDisplayName().equals("Greenwich
		// Mean Time"), a localised display string that never matched under a
		// non-English default locale. Zero is zero in every language.
		TimeZone tz = dateFormat.getTimeZone();
		int tzOffsetMS = tz.getOffset(inputDate.getTime());

		if (tzOffsetMS == 0) {
			return dateString.concat("Z");
		}

		String sign = tzOffsetMS < 0 ? "-" : "+";
		int absOffsetMS = Math.abs(tzOffsetMS);
		long tzOffsetHH = absOffsetMS / MS_IN_HOUR;
		long tzOffsetMM = (absOffsetMS % MS_IN_HOUR) / MS_IN_MINUTE;

		return dateString.concat(sign + twoDigits(tzOffsetHH) + ":"
				+ twoDigits(tzOffsetMM));
	}


	/**
		Parse the given date/time string in ISO 8601 format and return the
		resulting <code>Date</code> object.

		The format is as follows: "yyyy-MM-dd'T'HH:mm:ss.SSS[Z|[+|-]HH:mm]".

		@param inputString The string to be parsed.
		@return The resulting Date object.
		@throws ParseException If the string is null or does not match the date/time
		format.
	*/
	public static Date isoParse(String inputString) throws ParseException {

		// Setup the date format.
		SimpleDateFormat dateFormat = new SimpleDateFormat(ISO_FORMAT, Locale.ROOT);
		dateFormat.setLenient(false);

		// The length of the input string should be at least 24 characters.
		if (inputString == null || inputString.length() < INT) {
			// The offset used to be inputString.length(), which dereferences
			// the null the condition has just detected -- so the one input
			// the javadoc names explicitly, null, threw NullPointerException
			// instead of the ParseException it promises.
			throw new ParseException("An exception occurred because the input date/time string was null or under 24 characters in length.",
					inputString == null ? 0 : inputString.length());
		}

		// Evaluate the the specified offset and set the time zone.
		String offsetString = inputString.substring(BEGIN_INDEX);
		if (offsetString.equals("Z")) {
			dateFormat.setTimeZone(TimeZone.getTimeZone("Greenwich Mean Time"));
		}
		else if (offsetString.startsWith("-") || offsetString.startsWith("+")) {
			SimpleDateFormat offsetFormat = new SimpleDateFormat("", Locale.ROOT);
			if (offsetString.length() == 3) {
				offsetFormat.applyPattern("HH");
			}
			else if (offsetString.length() == 6) {
				offsetFormat.applyPattern("HH:mm");
			}
			else {
				throw new ParseException("An exception occurred because the offset portion was not the valid length of 3 or 6 characters.",
					ERROR_OFFSET1);
			}

			// Validate the given offset.
			offsetFormat.setLenient(false);

			// Set the time zone with the validated offset.
			dateFormat.setTimeZone(TimeZone.getTimeZone("GMT" + offsetString));
		}
		else {
			throw new ParseException("An exception occurred because the offset portion of the input date/time string was not 'Z' or did not start with '+' or '-'.",
				ERROR_OFFSET);
		}

		// Parse the given string.

	  return(dateFormat.parse(inputString));
	}


	/**
		Format the given date and return the resulting string in CCSDS
		ASCII Time Code B format.

		The format is as follows: "yyyy-DDD'T'HH:mm:ss.SSS".

		@param inputDate The date to be converted into string format.
		@return The formatted date/time string.
	*/
	public static String doyFormat(Date inputDate) {

		// Setup the date format and convert the given date.
		SimpleDateFormat dateFormat = new SimpleDateFormat(DOY_FORMAT, Locale.ROOT);

	  return(dateFormat.format(inputDate));
	}


	/**
		Parse the given date/time string in CCSDS ASCII Time Code B format
		and return the resulting <code>Date</code> object.

		The format is as follows: "yyyy-DDD'T'HH:mm:ss.SSS".

		@param inputString The string to be parsed.
		@return The resulting Date object.
		@throws ParseException If the string does not match the date/time
		format.
	*/
	public static Date doyParse(String inputString) throws ParseException {

		// Setup the date format and parse the given string.
		SimpleDateFormat dateFormat = new SimpleDateFormat(DOY_FORMAT, Locale.ROOT);
		dateFormat.setLenient(false);

	  return(dateFormat.parse(inputString));
	}


	/**
		Format the given date and return the resulting string in a timestamp
		format.

		The format is as follows: "yyyyMMddHHmmssSSS".

		@param inputDate The date to be converted into string format.
		@return The formatted date/time string.
	*/
	public static String tsFormat(Date inputDate) {

		// Setup the date format and convert the given date.
		SimpleDateFormat dateFormat = new SimpleDateFormat(TS_FORMAT, Locale.ROOT);

	  return(dateFormat.format(inputDate));
	}


	/**
		Parse the given date/time string in timestamp format
		and return the resulting <code>Date</code> object.

		The format is as follows: "yyyyMMddHHmmssSSS".

		@param inputString The string to be parsed.
		@return The resulting Date object.
		@throws ParseException If the string does not match the date/time
		format.
	*/
	public static Date tsParse(String inputString) throws ParseException {

		// Setup the date format and parse the given string.
		SimpleDateFormat dateFormat = new SimpleDateFormat(TS_FORMAT, Locale.ROOT);
		dateFormat.setLenient(false);

	  return(dateFormat.parse(inputString));
	}


	/**
		Format the given date and return the resulting string in a DBMS
		format.

		The format is as follows: "dd-MMM-yyyy HH:mm:ss".

		@param inputDate The date to be converted into string format.
		@return The formatted date/time string.
	*/
	public static String dbmsFormat(Date inputDate) {

		// Setup the date format and convert the given date.
		SimpleDateFormat dateFormat = new SimpleDateFormat(DBMS_FORMAT, Locale.ROOT);

	  return(dateFormat.format(inputDate));
	}


	/**
		Parse the given date/time string in DBMS format
		and return the resulting <code>Date</code> object.

		The format is as follows: "dd-MMM-yyyy HH:mm:ss".

		@param inputString The string to be parsed.
		@return The resulting Date object.
		@throws ParseException If the string does not match the date/time
		format.
	*/
	public static Date dbmsParse(String inputString) throws ParseException {

		// Setup the date format and parse the given string.
		SimpleDateFormat dateFormat = new SimpleDateFormat(DBMS_FORMAT, Locale.ROOT);
		dateFormat.setLenient(false);

	  return(dateFormat.parse(inputString));
	}


	/**
		Format the given date and return the resulting string in a
		year-month-day format.

		The format is as follows: "yyyy-MM-dd".

		@param inputDate The date to be converted into string format.
		@return The formatted date/time string.
	*/
	public static String ymdFormat(Date inputDate) {

		// Setup the date format and convert the given date.
		SimpleDateFormat dateFormat = new SimpleDateFormat(YMD_FORMAT, Locale.ROOT);

	  return(dateFormat.format(inputDate));
	}


	/**
		Parse the given date/time string in year-month-day format
		and return the resulting <code>Date</code> object.

		The format is as follows: "yyyy-MM-dd".

		@param inputString The string to be parsed.
		@return The resulting Date object.
		@throws ParseException If the string does not match the date/time
		format.
	*/
	public static Date ymdParse(String inputString) throws ParseException {

		// Setup the date format and parse the given string.
		SimpleDateFormat dateFormat = new SimpleDateFormat(YMD_FORMAT, Locale.ROOT);
		dateFormat.setLenient(false);

	  return(dateFormat.parse(inputString));
	}


	/**
		Get the number of milliseconds in a minute.

		@return The number of milliseconds in a minute.
	*/
	public static long getMsecsInMinute() {
		return(MS_IN_MINUTE);
	}


	/**
		Get the number of milliseconds in an hour.

		@return The number of milliseconds in an hour.
	*/
	public static long getMsecsInHour() {
		return(MS_IN_HOUR);
	}


	/**
		Get the number of milliseconds in a day.

		@return The number of milliseconds in a day.
	*/
	public static long getMsecsInDay() {
		return(MS_IN_DAY);
	}
}

