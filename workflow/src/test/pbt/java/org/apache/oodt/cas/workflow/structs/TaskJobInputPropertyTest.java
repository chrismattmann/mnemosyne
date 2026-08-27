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

package org.apache.oodt.cas.workflow.structs;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of {@link TaskJobInput}, the form a workflow task takes when the
 * resource manager is the one running it.
 *
 * <p>A task farmed out to the resource manager is described entirely by one of
 * these: the class to instantiate, the static configuration from the workflow
 * file and the dynamic metadata the workflow has accumulated so far. It is
 * written into a struct on this side and read back on the other, and the
 * resource manager separately asks it for a flat view of everything it knows.
 */
class TaskJobInputPropertyTest {

  /** A small alphabet of names, so that config and metadata keys collide. */
  private static final List<String> NAMES = List.of("A", "B", "C");

  private static String word(TestCase tc, String label) {
    return "v" + tc.draw(integers().min(0).max(999), label);
  }

  /** An input carrying drawn configuration, metadata and a class to run. */
  private static TaskJobInput inputOf(TestCase tc, List<String> configKeys,
      List<String> metadataKeys) {
    TaskJobInput input = new TaskJobInput();
    input.setWorkflowTaskInstanceClassName(word(tc, "instanceClass"));
    input.setId(word(tc, "id"));
    int configCount = tc.draw(integers().min(0).max(3), "configCount");
    for (int i = 0; i < configCount; i++) {
      String name = tc.draw(sampledFrom(NAMES), "configName" + i);
      configKeys.add(name);
      input.getTaskConfig().addConfigProperty(name, word(tc, "configValue" + i));
    }
    Metadata metadata = new Metadata();
    int metadataCount = tc.draw(integers().min(0).max(3), "metadataCount");
    for (int i = 0; i < metadataCount; i++) {
      String name = tc.draw(sampledFrom(NAMES), "metadataName" + i);
      metadataKeys.add(name);
      int values = tc.draw(integers().min(1).max(2), "metadataValues" + i);
      List<String> drawn = new Vector<>(values);
      for (int v = 0; v < values; v++) {
        drawn.add(word(tc, "metadataValue" + i + "x" + v));
      }
      metadata.addMetadata(name, drawn);
    }
    input.setDynMetadata(metadata);
    return input;
  }

  /**
   * An input survives being written into a struct and read back: the same
   * class runs, configured the same way, on the same metadata. That trip is
   * the whole of how a task reaches the resource manager.
   */
  @HegelTest
  void anInputSurvivesBeingWrittenAndReadBack(TestCase tc) {
    TaskJobInput before = inputOf(tc, new ArrayList<String>(),
        new ArrayList<String>());

    Object written = before.write();
    TaskJobInput after = new TaskJobInput();
    after.read(written);

    assertEquals(before.getWorkflowTaskInstanceClassName(),
        after.getWorkflowTaskInstanceClassName(),
        "a different class would be run");
    assertEquals(before.getTaskConfig().getProperties(),
        after.getTaskConfig().getProperties(), "the configuration changed");
    assertEquals(before.getDynMetadata().getAllKeys(),
        after.getDynMetadata().getAllKeys(),
        "the metadata came back under different keys");
    for (String key : before.getDynMetadata().getAllKeys()) {
      assertEquals(before.getDynMetadata().getAllMetadata(key),
          after.getDynMetadata().getAllMetadata(key),
          "the values under " + key + " changed");
    }
  }

  /**
   * Reading something that is not a struct at all leaves the input as it was.
   * The method takes an {@link Object} off the wire, so what arrives is
   * whatever the far end sent.
   */
  @HegelTest
  void readingSomethingThatIsNotAStructChangesNothing(TestCase tc) {
    List<String> configKeys = new ArrayList<>();
    TaskJobInput input = inputOf(tc, configKeys, new ArrayList<String>());
    String className = input.getWorkflowTaskInstanceClassName();
    Object notAStruct = tc.draw(sampledFrom(NAMES), "notAStruct");

    input.read(notAStruct);

    assertEquals(className, input.getWorkflowTaskInstanceClassName(),
        "the class to run was changed by a struct that was not one");
    for (String key : configKeys) {
      assertNotNull(input.getTaskConfig().getProperty(key),
          key + " was lost from the configuration");
    }
  }

  /**
   * The flat view the resource manager reads holds every configuration
   * property and every metadata value, with the metadata winning where both
   * name the same key. The resource manager knows nothing about workflows: this
   * view is all it sees, and the precedence is what lets a running workflow
   * override what the file configured.
   */
  @HegelTest
  void theFlatViewHoldsBothConfigurationAndMetadataWithMetadataWinning(
      TestCase tc) {
    List<String> configKeys = new ArrayList<>();
    List<String> metadataKeys = new ArrayList<>();
    TaskJobInput input = inputOf(tc, configKeys, metadataKeys);

    Map<String, Vector<String>> flat = input.getMetadata();

    assertNotNull(flat, "the input has no flat view at all");
    for (String key : configKeys) {
      assertTrue(flat.containsKey(key),
          key + " was configured but is not in the flat view");
      if (!metadataKeys.contains(key)) {
        assertEquals(List.of(input.getTaskConfig().getProperty(key)),
            flat.get(key), key + " came through with the wrong value");
      }
    }
    for (String key : input.getDynMetadata().getAllKeys()) {
      assertEquals(input.getDynMetadata().getAllMetadata(key), flat.get(key),
          key + " is in the metadata but the flat view disagrees");
    }
    assertEquals(countDistinct(configKeys, input.getDynMetadata()
        .getAllKeys()), flat.size(),
        "the flat view holds keys from neither source: " + flat.keySet());
  }

  private static int countDistinct(List<String> first, List<String> second) {
    List<String> all = new ArrayList<>(first);
    for (String each : second) {
      if (!all.contains(each)) {
        all.add(each);
      }
    }
    return new java.util.HashSet<>(all).size();
  }

  /**
   * An input built and never filled in is still usable: it has a configuration
   * to add properties to and metadata to add values to. Every reader builds one
   * this way.
   */
  @HegelTest
  void aFreshInputIsReadyToBeFilledIn(TestCase tc) {
    String name = tc.draw(sampledFrom(NAMES), "name");

    TaskJobInput input = new TaskJobInput();

    assertNotNull(input.getTaskConfig(), "a new input has no configuration");
    assertNotNull(input.getDynMetadata(), "a new input has no metadata");
    assertTrue(input.getMetadata().isEmpty(),
        "a new input already knows something: " + input.getMetadata());
    input.setId(name);
    assertEquals(name, input.getId(), "the id changed");
  }
}
