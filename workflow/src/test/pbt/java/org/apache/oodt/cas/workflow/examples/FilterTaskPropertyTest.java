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

package org.apache.oodt.cas.workflow.examples;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * Properties of {@link FilterTask}, the task that reshapes a workflow's shared
 * metadata between steps.
 *
 * <p>It is configured entirely by property names: anything called
 * {@code Rename_X} renames the key X, and {@code Remove_Key} lists keys to
 * drop. Everything downstream of it in a workflow reads the context it leaves
 * behind, so what it does to keys nobody mentioned matters as much as what it
 * does to the ones that were.
 */
class FilterTaskPropertyTest {

  /** A small alphabet of metadata keys, so that mentioned and present keys collide. */
  private static final List<String> KEYS = List.of("A", "B", "C", "D");

  /** Where a renamed key lands. Outside the alphabet, so renames cannot collide. */
  private static String renamedTo(String key) {
    return "Renamed" + key;
  }

  private static Set<String> drawKeys(TestCase tc, String label, int max) {
    int count = tc.draw(integers().min(0).max(max), label + "Count");
    Set<String> drawn = new LinkedHashSet<>();
    for (int i = 0; i < count; i++) {
      drawn.add(tc.draw(sampledFrom(KEYS), label + i));
    }
    return drawn;
  }

  private static Metadata metadataWith(Set<String> keys) {
    Metadata metadata = new Metadata();
    for (String key : keys) {
      metadata.addMetadata(key, "value-of-" + key);
    }
    return metadata;
  }

  /**
   * A renamed key's values arrive under the new name and are gone from the old
   * one. The point of the rename is that a later task reads a name it knows,
   * so a rename that copied rather than moved would leave the old name for
   * something else to trip over.
   */
  @HegelTest
  void aRenamedKeyMovesItsValuesToTheNewName(TestCase tc) throws Exception {
    Set<String> present = drawKeys(tc, "present", 4);
    Set<String> renamed = drawKeys(tc, "renamed", 3);
    Metadata metadata = metadataWith(present);
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    for (String key : renamed) {
      config.addConfigProperty("Rename_" + key, renamedTo(key));
    }

    new FilterTask().run(metadata, config);

    for (String key : renamed) {
      if (present.contains(key)) {
        assertFalse(metadata.containsKey(key),
            key + " was renamed but is still there");
        assertEquals("value-of-" + key, metadata.getMetadata(renamedTo(key)),
            key + " did not arrive under " + renamedTo(key));
      } else {
        assertFalse(metadata.containsKey(renamedTo(key)),
            "a key that was never there arrived under " + renamedTo(key));
      }
    }
  }

  /**
   * Every key listed for removal is gone afterwards, and a key listed but not
   * present is simply not there. The list is written in a workflow file
   * against a context assembled at runtime, so it names keys that may or may
   * not have turned up.
   */
  @HegelTest
  void everyKeyListedForRemovalIsGone(TestCase tc) throws Exception {
    Set<String> present = drawKeys(tc, "present", 4);
    Set<String> removed = drawKeys(tc, "removed", 3);
    Metadata metadata = metadataWith(present);
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("Remove_Key", String.join(",", removed));

    new FilterTask().run(metadata, config);

    for (String key : removed) {
      assertFalse(metadata.containsKey(key),
          key + " was listed for removal but is still there");
    }
    for (String key : present) {
      if (!removed.contains(key)) {
        assertTrue(metadata.containsKey(key),
            key + " was not listed for removal but is gone");
      }
    }
  }

  /**
   * A key nobody mentioned keeps its value. This is the task's real promise:
   * it filters what it was told to filter and passes the rest of the context
   * through untouched.
   */
  @HegelTest
  void anUnmentionedKeyIsUntouched(TestCase tc) throws Exception {
    Set<String> present = drawKeys(tc, "present", 4);
    Set<String> renamed = drawKeys(tc, "renamed", 2);
    Set<String> removed = drawKeys(tc, "removed", 2);
    // Renaming a key and removing it in the same pass is a question about
    // which happens first; these properties are about each on its own.
    Set<String> overlap = new LinkedHashSet<>(renamed);
    overlap.retainAll(removed);
    tc.assume(overlap.isEmpty());
    Metadata metadata = metadataWith(present);
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    for (String key : renamed) {
      config.addConfigProperty("Rename_" + key, renamedTo(key));
    }
    config.addConfigProperty("Remove_Key", String.join(",", removed));

    new FilterTask().run(metadata, config);

    List<String> untouched = new ArrayList<>();
    for (String key : present) {
      if (!renamed.contains(key) && !removed.contains(key)) {
        untouched.add(key);
      }
    }
    for (String key : untouched) {
      assertEquals("value-of-" + key, metadata.getMetadata(key),
          key + " was mentioned by nothing but changed");
    }
    assertEquals(untouched.size() + countRenamedPresent(present, renamed),
        metadata.getAllKeys().size(),
        "the context ended up with keys nobody asked for: "
            + metadata.getAllKeys());
  }

  private static int countRenamedPresent(Set<String> present,
      Set<String> renamed) {
    int count = 0;
    for (String key : renamed) {
      if (present.contains(key)) {
        count++;
      }
    }
    return count;
  }

  /**
   * A task with nothing configured changes nothing. A workflow may include the
   * filter and configure it per run; on a run that configures no filtering it
   * has to be a no-op rather than an emptying.
   */
  @HegelTest
  void anUnconfiguredFilterChangesNothing(TestCase tc) throws Exception {
    Set<String> present = drawKeys(tc, "present", 4);
    Metadata metadata = metadataWith(present);

    new FilterTask().run(metadata, new WorkflowTaskConfiguration());

    assertEquals(present, new LinkedHashSet<>(metadata.getAllKeys()),
        "an unconfigured filter changed which keys are present");
    for (String key : present) {
      assertEquals("value-of-" + key, metadata.getMetadata(key),
          key + " was changed by an unconfigured filter");
    }
  }
}
