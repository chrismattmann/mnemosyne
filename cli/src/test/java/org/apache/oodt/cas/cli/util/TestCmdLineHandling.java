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
package org.apache.oodt.cas.cli.util;

import java.util.Arrays;
import java.util.List;

import junit.framework.TestCase;

/**
 * Cursor arithmetic, column formatting, and value conversion in the CLI
 * plumbing. Each is small, and each shows up as help text that does not line
 * up or a value the user typed arriving different.
 */
public class TestCmdLineHandling extends TestCase {

    // ---- CmdLineIterable -------------------------------------------------

    /**
     * The constructor establishes -1 as a real state, but decrement clamped at
     * 0, so increment-then-decrement did not return where it started. That
     * pair is exactly what StdCmdLineConstructor.getValues uses to put an
     * argument back.
     */
    public void testIncrementThenDecrementReturnsToTheStart() {
        CmdLineIterable<String> args =
                new CmdLineIterable<String>(Arrays.asList("a", "b"));
        int before = args.getCurrentIndex();
        args.incrementIndex();
        args.descrementIndex();
        assertEquals("the cursor did not come back", before, args.getCurrentIndex());
    }

    /** Reading the current argument must not consume it. */
    public void testPeekingDoesNotConsume() {
        CmdLineIterable<String> args =
                new CmdLineIterable<String>(Arrays.asList("only"));
        assertTrue(args.hasNext());
        args.getCurrentArg();
        assertTrue("peeking consumed the argument", args.hasNext());
    }

    /** What is left of a fresh iterable is all of it. */
    public void testArgsLeftOnAFreshIterableIsEverything() {
        CmdLineIterable<String> args =
                new CmdLineIterable<String>(Arrays.asList("a", "b"));
        assertEquals(Arrays.asList("a", "b"), args.getArgsLeft());
    }

    /** Iteration itself must be unaffected. */
    public void testIterationStillYieldsEveryArgument() {
        CmdLineIterable<String> args =
                new CmdLineIterable<String>(Arrays.asList("a", "b", "c"));
        StringBuilder seen = new StringBuilder();
        for (String arg : args) {
            seen.append(arg);
        }
        assertEquals("abc", seen.toString());
    }

    // ---- getFormattedString ----------------------------------------------

    /**
     * StdCmdLinePrinter formats requirement rules into a 50-column box, and a
     * default Object.toString() runs to about 65 characters, which broke the
     * two-column help layout.
     */
    public void testAWordLongerThanTheColumnIsBroken() {
        String longWord = "org.apache.oodt.cas.cli.option.require.ActionDependencyRule@1b6d3586";
        String formatted = CmdLineUtils.getFormattedString(longWord, 10, 60);

        for (String line : formatted.split("\n")) {
            assertTrue("line overruns the column by "
                    + (line.length() - 60) + ": [" + line + "]", line.length() <= 60);
        }
    }

    public void testOrdinaryTextStillWrapsWithinTheColumn() {
        String text = "the quick brown fox jumps over the lazy dog and keeps running";
        for (String line : CmdLineUtils.getFormattedString(text, 5, 30).split("\n")) {
            assertTrue("[" + line + "]", line.length() <= 30);
        }
    }

    public void testFormattedTextKeepsItsWords() {
        String formatted = CmdLineUtils.getFormattedString("alpha beta gamma", 0, 40);
        assertTrue(formatted.contains("alpha"));
        assertTrue(formatted.contains("beta"));
        assertTrue(formatted.contains("gamma"));
    }

    // ---- convertToType ---------------------------------------------------

    /** Quoting is how a user asks for whitespace to be kept. */
    public void testQuotedWhitespaceSurvivesConversion() throws Exception {
        List<?> converted =
                CmdLineUtils.convertToType(Arrays.asList(" Bob "), String.class);
        assertEquals(" Bob ", converted.get(0));
    }

    /** Several values are still joined with a single space. */
    public void testSeveralValuesAreJoinedWithOneSpace() throws Exception {
        List<?> converted =
                CmdLineUtils.convertToType(Arrays.asList("a", "b", "c"), String.class);
        assertEquals("a b c", converted.get(0));
    }
}
