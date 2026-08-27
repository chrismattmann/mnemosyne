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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.oodt.product.ProductException;
import org.apache.oodt.product.Retriever;

/**
 * Streaming properties for {@link LargeResult}, the result type used for
 * products too big to hold in memory.
 *
 * <p>A large result is a promise rather than a payload: it carries the product's
 * MIME type and size, and the bytes are pulled from the product server one
 * chunk at a time when a caller opens a stream on it. The single thing a caller
 * needs from that arrangement is that the bytes which come out of the stream
 * are the bytes the server holds — all of them, in order, once each. A chunk
 * boundary handled wrongly does not fail; it silently corrupts the product,
 * and the caller has no way to notice.
 *
 * <p>The retriever below is a stand-in for the product server. It is asked to
 * return fewer bytes than requested sometimes, because a chunked protocol
 * permits that and the reassembly has to cope. It is never asked to return more
 * than requested or nothing at all: the stream does not defend against either,
 * and the second in particular would spin without terminating, which is noted
 * rather than provoked.
 */
class LargeResultPropertyTest {

  /** The chunk size the streamer uses, and so the boundary worth crossing. */
  private static final int BLOCK_SIZE =
      Integer.getInteger("org.apache.oodt.xmlquery.blockSize", ChunkedProductInputStream.VAL);

  private static final String LARGE_PRODUCT = "application/vnd.jpl.large-product";

  /** A product server that holds one product and hands it out in chunks. */
  private static final class HoldingRetriever implements Retriever {
    private final byte[] product;
    private final int shortBy;
    private final List<String> closed = new ArrayList<String>();

    HoldingRetriever(byte[] product, int shortBy) {
      this.product = product;
      this.shortBy = shortBy;
    }

    public byte[] retrieveChunk(String productID, long offset, int length) throws ProductException {
      if (offset < 0 || offset > product.length) {
        throw new ProductException("asked for offset " + offset + " of " + product.length);
      }
      int available = (int) Math.min(length, product.length - offset);
      int amount = Math.max(1, available - shortBy);
      amount = Math.min(amount, available);
      return Arrays.copyOfRange(product, (int) offset, (int) offset + amount);
    }

    public void close(String productID) {
      closed.add(productID);
    }
  }

  private static Generator<String> words() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  private static Generator<String> mimeTypes() {
    return sampledFrom(
        Arrays.asList("text/plain", "image/jpeg", "application/octet-stream", "text/html"));
  }

  /**
   * Product bytes. The content is derived from a drawn seed rather than drawn
   * byte by byte, so that a counterexample stays small and readable while the
   * sizes still reach past a chunk boundary.
   */
  private static byte[] drawProduct(TestCase tc) {
    int size =
        tc.draw(
            sampledFrom(
                Arrays.asList(
                    0, 1, 2, BLOCK_SIZE - 1, BLOCK_SIZE, BLOCK_SIZE + 1, 2 * BLOCK_SIZE,
                    2 * BLOCK_SIZE + 7)),
            "size");
    long seed = tc.draw(longs(), "seed");
    byte[] product = new byte[size];
    new Random(seed).nextBytes(product);
    return product;
  }

  private static LargeResult resultFor(String id, String mimeType, byte[] product,
      Retriever retriever) {
    LargeResult result =
        new LargeResult(id, mimeType, "profile", "resource", new ArrayList<Object>(),
            product.length);
    result.setRetriever(retriever);
    return result;
  }

