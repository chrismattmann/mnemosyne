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

import org.junit.Test;

import java.text.ParseException;
import java.util.Date;

import static org.junit.Assert.*;

/**
 * isoParse's javadoc names null explicitly: "@throws ParseException If the
 * string is null or does not match the date/time format." The guard detected
 * null and then passed inputString.length() as the error offset, so the one
 * input the contract calls out by name was the one input that threw the wrong
 * exception.
 */
public class TestDateConvertIsoParse {

    @Test
    public void testNullIsRejectedWithTheDocumentedException() {
        try {
            DateConvert.isoParse(null);
            fail("null was accepted");
        } catch (ParseException expected) {
            // the documented outcome
        } catch (NullPointerException e) {
            fail("isoParse(null) threw NullPointerException where its javadoc "
                    + "promises ParseException");
        }
    }

    /** a string too short to be a timestamp is still a ParseException. */
    @Test
    public void testTooShortAStringIsRejectedTheSameWay() {
        try {
            DateConvert.isoParse("2026-01-01");
            fail("a ten-character string was accepted");
        } catch (ParseException expected) {
            assertEquals("the error offset no longer points at the input",
                    10, expected.getErrorOffset());
        }
    }

    /** and a well-formed timestamp still parses. */
    @Test
    public void testAWellFormedTimestampStillParses() throws Exception {
        Date d = DateConvert.isoParse("2026-01-01T00:00:00.000Z");
        assertNotNull(d);
    }
}
