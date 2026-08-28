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

package org.apache.oodt.cas.filemgr.structs.query.conv;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * The class exists to turn a version string into a number that sorts the way
 * the string does. It did not: weighting each character by
 * Math.pow(10, position) meant several low-order characters routinely
 * outweighed a one-step difference in a high-order one, so the wrong version
 * won. The class had no unit tests.
 */
public class TestAsciiSortableVersionConverter {

    private final AsciiSortableVersionConverter converter =
        new AsciiSortableVersionConverter();

    private void assertOrderKept(String smaller, String larger) {
        assertTrue(smaller + " should sort before " + larger,
                smaller.compareTo(larger) < 0);
        assertTrue("priority order disagrees with ascii order for ["
                + smaller + "] and [" + larger + "]",
                converter.convertToPriority(smaller)
                    < converter.convertToPriority(larger));
    }

    /** The shrunk counterexample from the issue. */
    @Test
    public void testTheReportedPairKeepsItsOrder() {
        assertOrderKept("3~~~~~ys", "5&000000");
    }

    /** Equal-length pairs keep their ascii order, over a broad sample. */
    @Test
    public void testEqualLengthVersionsKeepTheirAsciiOrder() {
        Random random = new Random(42L);
        int checked = 0;

        for (int trial = 0; trial < 20000; trial++) {
            String a = randomVersion(random, 7);
            String b = randomVersion(random, 7);
            int cmp = a.compareTo(b);
            if (cmp == 0) {
                continue;
            }
            checked++;
            if (cmp < 0) {
                assertOrderKept(a, b);
            } else {
                assertOrderKept(b, a);
            }
        }

        assertTrue("the sample produced nothing to check", checked > 1000);
    }

    private static String randomVersion(Random random, int length) {
        StringBuilder version = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            version.append((char) (32 + random.nextInt(95)));
        }
        return version.toString();
    }

    /** Ordinary version strings order the way anyone would expect. */
    @Test
    public void testOrdinaryVersionsOrderAsExpected() {
        assertOrderKept("1.0", "1.1");
        assertOrderKept("1.0", "2.0");
        assertOrderKept("2024-01", "2024-02");
        assertOrderKept("a", "b");
    }

    /** A prefix sorts before the string that extends it. */
    @Test
    public void testAPrefixSortsBeforeWhatExtendsIt() {
        assertOrderKept("1.0", "1.0.1");
        assertOrderKept("a", "ab");
    }

    /**
     * Math.pow(10, position) overflowed to Infinity past about 308
     * characters, so every long version compared equal to every other.
     */
    @Test
    public void testALongVersionGetsAFinitePriority() {
        StringBuilder version = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            version.append('a');
        }

        double priority = converter.convertToPriority(version.toString());

        assertFalse("a long version overflowed to Infinity",
                Double.isInfinite(priority));
        assertFalse(Double.isNaN(priority));
    }

    /** Every priority lands in [0, 1), which is what makes them comparable. */
    @Test
    public void testEveryPriorityIsInRange() {
        for (String version : new String[] { "", "a", "~~~~~~~~", "1.0.0",
                                             "\u0000", "\u007f\u007f" }) {
            double priority = converter.convertToPriority(version);
            assertTrue(version + " -> " + priority,
                    priority >= 0.0 && priority < 1.0);
        }
    }

    /** The empty version is the smallest thing there is. */
    @Test
    public void testTheEmptyVersionIsSmallest() {
        assertEquals(0.0, converter.convertToPriority(""), 0.0);
        assertOrderKept("", "a");
    }

    /**
     * The stated limit, asserted rather than left to be discovered: seven
     * characters decide the order and anything past them ties. A tie leaves
     * two versions equally ranked; the old expression distinguished fourteen
     * characters but misordered them, which picks the wrong one.
     */
    @Test
    public void testSevenCharactersDecideTheOrder() {
        assertOrderKept("aaaaaaa", "aaaaaab");

        assertEquals("the documented limit has moved",
                converter.convertToPriority("aaaaaaaa"),
                converter.convertToPriority("aaaaaaab"), 0.0);
    }
}