  private static byte[] readFully(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = in.read(buffer, 0, buffer.length)) != -1) {
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  /**
   * Reading a large result gives back exactly the bytes the product server
   * holds. Sizes either side of a chunk boundary are the interesting ones, and
   * the server is allowed to answer with less than was asked for, as a chunked
   * protocol may.
   */
  @HegelTest
  void aLargeProductStreamsBackExactlyItsBytes(TestCase tc) throws Exception {
    byte[] product = drawProduct(tc);
    int shortBy = tc.draw(integers().min(0).max(3), "shortBy");
    String id = tc.draw(words(), "id");

    HoldingRetriever retriever = new HoldingRetriever(product, shortBy);
    LargeResult result = resultFor(id, "application/octet-stream", product, retriever);

    InputStream in = result.getInputStream();
    byte[] streamed = readFully(in);
    in.close();

    assertEquals(product.length, streamed.length, "the streamed product is a different length");
    assertArrayEquals(product, streamed, "the streamed product is not the product");
    assertEquals(
        Arrays.asList(id), retriever.closed, "closing the stream did not release the product");
  }

  /**
   * Reading a byte at a time gives the same product as reading in blocks. A
   * caller wrapping the stream in a reader does the former and one copying it
   * to a file does the latter, and the two must not disagree about what the
   * product is.
   *
   * <p>The loop below is the one {@link InputStream#read()} is specified for:
   * read until the call answers -1. That works because the method is required
   * to return the byte "as an int in the range 0 to 255" and to reserve -1 for
   * the end of the stream — a byte value can therefore never be mistaken for
   * the end. A stream that returns a signed byte instead ends the loop at the
   * first {@code 0xFF} in the product, which for binary data is early and
   * arbitrary, and reports no error when it does.
   */
  @HegelTest
  void readingOneByteAtATimeGivesTheSameProduct(TestCase tc) throws Exception {
    List<Integer> bytes =
        tc.draw(lists(integers().min(0).max(255)).minSize(1).maxSize(6), "productBytes");
    byte[] product = new byte[bytes.size()];
    for (int i = 0; i < bytes.size(); ++i) {
      product[i] = (byte) bytes.get(i).intValue();
    }
    int shortBy = tc.draw(integers().min(0).max(3), "shortBy");
    String id = tc.draw(words(), "id");

    LargeResult result =
        resultFor(id, "application/octet-stream", product, new HoldingRetriever(product, shortBy));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    InputStream in = result.getInputStream();
    int b;
    while ((b = in.read()) != -1) {
      assertTrue(
          b >= 0 && b <= 255,
          "the stream answered " + b + ", which is not a byte value in the range 0 to 255");
      out.write(b);
    }
    in.close();

    assertArrayEquals(
        product, out.toByteArray(), "reading a byte at a time gave a different product");
  }

  /**
   * The deprecated whole-value accessor returns the product, read to the end.
   * It is deprecated because it cannot hold a genuinely large product in
   * memory, but callers still exist — the source says so — and what they get
   * must be the product rather than a prefix of it.
   *
   * <p>The bytes are drawn from the printable ASCII range: the accessor decodes
   * them with the platform's charset, which is a separate matter from whether
   * the right bytes were read.
   */
  @HegelTest
  void theDeprecatedValueAccessorReturnsTheWholeProduct(TestCase tc) {
    String text =
        tc.draw(
            lists(sampledFrom(Arrays.asList("a", "B", "7", " ", "-", "~")))
                .minSize(0)
                .maxSize(40)
                .map(parts -> String.join("", parts)),
            "product");
    byte[] product = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    int shortBy = tc.draw(integers().min(0).max(3), "shortBy");

    LargeResult result =
        resultFor(
            tc.draw(words(), "id"), "text/plain", product,
            new HoldingRetriever(product, shortBy));

    assertEquals(text, result.getValue(), "the whole-value accessor did not return the product");
  }

  /**
   * The stream reports what is left of the chunk it is holding and never claims
   * more than remains of the product. A caller that trusted an overstated
   * {@code available()} would block waiting for bytes that do not exist.
   */
  @HegelTest
  void theStreamNeverClaimsMoreBytesThanRemain(TestCase tc) throws Exception {
    byte[] product = drawProduct(tc);
    String id = tc.draw(words(), "id");

    LargeResult result =
        resultFor(id, "application/octet-stream", product, new HoldingRetriever(product, 0));

    InputStream in = result.getInputStream();
    byte[] buffer = new byte[7];
    int consumed = 0;
    while (true) {
      int available = in.available();
      assertTrue(
          available >= 0 && available <= product.length - consumed,
          "the stream claimed " + available + " bytes with " + (product.length - consumed)
              + " left");
      int read = in.read(buffer, 0, buffer.length);
      if (read == -1) {
        break;
      }
      consumed += read;
    }
    assertEquals(product.length, consumed, "the stream ended early");
    in.close();
  }

  /**
   * A closed stream refuses to be read rather than answering with whatever it
   * happened to be holding.
   */
  @HegelTest
  void aClosedStreamRefusesToBeRead(TestCase tc) throws Exception {
    byte[] product = drawProduct(tc);
    String id = tc.draw(words(), "id");

    LargeResult result =
        resultFor(id, "application/octet-stream", product, new HoldingRetriever(product, 0));

    InputStream in = result.getInputStream();
    in.close();

    try {
      in.read();
      throw new AssertionError("a closed stream still returned a byte");
    } catch (IOException expected) {
      assertTrue(
          expected.getMessage() != null && expected.getMessage().contains("closed"),
          "the refusal does not say the stream is closed: " + expected.getMessage());
    }
  }

  /**
   * A large result reports the product's real MIME type and size, not the ones
   * of the envelope it travels in. The envelope's own type is always
   * {@code application/vnd.jpl.large-product} — that is how a client knows to
   * stream rather than to read a value — so the real type has to be recoverable
   * from somewhere, and a client picks its decoder by it.
   */
  @HegelTest
  void aLargeResultReportsTheProductsOwnTypeAndSize(TestCase tc) {
    String id = tc.draw(words(), "id");
    String mimeType = tc.draw(mimeTypes(), "mimeType");
    long size = tc.draw(longs().min(0).max(1000000000L), "size");

    LargeResult result =
        new LargeResult(id, mimeType, "profile", "resource", new ArrayList<Object>(), size);

    assertEquals(mimeType, result.getMimeType(), "the product's MIME type was lost");
    assertEquals(size, result.getSize(), "the product's size was lost");
    assertEquals(id, result.getID(), "the result id changed");
  }

  /**
   * Wrapping a large result as a large result again changes nothing. A result
   * is promoted on the way through a federation whenever a layer decides it is
   * too big to carry inline, and a layer that promotes an already-promoted
   * result must not bury the real MIME type one level deeper each time.
   */
  @HegelTest
  void promotingAnAlreadyLargeResultChangesNothing(TestCase tc) {
    String id = tc.draw(words(), "id");
    String mimeType = tc.draw(mimeTypes(), "mimeType");
    long size = tc.draw(longs().min(0).max(1000000000L), "size");

    LargeResult once =
        new LargeResult(id, mimeType, "profile", "resource", new ArrayList<Object>(), size);
    LargeResult twice = new LargeResult(once);

    assertEquals(once.getMimeType(), twice.getMimeType(), "the MIME type moved on re-promotion");
    assertEquals(once.getSize(), twice.getSize(), "the size moved on re-promotion");
    assertEquals(once.getID(), twice.getID(), "the id moved on re-promotion");
    assertEquals(LARGE_PRODUCT, twice.mimeType, "the envelope type changed");
  }

  /**
   * A large result survives being written to a node and read back: the promise
   * — the product's type and its size — is what crosses the wire, and a client
   * reconstructs the streaming result from it.
   */
  @HegelTest
  void aLargeResultRoundTripsThroughItsNode(TestCase tc) {
    String id = tc.draw(words(), "id");
    String mimeType = tc.draw(mimeTypes(), "mimeType");
    long size = tc.draw(longs().min(0).max(1000000000L), "size");

    LargeResult original =
        new LargeResult(id, mimeType, "profile", "resource", new ArrayList<Object>(), size);

    Result plain = new Result(original.toXML(XMLQuery.createDocument()));
    LargeResult reread = new LargeResult(plain);

    assertEquals(id, reread.getID(), "the result id changed");
    assertEquals(mimeType, reread.getMimeType(), "the product's MIME type changed");
    assertEquals(size, reread.getSize(), "the product's size changed");
    assertEquals("profile", reread.getProfileID(), "the profile id changed");
    assertEquals("resource", reread.getResourceID(), "the resource id changed");
  }
}
