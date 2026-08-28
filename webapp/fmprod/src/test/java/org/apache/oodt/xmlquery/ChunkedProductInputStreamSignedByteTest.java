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

package org.apache.oodt.xmlquery;

import org.apache.oodt.product.ProductException;
import org.apache.oodt.product.Retriever;

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * read() returned a signed byte.
 *
 * InputStream.read() is specified to return a value in 0..255 and to reserve
 * -1 for end of stream. A byte[] element sign-extends when widened, so a
 * stored 0xFF came back as -1 and the canonical read loop
 *
 *   while ((b = in.read()) != -1) { out.write(b); }
 *
 * stopped there and reported success. Every binary product -- HDF, NetCDF,
 * compressed archives, images -- was silently truncated at its first 0xFF,
 * which for most formats is within the first few dozen bytes.
 *
 * The existing ChunkedProductInputStreamTest masks the result with 0xff on
 * the *test* side, which is the mask the production code should have applied,
 * so it compensated for the defect rather than catching it. This one reads
 * the way a caller does.
 */
public class ChunkedProductInputStreamSignedByteTest extends TestCase
        implements Retriever {

    private byte[] data;

    public ChunkedProductInputStreamSignedByteTest(String id) {
        super(id);
    }

    public byte[] retrieveChunk(String id, long offset, int length)
            throws ProductException {
        byte[] chunk = new byte[length];
        System.arraycopy(data, (int) offset, chunk, 0, length);
        return chunk;
    }

    public void close(String id) {
    }

    /** Reads the way every caller does, and compares what came out. */
    private byte[] drainOneByteAtATime(byte[] product) throws IOException {
        data = product;
        ChunkedProductInputStream in =
                new ChunkedProductInputStream("test", this, product.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    /** The shrunk counterexample: a one-byte product containing 128. */
    public void testAHighBitByteIsNotReportedAsEndOfStream() throws Exception {
        byte[] product = new byte[] { (byte) 128 };

        assertTrue("the product was truncated at its first high-bit byte",
                Arrays.equals(product, drainOneByteAtATime(product)));
    }

    /** 0xFF is the one that reads as end of stream. */
    public void testAnFfByteIsNotReportedAsEndOfStream() throws Exception {
        byte[] product = new byte[] { (byte) 0xFF, 1, 2, 3 };

        byte[] read = drainOneByteAtATime(product);

        assertEquals("the stream stopped at the first 0xFF", 4, read.length);
        assertTrue(Arrays.equals(product, read));
    }

    /** Every byte value survives a one-at-a-time read. */
    public void testEveryByteValueSurvives() throws Exception {
        byte[] product = new byte[256];
        for (int i = 0; i < 256; i++) {
            product[i] = (byte) i;
        }

        assertTrue(Arrays.equals(product, drainOneByteAtATime(product)));
    }

    /** read() answers within the range InputStream specifies. */
    public void testReadNeverAnswersOutsideZeroToTwoFiftyFive() throws Exception {
        data = new byte[] { (byte) 0x80, (byte) 0xFF, 0x00, 0x7F };
        ChunkedProductInputStream in =
                new ChunkedProductInputStream("test", this, data.length);
        try {
            for (int i = 0; i < data.length; i++) {
                int b = in.read();
                assertTrue("read() answered " + b, b >= 0 && b <= 255);
            }
            assertEquals(-1, in.read());
        } finally {
            in.close();
        }
    }

    /** The bulk form was never affected, and still is not. */
    public void testTheArrayFormStillWorks() throws Exception {
        byte[] product = new byte[512];
        for (int i = 0; i < product.length; i++) {
            product[i] = (byte) (i % 256);
        }
        data = product;

        ChunkedProductInputStream in =
                new ChunkedProductInputStream("test", this, product.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64];
        int num;
        while ((num = in.read(buf)) != -1) {
            out.write(buf, 0, num);
        }
        in.close();

        assertTrue(Arrays.equals(product, out.toByteArray()));
    }
}
