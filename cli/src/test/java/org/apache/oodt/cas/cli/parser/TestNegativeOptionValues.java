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
package org.apache.oodt.cas.cli.parser;

import java.util.List;

import org.apache.oodt.cas.cli.util.ParsedArg;

import junit.framework.TestCase;

/**
 * A negative number is a value, not an option.
 *
 * convertToType explicitly supports Integer, Long and Double, so passing a
 * negative one is a supported thing to want, and there is no escape hatch:
 * this parser implements no end-of-options marker and no --opt=value form,
 * and shell quoting does not help because "-5" still arrives as -5 in argv.
 */
public class TestNegativeOptionValues extends TestCase {

    public void testANegativeIntegerIsNotAnOption() {
        assertFalse("-5 was read as an option named 5",
                StdCmdLineParser.isOption("-5"));
    }

    public void testANegativeDecimalIsNotAnOption() {
        assertFalse(StdCmdLineParser.isOption("-2.5"));
    }

    public void testANegativeNumberParsesAsAValue() throws Exception {
        List<ParsedArg> parsed =
                new StdCmdLineParser().parse(new String[] {"--count", "-5"});

        assertEquals(2, parsed.size());
        assertEquals(ParsedArg.Type.OPTION, parsed.get(0).getType());
        assertEquals("count", parsed.get(0).getName());
        assertEquals(ParsedArg.Type.VALUE, parsed.get(1).getType());
        assertEquals("-5", parsed.get(1).getName());
    }

    /** Real options must still be options. */
    public void testShortOptionIsStillAnOption() {
        assertTrue(StdCmdLineParser.isOption("-c"));
    }

    public void testLongOptionIsStillAnOption() {
        assertTrue(StdCmdLineParser.isOption("--count"));
    }

    /** A bare hyphen is conventionally a value, meaning stdin. */
    public void testABareHyphenIsNotAnOption() {
        assertFalse(StdCmdLineParser.isOption("-"));
    }

    public void testAnOrdinaryValueIsStillAValue() {
        assertFalse(StdCmdLineParser.isOption("filename.txt"));
    }
}
