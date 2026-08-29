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

package org.apache.oodt.cas.metadata.util;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * readMagicHeader looped while the last read was not -1. A stream that answers
 * 0 to a non-zero length request leaves totalRead where it was, so the loop
 * made no progress and never ended.
 *
 * <p>
 * Every test here has a timeout: without the fix they do not fail, they hang.
 * </p>
 */
public class TestReadMagicHeaderTermination {

  @Test(timeout = 10000)
  public void astreamThatNeverYieldsAnythingTerminates() throws Exception {
    byte[] header = MimeTypeUtils.readMagicHeader(new StuckStream(), 8);

    assertEquals(0, header.length);
  }

  /** Some bytes, then nothing: what was read is returned. */
  @Test(timeout = 10000)
  public void astreamThatStopsPartWayTerminates() throws Exception {
    byte[] header = MimeTypeUtils.readMagicHeader(new StallingStream(3), 8);

    assertArrayEquals(new byte[] {1, 1, 1}, header);
  }

  @Test(timeout = 10000)
  public void ashortStreamStillReturnsWhatItHad() throws Exception {
    byte[] header = MimeTypeUtils.readMagicHeader(
        new ByteArrayInputStream(new byte[] {1, 2, 3}), 8);

    assertArrayEquals(new byte[] {1, 2, 3}, header);
  }

  @Test(timeout = 10000)
  public void afullStreamStillFillsTheHeader() throws Exception {
    byte[] header = MimeTypeUtils.readMagicHeader(
        new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}), 8);

    assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, header);
  }

  /** Answers 0 to every request without ever reaching end of stream. */
  private static class StuckStream extends InputStream {
    @Override
    public int read() {
      return 0;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      return 0;
    }
  }

  /** Yields a fixed number of bytes and then answers 0 forever. */
  private static class StallingStream extends InputStream {
    private final int available;
    private int served;

    StallingStream(int available) {
      this.available = available;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) <= 0 ? 0 : one[0];
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (served >= available) {
        return 0;
      }
      int n = Math.min(len, available - served);
      for (int i = 0; i < n; i++) {
        b[off + i] = 1;
      }
      served += n;
      return n;
    }
  }
}
