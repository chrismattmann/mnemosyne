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

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * decode wrote its ascii-to-6-bit translation back into the caller's array,
 * so decoding destroyed the encoded buffer it was handed. Nothing in
 * "public static byte[] decode(byte[])" suggests the argument is consumed,
 * and the method returns a fresh array, which makes it look pure.
 */
public class TestBase64Purity {

    /** The shrunk counterexample: original = [0], encoded as "AA==". */
    @Test
    public void testDecodeLeavesItsArgumentAlone() {
        byte[] encoded = Base64.encode(new byte[] { 0 });
        byte[] before = Arrays.copyOf(encoded, encoded.length);

        Base64.decode(encoded);

        assertArrayEquals("decode overwrote the array it was given",
                before, encoded);
    }

    /** So decoding the same buffer twice gives the same answer. */
    @Test
    public void testDecodingTheSameBufferTwiceGivesTheSameAnswer() {
        byte[] encoded = Base64.encode(new byte[] { 0 });

        assertArrayEquals(Base64.decode(encoded), Base64.decode(encoded));
    }

    /** An empty payload is a legal thing to encode, and to decode back. */
    @Test
    public void testAnEmptyPayloadRoundTrips() {
        byte[] encoded = Base64.encode(new byte[0]);

        assertArrayEquals(new byte[0], Base64.decode(encoded));
    }

    /** Ordinary use is unchanged; this is what kept the defect alive. */
    @Test
    public void testTheRoundTripStillWorks() {
        for (int length = 0; length < 40; length++) {
            byte[] original = new byte[length];
            for (int i = 0; i < length; i++) {
                original[i] = (byte) (i * 7 - 128);
            }
            assertArrayEquals("length " + length, original,
                    Base64.decode(Base64.encode(original)));
        }
    }

    /** and a decode of a region leaves the rest of the buffer alone too. */
    @Test
    public void testDecodingARegionLeavesTheWholeBufferAlone() {
        byte[] encoded = Base64.encode(new byte[] { 1, 2, 3 });
        byte[] padded = new byte[encoded.length + 4];
        System.arraycopy(encoded, 0, padded, 2, encoded.length);
        byte[] before = Arrays.copyOf(padded, padded.length);

        Base64.decode(padded, 2, encoded.length);

        assertArrayEquals(before, padded);
    }
}
