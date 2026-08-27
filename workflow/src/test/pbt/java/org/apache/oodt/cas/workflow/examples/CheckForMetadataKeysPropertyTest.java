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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;

/**
 * Properties of {@link CheckForMetadataKeys}, the condition a workflow uses to
 * wait until the metadata it needs has arrived.
 *
 * <p>A condition instance is shared by every workflow that names it and is
 * asked repeatedly while the engine waits, so its answer has to depend on the
 * metadata it was handed and on nothing else. The same argument covers the two
 * constant conditions in this package, which are stated here alongside it.
 */
class CheckForMetadataKeysPropertyTest {

  /** A small alphabet of metadata keys, so that required and present keys collide. */
  private static final List<String> KEYS = List.of("A", "B", "C", "D");

  private static WorkflowConditionConfiguration configRequiring(
      List<String> keys) {
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    config.addConfigProperty("reqMetKeys", String.join(",", keys));
    return config;
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
      metadata.addMetadata(key, "aValue");
    }
    return metadata;
  }

  /**
   * The condition is met exactly when the metadata carries every key it was
   * configured to require. That is the whole of what it promises, and a task
   * guarded by it runs or waits on the answer.
   */
  @HegelTest
  void theConditionIsMetWhenEveryRequiredKeyIsPresent(TestCase tc) {
    Set<String> required = drawKeys(tc, "required", 3);
    Set<String> present = drawKeys(tc, "present", 4);
    Metadata metadata = metadataWith(present);
    // An empty list of required keys is a separate question, stated below.
    tc.assume(!required.isEmpty());

    boolean met = new CheckForMetadataKeys().evaluate(metadata,
        configRequiring(new ArrayList<>(required)));

    assertEquals(present.containsAll(required), met,
        "requiring " + required + " of metadata holding " + present
            + " answered " + met);
  }

  /**
   * Asking twice gives the same answer, and asking does not change the
   * metadata. The engine polls a condition until it passes; a condition that
   * answered differently the second time, or that edited the shared context
   * while deciding, would make the workflow's progress depend on how often it
   * was asked.
   */
  @HegelTest
  void askingIsRepeatableAndLeavesTheMetadataAlone(TestCase tc) {
    Set<String> required = drawKeys(tc, "required", 3);
    Set<String> present = drawKeys(tc, "present", 4);
    Metadata metadata = metadataWith(present);
    WorkflowConditionConfiguration config =
        configRequiring(new ArrayList<>(required));
    CheckForMetadataKeys condition = new CheckForMetadataKeys();

    boolean first = condition.evaluate(metadata, config);
    boolean second = condition.evaluate(metadata, config);
    boolean fromAnother = new CheckForMetadataKeys().evaluate(metadata, config);

    assertEquals(first, second, "the condition changed its mind");
    assertEquals(first, fromAnother,
        "a second instance of the condition disagreed with the first");
    assertEquals(present, new LinkedHashSet<>(metadata.getAllKeys()),
        "evaluating the condition changed the metadata");
  }

  /**
   * A condition that requires no keys is met by any metadata. Requiring
   * nothing is what an unconfigured condition amounts to, and a guard that
   * lists nothing to wait for has nothing to wait for.
   */
  @HegelTest
  void aConditionRequiringNothingIsMet(TestCase tc) {
    Set<String> present = drawKeys(tc, "present", 4);
    Metadata metadata = metadataWith(present);
    WorkflowConditionConfiguration empty =
        new WorkflowConditionConfiguration();

    boolean met = new CheckForMetadataKeys().evaluate(metadata, empty);

    assertTrue(met, "a condition requiring nothing was not met by metadata "
        + present);
  }

  /**
   * The two constant conditions answer their constant whatever they are given.
   * They stand in for "always run" and "never run" in workflow files and in
   * this module's own test workflows.
   */
  @HegelTest
  void theConstantConditionsIgnoreWhatTheyAreGiven(TestCase tc) {
    Set<String> present = drawKeys(tc, "present", 4);
    Metadata metadata = metadataWith(present);
    WorkflowConditionConfiguration config =
        configRequiring(new ArrayList<>(drawKeys(tc, "required", 3)));

    assertTrue(new TrueCondition().evaluate(metadata, config),
        "the always-true condition was not met");
    assertEquals(false, new FalseCondition().evaluate(metadata, config),
        "the always-false condition was met");
  }
}
