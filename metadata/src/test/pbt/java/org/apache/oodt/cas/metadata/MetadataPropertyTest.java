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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Properties of {@link Metadata}, the container every product's metadata passes
 * through.
 *
 * <p>The existing suite covers flat keys and the happy path. These properties
 * cover the contracts the class claims by implementing {@code equals} and
 * {@code hashCode}, and the expectation that reading a container does not
 * change it.
 *
 * <p>Keys here deliberately exclude {@code '/'}, which {@code Metadata} treats
 * as a group separator, so that every generated key is a plain root-level key
 * with no nesting semantics in play.
 */
class MetadataPropertyTest {

  /** Plain keys: no '/', non-empty, so no group-path parsing is involved. */
  private static Generator<String> keys() {
    return text().minSize(1).maxSize(12).excludeCharacters("/");
  }

  private static Generator<String> values() {
    return text().maxSize(12);
  }

  /** A list of key/value pairs, replayed into a fresh Metadata. */
  private static List<String[]> drawPairs(TestCase tc, String label) {
    List<String> ks = tc.draw(lists(keys()).minSize(1).maxSize(8), label + "Keys");
    List<String> vs = tc.draw(lists(values()).minSize(ks.size()).maxSize(ks.size()), label + "Values");
    List<String[]> pairs = new ArrayList<>();
    for (int i = 0; i < ks.size(); i++) {
      pairs.add(new String[] {ks.get(i), vs.get(i)});
    }
    return pairs;
  }

  private static Metadata build(List<String[]> pairs) {
    Metadata m = new Metadata();
    for (String[] p : pairs) {
      m.addMetadata(p[0], p[1]);
    }
    return m;
  }

  /** What went in comes back out. */
  @HegelTest
  void addedKeysAreReadableAndListed(TestCase tc) {
    String key = tc.draw(keys(), "key");
    String value = tc.draw(values(), "value");

    Metadata m = new Metadata();
    m.addMetadata(key, value);

    assertTrue(m.containsKey(key), "containsKey false for a key just added");
    assertEquals(value, m.getMetadata(key));
    assertTrue(m.getAllKeys().contains(key), "getAllKeys omits a key just added");
  }

  /**
   * The {@code equals}/{@code hashCode} contract: two containers that compare
   * equal must produce the same hash code. Anything putting Metadata in a
   * HashSet or using it as a HashMap key depends on this.
   */
  @HegelTest
  void equalMetadataHaveEqualHashCodes(TestCase tc) {
    List<String[]> pairs = drawPairs(tc, "");

    Metadata a = build(pairs);
    Metadata b = build(pairs);

    assertTrue(a.equals(b), "two containers built from identical input are not equal");
    assertEquals(a.hashCode(), b.hashCode(), "equal containers have different hash codes");
  }

  /**
   * Equality must not depend on the order distinct keys were inserted in.
   *
   * <p>The keys are deduplicated first on purpose. Adding the same key twice
   * makes it multi-valued, and the order of a key's values is meaningful, so
   * reversing that would not be the same container.
   */
  @HegelTest
  void equalityIgnoresInsertionOrderOfDistinctKeys(TestCase tc) {
    List<String[]> pairs = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (String[] p : drawPairs(tc, "")) {
      if (seen.add(p[0])) {
        pairs.add(p);
      }
    }

    List<String[]> reversed = new ArrayList<>(pairs);
    java.util.Collections.reverse(reversed);

    assertTrue(
        build(pairs).equals(build(reversed)),
        "same key/value content compares unequal when distinct keys were inserted in a different order");
  }

  /**
   * Reading is not writing. Asking for the keys of a group that was never added
   * must not bring that group into existence.
   */
  @HegelTest
  void readingAGroupDoesNotCreateIt(TestCase tc) {
    String absent = tc.draw(keys(), "absentGroup");

    Metadata m = new Metadata();
    assertFalse(m.containsGroup(absent), "precondition: group should not exist yet");

    m.getKeys(absent);

    assertFalse(m.containsGroup(absent), "reading the keys of an absent group created it");
  }

  /** Adding a key and removing it again leaves the container as it was. */
  @HegelTest
  void addThenRemoveRestoresTheContainer(TestCase tc) {
    String key = tc.draw(keys(), "key");
    String value = tc.draw(values(), "value");

    Metadata m = new Metadata();
    m.addMetadata(key, value);
    m.removeMetadata(key);

    assertFalse(m.containsKey(key), "key still present after removeMetadata");
    assertTrue(m.getAllKeys().isEmpty(), "container not empty after removing the only key");
  }

  /** Replacing is idempotent: doing it twice is the same as doing it once. */
  @HegelTest
  void replaceIsIdempotent(TestCase tc) {
    String key = tc.draw(keys(), "key");
    String first = tc.draw(values(), "first");
    String second = tc.draw(values(), "second");

    Metadata once = new Metadata();
    once.addMetadata(key, first);
    once.replaceMetadata(key, second);

    Metadata twice = new Metadata();
    twice.addMetadata(key, first);
    twice.replaceMetadata(key, second);
    twice.replaceMetadata(key, second);

    assertEquals(once.getAllMetadata(key), twice.getAllMetadata(key));
  }
}
