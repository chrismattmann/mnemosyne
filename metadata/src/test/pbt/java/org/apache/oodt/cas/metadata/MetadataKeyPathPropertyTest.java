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

import static dev.hegel.Generators.just;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.oneOf;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Properties of the key <em>paths</em> {@link Metadata} accepts.
 *
 * <p>{@code MetadataPropertyTest} deliberately keeps to plain root-level keys.
 * This class takes the other half: keys that contain the {@code '/'} group
 * separator, which {@code Metadata#getGroup(String, boolean)} splits with a
 * {@link java.util.StringTokenizer}. A tokenizer drops empty tokens, so several
 * distinct key strings collapse onto the same node in the tree, and one of them
 * collapses onto the root node itself.
 *
 * <p>The properties below do not assert that any particular normalisation is
 * right or wrong. They assert the two things every caller of this class relies
 * on: that what you put in can be found again by enumerating the container, and
 * that what enumeration hands back can be read.
 */
class MetadataKeyPathPropertyTest {

  /** One path segment: non-empty, and free of the group separator. */
  private static Generator<String> segments() {
    return text().minSize(1).maxSize(6).excludeCharacters("/");
  }

  /**
   * A segment that may also be the literal name of the root group. {@code root}
   * is an ordinary word — {@code root/path}, {@code root/uid} are plausible
   * metadata keys — but it is also {@code Group.ROOT_GROUP_NAME}.
   */
  private static Generator<String> segmentsOrRootName() {
    return oneOf(just("root"), segments());
  }

  /**
   * A key path whose segments may be empty, producing keys such as {@code /a},
   * {@code a//b}, {@code a/} and — when every segment is empty — the separator
   * alone, or the empty string.
   */
  private static Generator<String> pathsWithPossiblyEmptySegments() {
    return lists(oneOf(just(""), segments()))
        .minSize(1)
        .maxSize(3)
        .map(segs -> String.join("/", segs));
  }

  /** A well-formed path: one to three non-empty segments. */
  private static Generator<String> wellFormedPaths() {
    return lists(segmentsOrRootName()).minSize(1).maxSize(3).map(segs -> String.join("/", segs));
  }

  private static Generator<String> values() {
    return text().maxSize(8);
  }

  private static List<String> sorted(List<String> in) {
    List<String> out = new ArrayList<>(in);
    Collections.sort(out);
    return out;
  }

  /**
   * Quotes each element so that a counterexample containing the empty string is
   * legible: {@code [""]} and {@code []} both print as {@code []} otherwise.
   */
  private static List<String> quoted(List<String> in) {
    List<String> out = new ArrayList<>();
    for (String s : in) {
      out.add('"' + s + '"');
    }
    Collections.sort(out);
    return out;
  }

  /**
   * Storing is not the same as being able to find it again. Whatever key a
   * caller used, the value they stored has to be reachable by walking the
   * container — that walk is how {@code Metadata(Metadata)}, {@code getMap()}
   * and every serialiser in the codebase copy a container.
   */
  @HegelTest
  void everyAddedValueIsReachableByEnumeration(TestCase tc) {
    String key = tc.draw(pathsWithPossiblyEmptySegments(), "key");
    String value = tc.draw(values(), "value");

    Metadata m = new Metadata();
    m.addMetadata(key, value);
    tc.note("containsKey=" + m.containsKey(key) + " getAllKeys=" + m.getAllKeys());

    assertTrue(
        m.getAllValues().contains(value),
        "a value that was added is not reachable from getAllValues(), so any copy of this container loses it");
  }

  /**
   * The other direction: a key handed out by {@code getAllKeys()} must be a key
   * the same container will answer to. The enumerate-then-read idiom is used by
   * {@code addMetadata(Metadata)}, {@code getMap()} and
   * {@code SerializableMetadata#toXML()}, so a key that cannot be read back is
   * data that cannot be copied.
   */
  @HegelTest
  void everyEnumeratedKeyCanBeReadBack(TestCase tc) {
    List<String> paths = tc.draw(lists(wellFormedPaths()).minSize(1).maxSize(4), "paths");
    String value = tc.draw(values(), "value");

    Metadata m = new Metadata();
    for (String path : paths) {
      m.addMetadata(path, value);
    }

    for (String key : m.getAllKeys()) {
      assertTrue(
          m.containsKey(key),
          "getAllKeys() reported '" + key + "' but containsKey rejects it");
    }
  }

  /**
   * The copy constructor is enumerate-then-read spelled out: it walks
   * {@code getAllKeys()} and asks for each key's values. A container whose keys
   * do not read back cannot be copied, so this is the consequence of the
   * property above rather than a separate contract — but it is the shape the
   * failure actually takes in production code.
   */
  @HegelTest
  void aContainerCanAlwaysBeCopied(TestCase tc) {
    List<String> paths = tc.draw(lists(wellFormedPaths()).minSize(1).maxSize(4), "paths");
    String value = tc.draw(values(), "value");

    Metadata m = new Metadata();
    for (String path : paths) {
      m.addMetadata(path, value);
    }

    Metadata copy = assertDoesNotThrow(() -> new Metadata(m), "copying the container threw");
    assertEquals(
        sorted(m.getAllKeys()),
        sorted(copy.getAllKeys()),
        "the copy does not hold the same keys");
  }

  /**
   * Undoing an add must not blow up. {@code removeMetadata} is documented to
   * remove a key and says nothing about failing, so the only key it can be
   * handed is one that was added in the first place.
   */
  @HegelTest
  void removingAKeyThatWasJustAddedDoesNotThrow(TestCase tc) {
    String key = tc.draw(pathsWithPossiblyEmptySegments(), "key");
    String value = tc.draw(values(), "value");

    Metadata m = new Metadata();
    m.addMetadata(key, value);

    assertDoesNotThrow(
        () -> m.removeMetadata(key), "removeMetadata threw for a key that had just been added");
  }

  /**
   * {@code getSubMetadata} is documented to return "Metadata containing group
   * and all keys below it", so pulling out a group must not quietly drop
   * anything that was stored at or beneath it.
   */
  @HegelTest
  void subMetadataKeepsEveryValueAtOrBelowTheGroup(TestCase tc) {
    String group = tc.draw(segments(), "group");
    List<String> ownValues = tc.draw(lists(values()).maxSize(2), "ownValues");
    List<String> childNames = tc.draw(lists(segments()).maxSize(3), "childNames");
    List<String> childValues =
        tc.draw(
            lists(values()).minSize(childNames.size()).maxSize(childNames.size()), "childValues");

    Metadata m = new Metadata();
    for (String value : ownValues) {
      m.addMetadata(group, value);
    }
    for (int i = 0; i < childNames.size(); i++) {
      m.addMetadata(group + "/" + childNames.get(i), childValues.get(i));
    }

    List<String> expected = new ArrayList<>(ownValues);
    expected.addAll(childValues);

    assertEquals(
        quoted(expected),
        quoted(m.getSubMetadata(group).getAllValues()),
        "getSubMetadata dropped values stored at or below the group");
  }
}
