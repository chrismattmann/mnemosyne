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

package org.apache.oodt.cas.metadata;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Round-trip properties of {@link SerializableMetadata}.
 *
 * <p>Every property here compares parsed containers, never XML text: the
 * transformer's indentation and attribute order are implementation details and
 * two different serialisers may disagree about them while still carrying the
 * same data.
 *
 * <p>Keys and values are drawn from printable ASCII. That is the character set
 * real metadata lives in, and it includes the three characters this code path
 * has to reason about: {@code ' '}, {@code '+'} and {@code '%'}, which are all
 * significant to the {@link java.net.URLEncoder}/{@link java.net.URLDecoder}
 * pair that {@code toXML} and {@code XMLUtils.read} use.
 */
class SerializableMetadataPropertyTest {

  private static final String ENCODING = "UTF-8";

  private static Generator<String> keys() {
    return text().minSize(1).maxSize(8).codepoints(0x20, 0x7E).excludeCharacters("/");
  }

  /** Values, including the empty string — a metadata field that is present but blank. */
  private static Generator<String> values() {
    return text().maxSize(8).codepoints(0x20, 0x7E);
  }

  private static Generator<String> nonEmptyValues() {
    return text().minSize(1).maxSize(8).codepoints(0x20, 0x7E);
  }

  /** The container's observable content: every key it lists, mapped to its values. */
  private static Map<String, List<String>> contentOf(Metadata metadata) {
    Map<String, List<String>> content = new TreeMap<>();
    for (String key : metadata.getAllKeys()) {
      content.put(key, metadata.getAllMetadata(key));
    }
    return content;
  }

  private static void fill(Metadata metadata, List<String> keys, List<List<String>> values) {
    for (int i = 0; i < keys.size(); i++) {
      metadata.addMetadata(keys.get(i), values.get(i));
    }
  }

  private static List<String> drawKeys(TestCase tc) {
    return tc.draw(lists(keys()).minSize(1).maxSize(4), "keys");
  }

  private static List<List<String>> drawValues(TestCase tc, int size, Generator<String> value) {
    return tc.draw(
        lists(lists(value).minSize(1).maxSize(2)).minSize(size).maxSize(size), "values");
  }

  private static SerializableMetadata reload(byte[] xml, boolean useCDATA) throws Exception {
    return new SerializableMetadata(new ByteArrayInputStream(xml), ENCODING, useCDATA);
  }

  private static byte[] toXmlBytes(SerializableMetadata metadata) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    metadata.writeMetadataToXmlStream(out);
    return out.toByteArray();
  }

  /**
   * Writing a container to an XML stream and reading it back gives the same
   * container. This is the contract the class exists for: it is how metadata
   * crosses a process boundary in OODT.
   */
  @HegelTest
  void xmlStreamRoundTripPreservesEveryKeyAndValue(TestCase tc) throws Exception {
    List<String> keys = drawKeys(tc);
    List<List<String>> values = drawValues(tc, keys.size(), values());

    SerializableMetadata original = new SerializableMetadata(ENCODING, false);
    fill(original, keys, values);

    SerializableMetadata reloaded = reload(toXmlBytes(original), false);

    assertEquals(
        contentOf(original), contentOf(reloaded), "content changed across an XML round trip");
  }

  /**
   * {@code useCDATA} is documented as a choice about how element text is
   * wrapped, not about what the text means. Turning it on must not change the
   * data that survives a round trip.
   */
  @HegelTest
  void cdataRoundTripAgreesWithTheEncodedRoundTrip(TestCase tc) throws Exception {
    List<String> keys = drawKeys(tc);
    List<List<String>> values = drawValues(tc, keys.size(), nonEmptyValues());

    SerializableMetadata encoded = new SerializableMetadata(ENCODING, false);
    fill(encoded, keys, values);
    SerializableMetadata cdata = new SerializableMetadata(ENCODING, true);
    fill(cdata, keys, values);

    Map<String, List<String>> viaEncoding = contentOf(reload(toXmlBytes(encoded), false));
    Map<String, List<String>> viaCdata = contentOf(reload(toXmlBytes(cdata), true));

    assertEquals(viaEncoding, viaCdata, "the useCDATA flag changed the data, not just its wrapping");
  }

  /**
   * The same round trip through Java serialisation, which is the reason this
   * subclass exists. The encoding and the CDATA flag are part of the object's
   * state and have to survive too.
   *
   * <p>Values here are non-empty on purpose: the empty-value case is already
   * pinned down by {@code xmlStreamRoundTripPreservesEveryKeyAndValue}, and
   * keeping it out of this property leaves it testing the
   * {@code writeObject}/{@code readObject} wiring rather than re-reporting the
   * same defect.
   */
  @HegelTest
  void javaSerializationRoundTripPreservesContentAndSettings(TestCase tc) throws Exception {
    List<String> keys = drawKeys(tc);
    List<List<String>> values = drawValues(tc, keys.size(), nonEmptyValues());

    SerializableMetadata original = new SerializableMetadata(ENCODING, false);
    fill(original, keys, values);

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(original);
    }

    SerializableMetadata reloaded;
    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      reloaded = (SerializableMetadata) in.readObject();
    }

    assertEquals(original.getEncoding(), reloaded.getEncoding(), "encoding lost in serialisation");
    assertEquals(
        original.isUsingCDATA(), reloaded.isUsingCDATA(), "useCDATA flag lost in serialisation");
    assertEquals(
        contentOf(original), contentOf(reloaded), "content changed across Java serialisation");
  }
}
