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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.tuples;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.Generator;
import dev.hegel.TestCase;
import dev.hegel.Tuple2;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Properties of {@link CacheMap}, the least-recently-used map behind the
 * caches in this package.
 *
 * <p>The class documents itself as behaving "in all ways like a regular
 * {@link java.util.Map}, except that you can store only a limited number of
 * entries. After adding an entry that exceeds the size of the cache, the map
 * ejects the least recently used entry." Everything below is a restatement of
 * one clause of that sentence.
 *
 * <p>Keys are drawn from a deliberately small pool so that repeated puts of the
 * same key — the case where the cache must not grow — actually happen.
 */
class CacheMapPropertyTest {

  /** Key/value pairs a caller would push through the cache. */
  private static Generator<List<Tuple2<Integer, Integer>>> entries() {
    return lists(tuples(integers().min(0).max(7), integers().min(0).max(999))).maxSize(24);
  }

  private static String key(Tuple2<Integer, Integer> entry) {
    return "key-" + entry.value1();
  }

  /**
   * The cache never holds more than it was told it could. This is the only
   * reason to reach for a CacheMap instead of a HashMap, so a breach here is a
   * memory leak in whatever is using it.
   */
  @HegelTest
  void sizeNeverExceedsCapacity(TestCase tc) {
    int capacity = tc.draw(integers().min(0).max(8), "capacity");
    List<Tuple2<Integer, Integer>> puts = tc.draw(entries(), "puts");

    CacheMap cache = new CacheMap(capacity);
    for (Tuple2<Integer, Integer> entry : puts) {
      cache.put(key(entry), entry.value2());
      assertTrue(cache.size() <= capacity, "cache grew to " + cache.size() + " past " + capacity);
    }
  }

  /**
   * An entry the cache still admits to holding answers with the value that was
   * last written for it. A cache that hands back a stale value is worse than no
   * cache at all.
   */
  @HegelTest
  void aRetainedKeyAnswersWithItsLatestValue(TestCase tc) {
    int capacity = tc.draw(integers().min(1).max(8), "capacity");
    List<Tuple2<Integer, Integer>> puts = tc.draw(entries(), "puts");

    CacheMap cache = new CacheMap(capacity);
    Map<String, Integer> latest = new HashMap<>();
    for (Tuple2<Integer, Integer> entry : puts) {
      cache.put(key(entry), entry.value2());
      latest.put(key(entry), entry.value2());
    }

    for (Map.Entry<String, Integer> expected : latest.entrySet()) {
      if (cache.containsKey(expected.getKey())) {
        assertEquals(expected.getValue(), cache.get(expected.getKey()));
      }
    }
  }

  /**
   * Reading an entry counts as using it. Fill the cache, read the oldest entry
   * back, then add one more: the entry just read must survive and the one that
   * has now gone longest without a reader must be the one ejected. "Least
   * recently used" means nothing if a read does not refresh an entry.
   */
  @HegelTest
  void readingAnEntryDefersItsEviction(TestCase tc) {
    int capacity = tc.draw(integers().min(2).max(8), "capacity");

    CacheMap cache = new CacheMap(capacity);
    for (int i = 0; i < capacity; i++) {
      cache.put("key-" + i, i);
    }

    assertEquals(Integer.valueOf(0), cache.get("key-0"));
    cache.put("fresh", -1);

    assertTrue(cache.containsKey("key-0"), "the entry just read was evicted");
    assertFalse(cache.containsKey("key-1"), "the least recently used entry survived");
    assertTrue(cache.containsKey("fresh"), "the new entry was not stored");
  }

  /**
   * Removing every key empties the cache. The map keeps its entries in two
   * structures at once — a hash map and an ordering list — so a removal that
   * updates one and not the other leaves a phantom entry taking up capacity.
   */
  @HegelTest
  void removingEveryKeyEmptiesTheCache(TestCase tc) {
    int capacity = tc.draw(integers().min(1).max(8), "capacity");
    List<Tuple2<Integer, Integer>> puts = tc.draw(entries(), "puts");

    CacheMap cache = new CacheMap(capacity);
    for (Tuple2<Integer, Integer> entry : puts) {
      cache.put(key(entry), entry.value2());
    }
    for (int i = 0; i <= 7; i++) {
      cache.remove("key-" + i);
    }

    assertEquals(0, cache.size(), "keys remained after everything was removed");

    // And the freed capacity is genuinely free again.
    for (int i = 0; i < capacity; i++) {
      cache.put("after-" + i, i);
    }
    assertEquals(capacity, cache.size(), "the emptied cache would not refill");
  }

  /**
   * Two cache maps that disagree about what a key maps to are not equal.
   * {@link java.util.Map#equals} is defined over the mappings, and this class
   * declares itself a Map, so anything that compares two of them — a test
   * assertion, a set of caches, a change check before a write — is entitled to
   * that meaning.
   */
  @HegelTest
  void cachesThatDisagreeAboutAValueAreNotEqual(TestCase tc) {
    int capacity = tc.draw(integers().min(1).max(8), "capacity");
    int left = tc.draw(integers().min(0).max(100), "left");
    int right = tc.draw(integers().min(0).max(100), "right");
    tc.assume(left != right);

    CacheMap one = new CacheMap(capacity);
    CacheMap other = new CacheMap(capacity);
    one.put("key-0", left);
    other.put("key-0", right);

    assertFalse(one.equals(other), "caches holding different values compared equal");
  }
}
