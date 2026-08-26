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

package org.apache.oodt.cas.pge.metadata;

import static dev.hegel.Generators.integers;
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
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of the key-link and grouping machinery in {@link PgeMetadata}: the
 * part that lets one metadata name stand for another, and the part that imports
 * another task's metadata under a namespace.
 *
 * <p>Key links are how a PGE config gives a science executable the name it
 * expects for a value the workflow calls something else, so following a link has
 * to arrive at the right key and reading through a link has to give the current
 * value of that key.
 *
 * <p>Every link graph built here is a chain that only ever points forward, which
 * is what a config file expresses. A link graph containing a cycle is not
 * generated: {@link PgeMetadata#getReferenceKeyPath} appends to a list on every
 * hop and would exhaust the heap rather than return.
 */
class PgeMetadataLinkPropertyTest {

  private static Generator<List<String>> values() {
    return lists(text().minSize(1).maxSize(6).categories("Lu", "Ll", "Nd")).minSize(1).maxSize(3);
  }

  /** A forward-only chain of link names: link0 -> link1 -> ... -> linkN. */
  private static List<String> chain(int length) {
    List<String> keys = new ArrayList<String>();
    for (int i = 0; i <= length; i++) {
      keys.add("link" + i);
    }
    return keys;
  }

  private static PgeMetadata linked(List<String> keys) {
    PgeMetadata metadata = new PgeMetadata();
    for (int i = 0; i < keys.size() - 1; i++) {
      metadata.linkKey(keys.get(i), keys.get(i + 1));
    }
    return metadata;
  }

  /**
   * Following a chain of links arrives at the one key at the end of it, and the
   * path reported is the hops taken to get there. Every name on the way is a
   * link; the destination is not.
   */
  @HegelTest
  void resolvingFollowsAChainToItsEnd(TestCase tc) {
    int length = tc.draw(integers().min(1).max(6), "chainLength");
    List<String> keys = chain(length);
    PgeMetadata metadata = linked(keys);

    String destination = keys.get(keys.size() - 1);
    for (int i = 0; i < keys.size() - 1; i++) {
      assertTrue(metadata.isLink(keys.get(i)), keys.get(i) + " is not reported as a link");
      assertEquals(destination, metadata.resolveKey(keys.get(i)), "from " + keys.get(i));
      assertEquals(
          keys.size() - 1 - i,
          metadata.getReferenceKeyPath(keys.get(i)).size(),
          "wrong path length from " + keys.get(i));
    }
    assertFalse(metadata.isLink(destination), destination + " is reported as a link");
    assertEquals(destination, metadata.resolveKey(destination));
    assertTrue(metadata.getReferenceKeyPath(destination).isEmpty());
  }

  /**
   * Reading through a link gives the current value of the key it points at.
   * This is the whole point of a link, and it has to hold through a chain of
   * them, not just one hop.
   */
  @HegelTest
  void readingThroughALinkGivesTheTargetsValue(TestCase tc) {
    int length = tc.draw(integers().min(1).max(6), "chainLength");
    List<String> updated = tc.draw(values(), "updated");

    List<String> keys = chain(length);
    PgeMetadata metadata = linked(keys);
    String destination = keys.get(keys.size() - 1);

    metadata.replaceMetadata(destination, updated);

    for (String key : keys) {
      assertEquals(updated, metadata.getAllMetadata(key), "reading " + key);
      assertEquals(updated.get(0), metadata.getMetadata(key), "reading " + key);
    }
  }

  /**
   * Writing through a link updates the key it points at rather than creating a
   * key under the link's own name - otherwise the link and its target would
   * quietly drift apart after the first write.
   */
  @HegelTest
  void writingThroughALinkUpdatesTheTarget(TestCase tc) {
    int length = tc.draw(integers().min(1).max(6), "chainLength");
    List<String> written = tc.draw(values(), "written");

    List<String> keys = chain(length);
    PgeMetadata metadata = linked(keys);
    String destination = keys.get(keys.size() - 1);

    metadata.replaceMetadata(keys.get(0), written);

    assertEquals(written, metadata.getAllMetadata(destination), "the target was not updated");
    assertEquals(written, metadata.getAllMetadata(keys.get(0)), "the link no longer reads through");
  }

  /**
   * Unlinking undoes linking. The key that was linked to is documented to be
   * left alone, so after unlinking the name is an ordinary name again and the
   * target still holds its value.
   */
  @HegelTest
  void unlinkingUndoesLinking(TestCase tc) {
    List<String> stored = tc.draw(values(), "stored");

    PgeMetadata metadata = new PgeMetadata();
    metadata.replaceMetadata("target", stored);
    metadata.linkKey("alias", "target");
    assertTrue(metadata.isLink("alias"));

    metadata.unlinkKey("alias");

    assertFalse(metadata.isLink("alias"), "alias is still a link");
    assertEquals("alias", metadata.resolveKey("alias"), "alias still resolves elsewhere");
    assertEquals(stored, metadata.getAllMetadata("target"), "the target lost its value");
  }

  /**
   * Combining the layers loses no key. Which layer wins a contested key is a
   * separate question; this property only asks that a key present in any layer
   * is present in the combined metadata, because that combined object is what
   * gets handed to the science executable.
   */
  @HegelTest
  void combiningKeepsEveryKeyFromEveryLayer(TestCase tc) {
    List<String> staticKeys = tc.draw(keys("static"), "staticKeys");
    List<String> dynamicKeys = tc.draw(keys("dynamic"), "dynamicKeys");
    List<String> localKeys = tc.draw(keys("local"), "localKeys");

    Metadata staticMetadata = metadataFor(staticKeys);
    Metadata dynamicMetadata = metadataFor(dynamicKeys);
    PgeMetadata metadata = new PgeMetadata(staticMetadata, dynamicMetadata);
    for (String key : localKeys) {
      metadata.replaceMetadata(key, "value-of-" + key);
    }

    List<String> combined = metadata.asMetadata().getAllKeys();
    for (String key : staticKeys) {
      assertTrue(combined.contains(key), "static key " + key + " was lost");
    }
    for (String key : dynamicKeys) {
      assertTrue(combined.contains(key), "dynamic key " + key + " was lost");
    }
    for (String key : localKeys) {
      assertTrue(combined.contains(key), "local key " + key + " was lost");
    }
  }

  /**
   * Importing another task's metadata under a group namespaces its local keys
   * and leaves its shared keys alone. That split is the documented contract:
   * local metadata belongs to the task it came from and must not collide with
   * the importer's own, while static and dynamic metadata is shared.
   */
  @HegelTest
  void importingUnderAGroupNamespacesOnlyTheLocalKeys(TestCase tc) {
    List<String> localKeys = tc.draw(keys("local"), "localKeys");
    List<String> staticKeys = tc.draw(keys("static"), "staticKeys");
    String group = tc.draw(integers().min(0).max(3).map(i -> "group" + i), "group");

    PgeMetadata source = new PgeMetadata(metadataFor(staticKeys), new Metadata());
    for (String key : localKeys) {
      source.replaceMetadata(key, "value-of-" + key);
    }

    PgeMetadata target = new PgeMetadata();
    target.replaceMetadata(source, group);

    for (String key : localKeys) {
      assertEquals(
          "value-of-" + key,
          target.getMetadata(group + "/" + key),
          "local key " + key + " is not readable under " + group);
    }
    for (String key : staticKeys) {
      assertEquals(
          "value-of-" + key,
          target.getMetadata(key),
          "shared key " + key + " was not imported as-is");
    }
  }

  private static Generator<List<String>> keys(String prefix) {
    return lists(integers().min(0).max(3).map(i -> prefix + "Key" + i)).maxSize(4);
  }

  private static Metadata metadataFor(List<String> keys) {
    Metadata metadata = new Metadata();
    for (String key : keys) {
      metadata.replaceMetadata(key, "value-of-" + key);
    }
    return metadata;
  }
}
