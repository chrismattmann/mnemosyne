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

import static dev.hegel.Generators.binary;
import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Arrays;

/**
 * Properties of {@link Base64}, whose own javadoc states the contract these
 * check: "Pass a byte array into the encode method and you'll get a byte array
 * result where all of the bytes are printable ASCII values. Pass that result
 * into decode and you'll get your original byte array."
 *
 * <p>The class is hand-rolled bit twiddling with three separate index
 * calculations, so the round trip is worth pinning down even where it holds.
 */
class Base64PropertyTest {

  /** The RFC-1521 alphabet, plus the pad character encode appends. */
  private static boolean isBase64Byte(byte b) {
    char ch = (char) (b & 0xFF);
    return (ch >= 'A' && ch <= 'Z')
        || (ch >= 'a' && ch <= 'z')
        || (ch >= '0' && ch <= '9')
        || ch == '+'
        || ch == '/'
        || ch == '=';
  }

  /**
   * Encoding then decoding gives back exactly the bytes that went in. This is
   * the whole point of the class: it is how binary payloads travel through the
   * text-only channels the rest of the codebase uses.
   */
  @HegelTest
  void decodeUndoesEncode(TestCase tc) {
    byte[] original = tc.draw(binary().minSize(1).maxSize(96), "original");

    byte[] encoded = Base64.encode(original);
    assertArrayEquals(original, Base64.decode(encoded));
  }

  /**
   * The same round trip for the three-argument form, where the caller hands
   * over a buffer and names the region of it to encode. Any region of any
   * buffer is fair game, including the empty one: a zero-length payload is an
   * ordinary thing to carry — an empty file, an empty field — and it has to
   * survive the same trip as any other.
   */
  @HegelTest
  void decodeUndoesEncodeForAnyRegionOfABuffer(TestCase tc) {
    byte[] buffer = tc.draw(binary().maxSize(48), "buffer");
    int offset = tc.draw(integers().min(0).max(buffer.length), "offset");
    int length = tc.draw(integers().min(0).max(buffer.length - offset), "length");

    byte[] region = Arrays.copyOfRange(buffer, offset, offset + length);
    assertArrayEquals(region, Base64.decode(Base64.encode(buffer, offset, length)));
  }

  /** Everything encode produces is printable ASCII, as its javadoc promises. */
  @HegelTest
  void encodeProducesOnlyBase64Characters(TestCase tc) {
    byte[] original = tc.draw(binary().minSize(1).maxSize(96), "original");

    for (byte b : Base64.encode(original)) {
      assertTrue(isBase64Byte(b), "encode emitted a non-base-64 byte: " + b);
    }
  }

  /**
   * Decoding reads its argument; it does not consume it. A caller who keeps the
   * encoded form around — to log it, to retry with it, to decode it twice — has
   * to still have it afterwards.
   */
  @HegelTest
  void decodeLeavesItsArgumentAlone(TestCase tc) {
    byte[] original = tc.draw(binary().minSize(1).maxSize(96), "original");

    byte[] encoded = Base64.encode(original);
    byte[] untouched = Arrays.copyOf(encoded, encoded.length);

    Base64.decode(encoded);

    assertArrayEquals(untouched, encoded, "decode overwrote the array it was given");
  }
}
