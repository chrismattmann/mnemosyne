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
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static dev.hegel.Generators.tuples;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import dev.hegel.Tuple2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Properties of the whole-container operations on {@link Metadata}: merging one
 * container into another, merging one under a group, replacing a group, and
 * reading a group back out.
 *
 * <p>These are the operations a crawler and an ingester use to assemble a
 * product's metadata from several extractors, so "which values survive a merge
 * and in what order" is not a detail — it is the ingested record.
 *
 * <p>The single-key operations, key paths, and {@code equals}/{@code hashCode}
 * are covered by the sibling property tests; nothing here repeats them.
 *
 * <p>Keys are drawn from a small pool so that collisions between the two
 * containers being merged actually happen; that is the only interesting case.
 */
class MetadataMergePropertyTest {

  private static final List<String> KEY_POOL =
      List.of("Filename", "ProductType", "DataVersion", "Owner");

  private static Generator<String> keys() {
    return sampledFrom(KEY_POOL);
  }

  private static Generator<String> values() {
    return text().minSize(1).maxSize(6).categories("Ll", "Nd");
  }

  /** A container's worth of key/value pairs. */
  private static Generator<List<Tuple2<String, String>>> entries() {
    return lists(tuples(keys(), values())).maxSize(10);
  }

  /** A single-segment group name, so nested paths stay predictable. */
  private static Generator<String> groups() {
    return sampledFrom("GroupA", "GroupB", "Nested");
  }

  private static Metadata build(List<Tuple2<String, String>> entries) {
    Metadata metadata = new Metadata();
    for (Tuple2<String, String> entry : entries) {
      metadata.addMetadata(entry.value1(), entry.value2());
    }
    return metadata;
  }

  /**
   * Merging one container into another keeps every value from both, with the
   * receiver's values first for keys they share. Two extractors that both
   * report {@code Filename} must each be represented; dropping either loses
   * provenance the catalog is expected to hold.
   */
  @HegelTest
  void mergingKeepsEveryValueFromBothSides(TestCase tc) {
    List<Tuple2<String, String>> left = tc.draw(entries(), "left");
    List<Tuple2<String, String>> right = tc.draw(entries(), "right");

    Metadata target = build(left);
    Metadata source = build(right);

    target.addMetadata(source);

    for (String key : KEY_POOL) {
      List<String> expected = new ArrayList<>();
      expected.addAll(valuesFor(left, key));
      expected.addAll(valuesFor(right, key));
      if (expected.isEmpty()) {
        assertFalse(target.containsKey(key), "key [" + key + "] appeared from nowhere");
      } else {
        assertEquals(expected, target.getAllMetadata(key), "merged values for [" + key + "]");
      }
    }
  }

  /** Merging leaves the container that was merged from untouched. */
  @HegelTest
  void mergingDoesNotDisturbTheSource(TestCase tc) {
    List<Tuple2<String, String>> left = tc.draw(entries(), "left");
    List<Tuple2<String, String>> right = tc.draw(entries(), "right");

    Metadata target = build(left);
    Metadata source = build(right);
    Metadata snapshot = build(right);

    target.addMetadata(source);

    assertEquals(contentOf(snapshot), contentOf(source), "the source container was modified by the merge");
  }

  /**
   * Replacing overwrites rather than appends: after replacing, each key the
   * incoming container carries holds exactly the incoming values. An ingester
   * that replaces to correct a bad extraction must not be left with both the
   * bad value and the good one.
   */
  @HegelTest
  void replacingLeavesOnlyTheIncomingValues(TestCase tc) {
    List<Tuple2<String, String>> left = tc.draw(entries(), "left");
    List<Tuple2<String, String>> right = tc.draw(entries(), "right");

    Metadata target = build(left);
    Metadata source = build(right);

    target.replaceMetadata(source);

    for (String key : source.getAllKeys()) {
      assertEquals(
          source.getAllMetadata(key),
          target.getAllMetadata(key),
          "replaced values for [" + key + "]");
    }
  }

  /**
   * Replacing is idempotent — doing it twice is doing it once. Callers replace
   * defensively in retry paths.
   */
  @HegelTest
  void replacingTwiceIsReplacingOnce(TestCase tc) {
    List<Tuple2<String, String>> left = tc.draw(entries(), "left");
    List<Tuple2<String, String>> right = tc.draw(entries(), "right");

    Metadata once = build(left);
    once.replaceMetadata(build(right));
    Metadata twice = build(left);
    twice.replaceMetadata(build(right));
    twice.replaceMetadata(build(right));

    assertEquals(contentOf(once), contentOf(twice), "replacing twice differed from replacing once");
  }

  /**
   * Merging a container under a group files every one of its keys beneath that
   * group, and leaves them readable at the qualified path. This is how an
   * extractor's output is namespaced.
   */
  @HegelTest
  void mergingUnderAGroupQualifiesEveryKey(TestCase tc) {
    List<Tuple2<String, String>> entries = tc.draw(entries(), "entries");
    String group = tc.draw(groups(), "group");

    Metadata source = build(entries);
    Metadata target = new Metadata();
    target.addMetadata(group, source);

    for (String key : source.getAllKeys()) {
      assertEquals(
          source.getAllMetadata(key),
          target.getAllMetadata(group + "/" + key),
          "key [" + key + "] was not filed under [" + group + "]");
    }
    assertEquals(
        source.getAllKeys().isEmpty(),
        target.getAllKeys().isEmpty(),
        "the group merge produced the wrong number of keys");
  }

