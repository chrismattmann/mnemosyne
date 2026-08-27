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

import static dev.hegel.Generators.binary;
import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Properties of {@link Base64EncodingOutputStream} and
 * {@link Base64DecodingInputStream}, the streaming half of this package's
 * base-64 support.
 *
 * <p>The two classes are a matched pair: the Javadoc of each says to wrap it
 * around another stream and the bytes are converted on the way through. So the
 * property that matters is that the pair composes to the identity — an
 * attachment written through the encoder and read back through the decoder is
 * the attachment.
 *
 * <p>Beyond that, the decoder is an {@link java.io.InputStream} and callers
 * hand it to code that was written against that interface, so it has to keep
 * that interface's promises about what a read returns and consumes.
 *
 * <p>Neither class had unit tests.
 */
class Base64StreamsPropertyTest {

  /**
   * Encode a payload the way a caller would: write it, then close.
   *
   * <p>Zero-length writes are skipped here rather than issued, because the
   * encoder rejects them; that deviation is stated as its own property below
   * instead of tripping every round trip.
   */
  private static byte[] encode(byte[] data, boolean flushHalfway) throws IOException {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    Base64EncodingOutputStream out = new Base64EncodingOutputStream(sink);
    int half = data.length / 2;
    if (half > 0) {
      out.write(data, 0, half);
    }
    if (flushHalfway) {
      out.flush();
    }
    if (data.length - half > 0) {
      out.write(data, half, data.length - half);
    }
    out.close();
    return sink.toByteArray();
  }

  private static Base64DecodingInputStream decoderOver(byte[] encoded) {
    return new Base64DecodingInputStream(new ByteArrayInputStream(encoded));
  }

