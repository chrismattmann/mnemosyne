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

import static dev.hegel.Generators.binary;
import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Properties of the two static helpers in {@link MimeTypeUtils} that need no
 * mime-type repository: {@code cleanMimeType} and {@code readMagicHeader}.
 *
 * <p>The instance methods are left alone here — constructing the class loads a
 * mime-type repository, and the resolution methods stat and sniff the product
 * file.
 *
 * <p>{@code cleanMimeType} exists to reduce a {@code Content-Type} header down
 * to just the type and subtype, so that a value the catalog stores can be
 * compared against another. Anything it leaves behind defeats that comparison.
 *
 * <p>The class had no unit tests.
 */
class MimeTypeUtilsPropertyTest {

  /** Type/subtype pairs of the shape a real header carries. */
  private static Generator<String> baseTypes() {
    return fromRegex("(text|image|application|video)/[a-z]{2,8}");
  }

  /** Parameters a server appends after the type. */
  private static Generator<String> parameters() {
    return sampledFrom(
        "charset=UTF-8", " charset=UTF-8", "boundary=xyz", " q=0.9", "", " ", "level=1");
  }

  /**
   * Cleaning a content type leaves no parameter separator behind, so two
   * headers that name the same type compare equal after cleaning however many
   * parameters — including none at all — each of them carried.
   */
  @HegelTest
  void cleaningRemovesEveryParameter(TestCase tc) {
    String base = tc.draw(baseTypes(), "base");
    List<String> parameters = tc.draw(lists(parameters()).maxSize(3), "parameters");

    StringBuilder header = new StringBuilder(base);
    for (String parameter : parameters) {
      header.append(';').append(parameter);
    }

    String cleaned = MimeTypeUtils.cleanMimeType(header.toString());

    assertFalse(
        cleaned.contains(";"),
        "[" + header + "] cleaned to [" + cleaned + "], which still carries a parameter");
    assertEquals(base, cleaned, "[" + header + "] did not clean to its base type");
  }

  /** Cleaning a type that has nothing to clean leaves it exactly as it was. */
  @HegelTest
  void cleaningABareTypeChangesNothing(TestCase tc) {
    String base = tc.draw(baseTypes(), "base");

    assertEquals(base, MimeTypeUtils.cleanMimeType(base));
  }

  /** Cleaning is idempotent — a value cleaned twice is the value cleaned once. */
  @HegelTest
  void cleaningIsIdempotent(TestCase tc) {
    String base = tc.draw(baseTypes(), "base");
    String parameter = tc.draw(parameters(), "parameter");
    String header = base + ";" + parameter;

    String once = MimeTypeUtils.cleanMimeType(header);

    assertEquals(once, MimeTypeUtils.cleanMimeType(once), "cleaning [" + header + "] twice differed");
  }

  /** An absent content type stays absent rather than becoming a crash. */
  @HegelTest
  void anAbsentTypeStaysAbsent(TestCase tc) {
    tc.draw(baseTypes(), "unused");

    assertEquals(null, MimeTypeUtils.cleanMimeType(null));
  }

  /**
   * The magic header is the first bytes of the stream, and no more of them than
   * were asked for. Type detection is run against this prefix, so extra or
   * missing bytes are a mis-identified product.
   */
  @HegelTest
  void theMagicHeaderIsThePrefixOfTheStream(TestCase tc) throws IOException {
    byte[] content = tc.draw(binary().maxSize(300), "content");
    int wanted = tc.draw(integers().min(0).max(400), "wanted");
    int chunk = tc.draw(integers().min(1).max(32), "chunk");

    byte[] header = MimeTypeUtils.readMagicHeader(dribbling(content, chunk), wanted);

    int expectedLength = Math.min(wanted, content.length);
    byte[] expected = new byte[expectedLength];
    System.arraycopy(content, 0, expected, 0, expectedLength);

    assertArrayEquals(expected, header, "the magic header was not the prefix of the stream");
  }

  /**
   * A stream that hands over its bytes a few at a time gives the same header as
   * one that hands them over all at once. Any real stream — a socket, a
   * decompressor — is the first kind.
   */
  @HegelTest
  void aDribblingStreamGivesTheSameHeader(TestCase tc) throws IOException {
    byte[] content = tc.draw(binary().minSize(1).maxSize(200), "content");
    int chunk = tc.draw(integers().min(1).max(16), "chunk");

    assertArrayEquals(
        MimeTypeUtils.readMagicHeader(new ByteArrayInputStream(content)),
        MimeTypeUtils.readMagicHeader(dribbling(content, chunk)),
        "a stream that dribbled its bytes produced a different header");
  }

  /** A missing stream is rejected with an argument error, not a null dereference. */
  @HegelTest
  void aMissingStreamIsRejected(TestCase tc) {
    int wanted = tc.draw(integers().min(0).max(64), "wanted");

    assertThrows(
        IllegalArgumentException.class, () -> MimeTypeUtils.readMagicHeader(null, wanted));
  }

  /** A stream that never returns more than {@code chunk} bytes from one read. */
  private static InputStream dribbling(byte[] content, int chunk) {
    return new ByteArrayInputStream(content) {
      @Override
      public synchronized int read(byte[] b, int off, int len) {
        return super.read(b, off, Math.min(len, chunk));
      }
    };
  }
}