  /**
   * Reading a group back out gives the container that was filed under it, with
   * the group prefix stripped. Namespacing a container and un-namespacing it
   * must be inverse operations, or a round trip through a group renames every
   * key.
   */
  @HegelTest
  void aGroupRoundTripsThroughSubMetadata(TestCase tc) {
    List<Tuple2<String, String>> entries = tc.draw(entries(), "entries");
    String group = tc.draw(groups(), "group");

    Metadata source = build(entries);
    Metadata target = new Metadata();
    target.addMetadata(group, source);

    assertEquals(
        contentOf(source), contentOf(target.getSubMetadata(group)), "the group did not round trip");
  }

  /**
   * A group that was filed and then removed leaves nothing behind. An ingester
   * that drops an extractor's contribution must not leave its keys in the
   * record.
   */
  @HegelTest
  void removingAGroupRemovesEverythingUnderIt(TestCase tc) {
    List<Tuple2<String, String>> kept = tc.draw(entries(), "kept");
    List<Tuple2<String, String>> dropped = tc.draw(entries(), "dropped");
    String group = tc.draw(groups(), "group");

    Metadata target = build(kept);
    target.addMetadata(group, build(dropped));

    target.removeMetadataGroup(group);

    assertFalse(target.containsGroup(group), "the group survived its own removal");
    for (String key : target.getAllKeys()) {
      assertFalse(
          key.startsWith(group + "/"),
          "key [" + key + "] survived the removal of group [" + group + "]");
    }
  }

  /**
   * Values for a key come back in the order they were added, and a key with
   * more than one value says so. Callers that take the first value are relying
   * on "first" meaning "added first".
   */
  @HegelTest
  void multipleValuesKeepTheirInsertionOrder(TestCase tc) {
    String key = tc.draw(keys(), "key");
    List<String> values = tc.draw(lists(values()).minSize(1).maxSize(6), "values");

    Metadata metadata = new Metadata();
    for (String value : values) {
      metadata.addMetadata(key, value);
    }

    assertEquals(values, metadata.getAllMetadata(key), "values came back reordered");
    assertEquals(values.get(0), metadata.getMetadata(key), "the first value was not the first added");
    assertEquals(values.size() > 1, metadata.isMultiValued(key));
  }

  /**
   * A container built from a map holds what the map held. This is the bridge
   * every XML-RPC caller crosses, in both directions.
   */
  @HegelTest
  void aMapRoundTripsThroughAContainer(TestCase tc) {
    List<Tuple2<String, String>> entries = tc.draw(entries(), "entries");

    Metadata original = build(entries);
    Map<String, Object> asMap = original.getMap();

    Metadata rebuilt = new Metadata();
    rebuilt.addMetadata(new HashMap<>(asMap));

    assertEquals(
        contentOf(original), contentOf(rebuilt), "the container did not survive a trip through a map");
  }

  /**
   * The copy constructor produces an equal but independent container. Callers
   * copy precisely so they can modify without disturbing the original.
   */
  @HegelTest
  void aCopyIsEqualAndIndependent(TestCase tc) {
    List<Tuple2<String, String>> entries = tc.draw(entries(), "entries");
    String key = tc.draw(keys(), "key");
    String extra = tc.draw(values(), "extra");

    Metadata original = build(entries);
    Metadata copy = new Metadata(original);

    assertEquals(contentOf(original), contentOf(copy), "the copy did not hold what its original held");

    List<String> before = original.getAllMetadata(key);
    List<String> snapshot = before == null ? null : new ArrayList<>(before);

    copy.addMetadata(key, extra);

    assertEquals(
        snapshot, original.getAllMetadata(key), "writing to the copy reached back into the original");
    assertTrue(copy.getAllMetadata(key).contains(extra), "the copy did not take the write");
  }

  /**
   * Every key in a container mapped to its values, in insertion order.
   *
   * <p>The properties above compare containers this way rather than with
   * {@code Metadata.equals}, which compares the two key lists as ordered lists
   * and so is sensitive to the order two colliding keys happen to sit in inside
   * the backing {@code Hashtable}. That is a defect in {@code equals} itself and
   * is stated as its own property elsewhere; comparing content directly keeps
   * these properties about merge semantics.
   */
  private static Map<String, List<String>> contentOf(Metadata metadata) {
    Map<String, List<String>> content = new TreeMap<>();
    for (String key : metadata.getAllKeys()) {
      content.put(key, metadata.getAllMetadata(key));
    }
    return content;
  }

  private static List<String> valuesFor(List<Tuple2<String, String>> entries, String key) {
    List<String> values = new ArrayList<>();
    for (Tuple2<String, String> entry : entries) {
      if (entry.value1().equals(key)) {
        values.add(entry.value2());
      }
    }
    return values;
  }
}
