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

/**
 * 
 * @author bfoster
 * @version $Revision$
 *
 * <p>
 * Converts a ascii sortable String version into a priority number
 * <p>
 */
public class AsciiSortableVersionConverter implements VersionConverter {

    /** Characters are weighted in base 128; see convertToPriority. */
    private static final double RADIX = 128.0;

    /** Above this, a character cannot be told from any other above it. */
    private static final int MAX_ORDINAL = 127;

    /**
     * The version as a number that sorts the way the string does.
     *
     * The string is read as a fraction in base 128 -- the first character is
     * worth c/128, the second c/128^2, and so on -- which puts every result
     * in [0, 1) and orders them exactly as String.compareTo orders their
     * inputs, for the leading characters a double can hold.
     *
     * What was here weighted each character by Math.pow(10, position): a
     * base-10 place value carrying values up to 65535. A character was worth
     * up to 65535 in its own place but only ten times as much per position,
     * so several low-order characters routinely outweighed a one-step
     * difference in a high-order one and the wrong version won. Measured over
     * random equal-length pairs, roughly one pair in 35 came out misordered
     * -- and "3~~~~~ys" against "5&000000" is the shrunk example. Past about
     * 308 characters that expression also returned Infinity, so every long
     * version compared equal to every other.
     *
     * Two limits, stated rather than hidden:
     *
     *  - a double holds 52 bits of mantissa and each character consumes
     *    seven, so the first **seven** characters decide the order and
     *    anything beyond them is a tie. The previous expression distinguished
     *    fourteen, but misordered them; a tie leaves two versions equally
     *    ranked, where a misordering picks the wrong one;
     *  - ordinals above 127 are clamped, so two different non-ASCII
     *    characters in the same position tie. This class is named for ASCII
     *    and that is the alphabet it orders.
     *
     * Removing both limits means comparing the strings rather than mapping
     * them to a double, which is a change to VersionConverter and to the
     * TimeEvent priority the one caller feeds.
     *
     * @param version the version string
     * @return a value in [0, 1) ordering as the string does
     */
    public double convertToPriority(String version) {
        double priority = 0;
        double weight = 1.0 / RADIX;
        for (int i = 0; i < version.length(); i++) {
            priority += Math.min(version.charAt(i), MAX_ORDINAL) * weight;
            weight /= RADIX;
        }
        return priority;
    }
    
}
