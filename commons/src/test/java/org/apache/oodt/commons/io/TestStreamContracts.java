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
package org.apache.oodt.commons.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import junit.framework.TestCase;

/**
 * Places where these streams depart from what java.io specifies. Each is small
 * on its own, but a stream that does not honour the interface it implements
 * fails in whatever generic code is handed it.
 */
public class TestStreamContracts extends TestCase {

    /**
     * A bulk write that exactly filled the buffer left the ring cursor one past
     * the end, so the next single-byte write indexed off the array. Bulk-only
     * and single-byte-only both pass; only the mix was broken.
     */
    public void testFixedBufferAcceptsASingleByteAfterAnExactBulkWrite() throws Exception {
        FixedBufferOutputStream out = new FixedBufferOutputStream(1);
        out.write(new byte[] {'a'}, 0, 1);
        out.write('b');
        assertEquals(1, out.getBuffer().length);
        assertEquals((byte) 'b', out.getBuffer()[0]);
    }

    public void testFixedBufferKeepsWorkingAcrossSeveralMixedWrites() throws Exception {
        FixedBufferOutputStream out = new FixedBufferOutputStream(4);
        out.write(new byte[] {'a', 'b', 'c', 'd'}, 0, 4);
        out.write('e');
        out.write(new byte[] {'f', 'g'}, 0, 2);
        out.write('h');
        assertEquals(4, out.getBuffer().length);
    }

    /** Closeable: "if the stream is already closed then invoking this method has no effect". */
    public void testNullInputStreamCloseIsIdempotent() throws Exception {
        NullInputStream in = new NullInputStream();
        in.close();
        in.close();
    }

    public void testNullOutputStreamCloseIsIdempotent() throws Exception {
        NullOutputStream out = new NullOutputStream();
        out.close();
        out.close();
    }

    /** InputStream: a zero length reads nothing and returns 0. */
    public void testZeroLengthReadReadsNothing() throws Exception {
        byte[] encoded = "YWJjZA==".getBytes("US-ASCII");   // "abcd"
        Base64DecodingInputStream in =
                new Base64DecodingInputStream(new ByteArrayInputStream(encoded));

        byte[] target = new byte[4];
        assertEquals("a zero-length read must return 0", 0, in.read(target, 0, 0));

        // and it must not have consumed anything
        assertEquals(4, in.read(target, 0, 4));
        assertEquals('a', target[0]);
        assertEquals('d', target[3]);
        in.close();
    }

    /** OutputStream: write(b, 0, 0) on an empty array is legal and writes nothing. */
    public void testZeroLengthWriteOfAnEmptyArrayIsAccepted() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Base64EncodingOutputStream out = new Base64EncodingOutputStream(sink);
        out.write(new byte[0], 0, 0);
        out.close();
    }

    /** A zero-length write at the end of a non-empty array is legal too. */
    public void testZeroLengthWriteAtTheEndOfAnArrayIsAccepted() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Base64EncodingOutputStream out = new Base64EncodingOutputStream(sink);
        out.write(new byte[] {'a'}, 1, 0);
        out.close();
    }

    /** Ordinary encoding must be unaffected. */
    public void testOrdinaryEncodingStillWorks() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Base64EncodingOutputStream out = new Base64EncodingOutputStream(sink);
        out.write("abcd".getBytes("US-ASCII"), 0, 4);
        out.close();
        assertTrue(sink.size() > 0);
    }
}