  /** Drain a stream one byte at a time. */
  private static byte[] drainByByte(Base64DecodingInputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      out.write(b);
    }
    return out.toByteArray();
  }

  /**
   * A payload written through the encoder and read back through the decoder is
   * the payload. This is the entire purpose of the pair.
   */
  @HegelTest
  void thePairComposesToTheIdentity(TestCase tc) throws IOException {
    byte[] data = tc.draw(binary().maxSize(400), "data");

    byte[] encoded = encode(data, false);

    assertArrayEquals(data, drainByByte(decoderOver(encoded)), "the payload changed in transit");
  }

  /**
   * The same, when the caller flushed part way through. Flushing is how a
   * caller pushes a message boundary onto the wire, and it must not corrupt
   * what follows it.
   */
  @HegelTest
  void flushingMidStreamDoesNotCorruptThePayload(TestCase tc) throws IOException {
    byte[] data = tc.draw(binary().maxSize(400), "data");

    byte[] encoded = encode(data, true);

    assertArrayEquals(
        data, drainByByte(decoderOver(encoded)), "flushing part way through changed the payload");
  }

  /**
   * The encoder's output is base-64 text and nothing else — no stray bytes
   * outside the alphabet, so the result is safe to put in an XML element or an
   * HTTP header.
   */
  @HegelTest
  void theEncodedFormIsBase64Text(TestCase tc) throws IOException {
    byte[] data = tc.draw(binary().maxSize(200), "data");

    for (byte b : encode(data, false)) {
      char c = (char) (b & 0xff);
      assertTrue(
          Character.isLetterOrDigit(c) || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r',
          "byte " + (b & 0xff) + " is not base-64 text");
    }
  }

  /**
   * Reading in chunks gives the same bytes as reading one at a time. Callers
   * copy through a buffer, and a stream whose two read overloads disagree
   * corrupts every such copy.
   */
  @HegelTest
  void bulkReadsAgreeWithSingleByteReads(TestCase tc) throws IOException {
    byte[] data = tc.draw(binary().maxSize(300), "data");
    List<Integer> chunkSizes =
        tc.draw(lists(integers().min(1).max(64)).minSize(1).maxSize(8), "chunkSizes");

    byte[] encoded = encode(data, false);

    ByteArrayOutputStream bulk = new ByteArrayOutputStream();
    Base64DecodingInputStream in = decoderOver(encoded);
    int chunk = 0;
    while (true) {
      int size = chunkSizes.get(chunk++ % chunkSizes.size());
      byte[] target = new byte[size];
      int got = in.read(target, 0, size);
      if (got == -1) {
        break;
      }
      bulk.write(target, 0, got);
    }

    assertArrayEquals(data, bulk.toByteArray(), "chunked reading lost or changed bytes");
  }

  /**
   * {@link java.io.InputStream#read(byte[], int, int)}: "If len is zero, then
   * no bytes are read and 0 is returned." Copy loops written against that
   * sentence — including the JDK's own {@code transferTo} and
   * {@code readNBytes} — will lose a byte per zero-length call against a
   * stream that consumes one anyway.
   */
  @HegelTest
  void aZeroLengthReadConsumesNothing(TestCase tc) throws IOException {
    byte[] data = tc.draw(binary().minSize(1).maxSize(64), "data");
    int scratchSize = tc.draw(integers().min(1).max(8), "scratchSize");

    Base64DecodingInputStream in = decoderOver(encode(data, false));

    assertEquals(0, in.read(new byte[scratchSize], 0, 0), "a zero-length read returned bytes");
    assertArrayEquals(data, drainByByte(in), "a zero-length read swallowed a byte");
  }

  /**
   * Skipping ahead lands where it says it landed: the bytes read after a skip
   * are the bytes that follow the ones skipped.
   */
  @HegelTest
  void skippingLandsWhereItSaysItDid(TestCase tc) throws IOException {
    byte[] data = tc.draw(binary().minSize(1).maxSize(200), "data");
    int skip = tc.draw(integers().min(0).max(data.length), "skip");

    Base64DecodingInputStream in = decoderOver(encode(data, false));
    long skipped = in.skip(skip);

    assertEquals(skip, skipped, "the stream skipped a different number of bytes than it claimed");

    byte[] rest = new byte[data.length - skip];
    System.arraycopy(data, skip, rest, 0, rest.length);
    assertArrayEquals(rest, drainByByte(in), "the bytes after the skip were the wrong ones");
  }

  /** An empty payload encodes to nothing and decodes straight back to nothing. */
  @HegelTest
  void anEmptyPayloadRoundTrips(TestCase tc) throws IOException {
    boolean flush = tc.draw(booleans(), "flush");

    byte[] encoded = encode(new byte[0], flush);

    assertEquals(0, encoded.length, "an empty payload produced output");
    assertEquals(-1, decoderOver(encoded).read(), "an empty stream did not report end of file");
  }

  /**
   * Writing nothing writes nothing. {@link java.io.OutputStream#write(byte[],
   * int, int)} accepts a zero length at any in-range offset, and a copy loop
   * that has drained its source hands exactly that — an empty array, offset
   * zero, length zero — to whatever stream it was given. Rejecting it makes
   * this encoder unusable as a drop-in for a plain {@code OutputStream}.
   */
  @HegelTest
  void writingNothingIsAllowed(TestCase tc) throws IOException {
    byte[] data = tc.draw(binary().maxSize(16), "data");
    int offset = tc.draw(integers().min(0).max(data.length), "offset");

    Base64EncodingOutputStream out = new Base64EncodingOutputStream(new ByteArrayOutputStream());

    out.write(data, offset, 0);

    out.close();
  }

  /** Closed streams refuse to be used again rather than answering with nonsense. */
  @HegelTest
  void closedStreamsRefuseToBeUsed(TestCase tc) throws IOException {
    byte[] data = tc.draw(binary().minSize(1).maxSize(32), "data");

    Base64EncodingOutputStream out = new Base64EncodingOutputStream(new ByteArrayOutputStream());
    out.write(data, 0, data.length);
    out.close();
    assertThrows(IOException.class, () -> out.write(0));
    assertThrows(IOException.class, () -> out.write(data, 0, data.length));

    Base64DecodingInputStream in = decoderOver(encode(data, false));
    in.close();
    assertThrows(IOException.class, in::read);
    assertThrows(IOException.class, () -> in.skip(1));
  }
}
