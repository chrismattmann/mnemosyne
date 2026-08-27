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

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Properties of {@link FixedBufferOutputStream}, whose whole contract is one
 * sentence of its own Javadoc: "For a buffer of size <var>n</var>, only the
 * last <var>n</var> bytes written are ever available."
 *
 * <p>The class is a ring buffer with two independent write paths — a
 * single-byte {@code write(int)} and a bulk {@code write(byte[], int, int)} —
 * that share the {@code start}/{@code size} cursors between them. Callers reach
 * both paths without thinking about it: {@code OutputStream#write(byte[])} and
 * anything wrapping the stream in a {@code PrintStream} or {@code Writer} mix
 * them freely. So every property below drives an interleaved sequence of both.
 *
 * <p>The class had no unit tests.
 */
class FixedBufferOutputStreamPropertyTest {

  /** One write a caller might make: either a single byte or a chunk. */
  private record Chunk(byte[] bytes, boolean single) {
    @Override
    public String toString() {
      return (single ? "write(int) x" : "write(byte[]) ") + java.util.Arrays.toString(bytes);
    }
  }

  private static Generator<Chunk> chunk() {
    return binary()
        .minSize(1)
        .maxSize(12)
        .flatMap(bytes -> booleans().map(single -> new Chunk(bytes, single)));
  }

  private static Generator<List<Chunk>> writes() {
    return lists(chunk()).maxSize(10);
  }

  /** Replay a write sequence onto the stream, returning everything that was written. */
  private static byte[] replay(FixedBufferOutputStream out, List<Chunk> chunks) throws IOException {
    ByteArrayOutputStream all = new ByteArrayOutputStream();
    for (Chunk c : chunks) {
      if (c.single()) {
        for (byte b : c.bytes()) {
          out.write(b);
          all.write(b);
        }
      } else {
        out.write(c.bytes(), 0, c.bytes().length);
        all.write(c.bytes(), 0, c.bytes().length);
      }
    }
    return all.toByteArray();
  }

  private static byte[] lastBytes(byte[] all, int n) {
    int keep = Math.min(n, all.length);
    byte[] tail = new byte[keep];
    System.arraycopy(all, all.length - keep, tail, 0, keep);
    return tail;
  }

  /**
   * The stated contract: after any sequence of writes the buffer holds the last
   * <var>n</var> bytes written, in order. This is the only reason to use the
   * class — it exists so a log tail or an error excerpt can be kept in bounded
   * memory — so a mismatch here is corrupted output, not a nicety.
   */
  @HegelTest
  void bufferHoldsTheLastBytesWritten(TestCase tc) throws IOException {
    int capacity = tc.draw(integers().min(1).max(16), "capacity");
    List<Chunk> chunks = tc.draw(writes(), "writes");

    FixedBufferOutputStream out = new FixedBufferOutputStream(capacity);
    byte[] all = replay(out, chunks);

    assertArrayEquals(lastBytes(all, capacity), out.getBuffer());
  }

  /**
   * The same contract, but reached only through bulk writes. Stated separately
   * so that the ring arithmetic on the bulk path is checked on its own, without
   * the interleaved single-byte writes getting there first.
   */
  @HegelTest
  void bulkWritesAloneRetainTheLastBytes(TestCase tc) throws IOException {
    int capacity = tc.draw(integers().min(1).max(16), "capacity");
    List<byte[]> chunks = tc.draw(lists(binary().maxSize(12)).maxSize(10), "chunks");

    FixedBufferOutputStream out = new FixedBufferOutputStream(capacity);
    ByteArrayOutputStream all = new ByteArrayOutputStream();
    for (byte[] chunk : chunks) {
      out.write(chunk, 0, chunk.length);
      all.write(chunk, 0, chunk.length);
    }

    assertArrayEquals(lastBytes(all.toByteArray(), capacity), out.getBuffer());
  }

  /** The same contract again, reached only through single-byte writes. */
  @HegelTest
  void singleByteWritesAloneRetainTheLastBytes(TestCase tc) throws IOException {
    int capacity = tc.draw(integers().min(1).max(16), "capacity");
    byte[] data = tc.draw(binary().maxSize(40), "data");

    FixedBufferOutputStream out = new FixedBufferOutputStream(capacity);
    for (byte b : data) {
      out.write(b);
    }

    assertArrayEquals(lastBytes(data, capacity), out.getBuffer());
  }

  /**
   * The buffer never grows past the size it was constructed with. A caller uses
   * this class precisely to cap memory, so unbounded growth defeats it.
   */
  @HegelTest
  void bufferNeverExceedsItsCapacity(TestCase tc) throws IOException {
    int capacity = tc.draw(integers().min(0).max(16), "capacity");
    List<Chunk> chunks = tc.draw(writes(), "writes");

    FixedBufferOutputStream out = new FixedBufferOutputStream(capacity);
    replay(out, chunks);

    assertTrue(
        out.getBuffer().length <= capacity,
        "buffer of " + out.getBuffer().length + " bytes past capacity " + capacity);
  }

  /**
   * A zero-length buffer swallows everything and stays empty. This is the
   * degenerate configuration a caller reaches by computing the size from
   * configuration, so it must not throw.
   */
  @HegelTest
  void aZeroSizedBufferStaysEmpty(TestCase tc) throws IOException {
    List<Chunk> chunks = tc.draw(writes(), "writes");

    FixedBufferOutputStream out = new FixedBufferOutputStream(0);
    replay(out, chunks);

    assertEquals(0, out.getBuffer().length);
  }

  /**
   * Writing a slice of an array is the same as writing the slice on its own —
   * the offset and length arguments must not leak neighbouring bytes into the
   * buffer.
   */
  @HegelTest
  void writingASliceIsWritingJustThatSlice(TestCase tc) throws IOException {
    int capacity = tc.draw(integers().min(1).max(16), "capacity");
    byte[] data = tc.draw(binary().minSize(1).maxSize(32), "data");
    int off = tc.draw(integers().min(0).max(data.length - 1), "off");
    int len = tc.draw(integers().min(0).max(data.length - off), "len");

    FixedBufferOutputStream whole = new FixedBufferOutputStream(capacity);
    whole.write(data, off, len);

    byte[] slice = new byte[len];
    System.arraycopy(data, off, slice, 0, len);
    FixedBufferOutputStream part = new FixedBufferOutputStream(capacity);
    part.write(slice, 0, len);

    assertArrayEquals(part.getBuffer(), whole.getBuffer());
  }

  /**
   * A closed stream rejects further writes rather than silently accepting them,
   * whichever write method the caller reaches for.
   */
  @HegelTest
  void aClosedStreamRefusesEveryWrite(TestCase tc) throws IOException {
    int capacity = tc.draw(integers().min(1).max(8), "capacity");
    byte[] data = tc.draw(binary().minSize(1).maxSize(8), "data");

    FixedBufferOutputStream out = new FixedBufferOutputStream(capacity);
    out.write(data, 0, data.length);
    out.close();

    assertThrows(IOException.class, () -> out.write(1));
    assertThrows(IOException.class, () -> out.write(data, 0, data.length));
  }

  /** A negative buffer size is rejected at construction, not at the first write. */
  @HegelTest
  void anImpossibleCapacityIsRejectedUpFront(TestCase tc) {
    int capacity = tc.draw(integers().min(Integer.MIN_VALUE).max(-1), "capacity");

    assertThrows(
        IllegalArgumentException.class, () -> new FixedBufferOutputStream(capacity));
  }
}
