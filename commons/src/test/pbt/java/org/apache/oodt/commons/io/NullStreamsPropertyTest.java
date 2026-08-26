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
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Properties of the three trivial streams in this package: {@link
 * NullInputStream}, {@link NullOutputStream} and {@link CountingOutputStream}.
 *
 * <p>Nothing here is subtle, which is the point: these classes stand in for a
 * real stream in production wiring, so they have to honour the same
 * {@link java.io.InputStream}/{@link java.io.OutputStream}/{@link
 * java.io.Closeable} contracts the real stream would. A caller that swaps a
 * {@code FileOutputStream} for a {@code NullOutputStream} to disable logging
 * must not have to change how it closes it.
 *
 * <p>None of the three classes had unit tests.
 */
class NullStreamsPropertyTest {

  /**
   * An empty input stream reports end-of-file no matter how many times, and
   * through whichever read overload, a caller asks. A stream that returned
   * anything else here would feed phantom bytes to its reader.
   */
  @HegelTest
  void anEmptyStreamAlwaysReportsEndOfFile(TestCase tc) throws IOException {
    int reads = tc.draw(integers().min(1).max(20), "reads");
    int bufferSize = tc.draw(integers().min(1).max(64), "bufferSize");

    NullInputStream in = new NullInputStream();
    for (int i = 0; i < reads; i++) {
      assertEquals(-1, in.read(), "single-byte read returned data");
      assertEquals(-1, in.read(new byte[bufferSize]), "bulk read returned data");
    }
    assertEquals(0, in.available(), "an empty stream claimed bytes were available");
  }

  /**
   * {@link java.io.Closeable#close} says: "If the stream is already closed then
   * invoking this method has no effect." Callers lean on that every time they
   * close in a {@code finally} block after having closed on the happy path, and
   * try-with-resources over a stream the body already closed does exactly this.
   */
  @HegelTest
  void closingAnAlreadyClosedStreamIsHarmless(TestCase tc) throws IOException {
    int extraCloses = tc.draw(integers().min(1).max(5), "extraCloses");

    NullInputStream in = new NullInputStream();
    in.close();
    for (int i = 0; i < extraCloses; i++) {
      assertDoesNotThrow(in::close, "closing a closed NullInputStream threw");
    }

    NullOutputStream out = new NullOutputStream();
    out.close();
    for (int i = 0; i < extraCloses; i++) {
      assertDoesNotThrow(out::close, "closing a closed NullOutputStream threw");
    }
  }

  /** Reading from a closed stream is an error, not a silent end-of-file. */
  @HegelTest
  void aClosedInputStreamRefusesToBeRead(TestCase tc) throws IOException {
    int bufferSize = tc.draw(integers().min(1).max(32), "bufferSize");

    NullInputStream in = new NullInputStream();
    in.close();

    assertThrows(IOException.class, in::read);
    assertThrows(IOException.class, () -> in.read(new byte[bufferSize]));
  }

  /** A sink stream accepts any well-formed write and keeps accepting them. */
  @HegelTest
  void aSinkStreamSwallowsEveryWrite(TestCase tc) throws IOException {
    List<byte[]> chunks = tc.draw(lists(binary().maxSize(32)).maxSize(10), "chunks");

    NullOutputStream out = new NullOutputStream();
    for (byte[] chunk : chunks) {
      out.write(chunk);
      out.flush();
    }
    out.close();
  }

  /**
   * A closed sink refuses writes rather than pretending to have taken them, so
   * a caller that keeps writing after close finds out.
   */
  @HegelTest
  void aClosedSinkReportsBeingClosed(TestCase tc) throws IOException {
    byte[] data = tc.draw(binary().minSize(1).maxSize(16), "data");

    NullOutputStream out = new NullOutputStream();
    out.close();

    assertThrows(IOException.class, () -> out.write(1));
    assertThrows(IOException.class, () -> out.write(data, 0, data.length));
    assertThrows(IOException.class, out::flush);
  }

  /**
   * An out-of-range offset or length is rejected the same way
   * {@link java.io.OutputStream} rejects it, so this stub can be substituted for
   * a real sink without changing which exception a caller has to handle.
   */
  @HegelTest
  void outOfRangeSliceArgumentsAreRejected(TestCase tc) {
    byte[] data = tc.draw(binary().minSize(1).maxSize(16), "data");
    int badOffset = tc.draw(integers().min(data.length + 1).max(data.length + 64), "badOffset");
    int badLength = tc.draw(integers().min(data.length + 1).max(data.length + 64), "badLength");

    NullOutputStream out = new NullOutputStream();

    assertThrows(IndexOutOfBoundsException.class, () -> out.write(data, badOffset, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> out.write(data, 0, badLength));
    assertThrows(IndexOutOfBoundsException.class, () -> out.write(data, -1, 1));
  }

  /**
   * The count a {@link CountingOutputStream} reports is the number of bytes the
   * stream underneath it actually received. Callers use the count to report
   * transfer sizes, so a count that disagrees with the payload is a lie about
   * work done.
   */
  @HegelTest
  void theCountMatchesWhatReachedTheSink(TestCase tc) throws IOException {
    List<byte[]> chunks = tc.draw(lists(binary().maxSize(24)).maxSize(12), "chunks");
    List<Integer> modes = tc.draw(lists(integers().min(0).max(2)).maxSize(12), "modes");

    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    CountingOutputStream out = new CountingOutputStream(sink);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();

    for (int i = 0; i < chunks.size(); i++) {
      byte[] chunk = chunks.get(i);
      int mode = modes.isEmpty() ? 0 : modes.get(i % modes.size());
      if (mode == 0 || chunk.length == 0) {
        out.write(chunk);
        expected.write(chunk);
      } else if (mode == 1) {
        int off = chunk.length / 2;
        out.write(chunk, off, chunk.length - off);
        expected.write(chunk, off, chunk.length - off);
      } else {
        for (byte b : chunk) {
          out.write(b);
          expected.write(b);
        }
      }
    }
    out.flush();

    assertArrayEquals(expected.toByteArray(), sink.toByteArray());
    assertEquals(expected.size(), out.getBytesWritten(), "byte count disagrees with the payload");
  }

  /** The count only ever moves forwards, by exactly the size of each write. */
  @HegelTest
  void theCountOnlyGrows(TestCase tc) throws IOException {
    List<byte[]> chunks = tc.draw(lists(binary().maxSize(16)).maxSize(10), "chunks");

    CountingOutputStream out = new CountingOutputStream(new NullOutputStream());
    long previous = out.getBytesWritten();
    assertEquals(0L, previous, "a fresh stream claimed bytes had been written");

    for (byte[] chunk : chunks) {
      out.write(chunk);
      long now = out.getBytesWritten();
      assertEquals(previous + chunk.length, now, "count moved by the wrong amount");
      previous = now;
    }
  }
}
