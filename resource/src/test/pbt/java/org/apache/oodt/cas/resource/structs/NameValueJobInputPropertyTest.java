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

package org.apache.oodt.cas.resource.structs;

import static dev.hegel.Generators.maps;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Map;
import java.util.Vector;

/**
 * Properties of the name/value job input in {@link NameValueJobInput}.
 *
 * <p>The class had no unit tests. It is the input a job is actually run with:
 * it goes onto the wire through {@link #write()}, comes back through
 * {@link #read(Object)}, and is read out again as metadata by the job queue
 * when it decides which jobs to promote. Each of those is a contract a caller
 * relies on, and each is stated here over ordinary string name/value pairs,
 * which is all this class accepts.
 */
class NameValueJobInputPropertyTest {

  /** Names and values as a job definition file would carry them. */
  private static Generator<Map<String, String>> pairs() {
    return maps(
            text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd"),
            text().minSize(0).maxSize(10).categories("Lu", "Ll", "Nd"))
        .maxSize(8);
  }

  private static NameValueJobInput inputOf(Map<String, String> pairs) {
    NameValueJobInput in = new NameValueJobInput();
    for (Map.Entry<String, String> e : pairs.entrySet()) {
      in.setNameValuePair(e.getKey(), e.getValue());
    }
    return in;
  }

  /** Whatever was set can be read back under the same name. */
  @HegelTest
  void everyPairSetCanBeReadBack(TestCase tc) {
    Map<String, String> pairs = tc.draw(pairs(), "pairs");
    NameValueJobInput in = inputOf(pairs);

    for (Map.Entry<String, String> e : pairs.entrySet()) {
      assertEquals(e.getValue(), in.getValue(e.getKey()));
    }
  }

  /** A name that was never set has no value. */
  @HegelTest
  void anUnsetNameHasNoValue(TestCase tc) {
    Map<String, String> pairs = tc.draw(pairs(), "pairs");
    String absent = tc.draw(text().minSize(1).maxSize(10), "absent");
    tc.assume(!pairs.containsKey(absent));

    assertNull(inputOf(pairs).getValue(absent));
  }

  /**
   * The input survives the round trip onto the wire and back: a job executed
   * remotely must see the same input the submitter gave it.
   */
  @HegelTest
  void inputSurvivesTheWire(TestCase tc) {
    Map<String, String> pairs = tc.draw(pairs(), "pairs");
    NameValueJobInput sent = inputOf(pairs);

    NameValueJobInput received = new NameValueJobInput();
    received.read(sent.write());

    assertEquals(sent.getProps(), received.getProps());
    for (Map.Entry<String, String> e : pairs.entrySet()) {
      assertEquals(e.getValue(), received.getValue(e.getKey()));
    }
  }

  /**
   * Reading something that is not a map leaves the input untouched. The method
   * is explicitly written to ignore such an argument, and a caller that
   * mis-typed a wire value must not silently lose the input it already had.
   */
  @HegelTest
  void readingANonMapChangesNothing(TestCase tc) {
    Map<String, String> pairs = tc.draw(pairs(), "pairs");
    NameValueJobInput in = inputOf(pairs);

    in.read(tc.draw(text().maxSize(10), "notAMap"));

    for (Map.Entry<String, String> e : pairs.entrySet()) {
      assertEquals(e.getValue(), in.getValue(e.getKey()));
    }
  }

  /** Configuring with no properties leaves the existing ones in place. */
  @HegelTest
  void configuringWithNullKeepsTheExistingProperties(TestCase tc) {
    Map<String, String> pairs = tc.draw(pairs(), "pairs");
    NameValueJobInput in = inputOf(pairs);

    in.configure(null);

    assertEquals(pairs.size(), in.getProps().size());
  }

  /** The input id is a constant: it identifies the kind of input, not one. */
  @HegelTest
  void theIdDoesNotDependOnTheContents(TestCase tc) {
    Map<String, String> pairs = tc.draw(pairs(), "pairs");

    assertEquals(new NameValueJobInput().getId(), inputOf(pairs).getId());
  }

  /**
   * The metadata view exposes each name/value pair under its own name.
   *
   * <p>This is what {@code FifoMappedJobQueue.promoteKeyValPair} looks a job up
   * by: an operator promoting every job whose {@code ProductType} is
   * {@code GenericFile} is asking this map for the key {@code ProductType}. If
   * the pairs a job was built with are not the pairs this map is keyed by,
   * the promotion matches the wrong jobs, or none.
   */
  @HegelTest
  void metadataIsKeyedByTheNameOfEachPair(TestCase tc) {
    Map<String, String> pairs = tc.draw(pairs(), "pairs");
    tc.assume(!pairs.isEmpty());
    NameValueJobInput in = inputOf(pairs);

    Map<String, Vector<String>> met = in.getMetadata();

    for (Map.Entry<String, String> e : pairs.entrySet()) {
      Vector<String> values = met.get(e.getKey());
      assertNotNull(values, "no metadata under the name '" + e.getKey() + "'");
      assertEquals(e.getValue(), values.get(0));
    }
  }
}
