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

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * Properties of {@link NumIncrementTask}, the task the module's own looping
 * workflows use to make progress.
 *
 * <p>It is the smallest example of a task that communicates by editing the
 * shared metadata context, and the workflows that use it loop until the number
 * reaches a bound, so how much it moves the number on each run is the whole of
 * its behaviour.
 */
class NumIncrementTaskPropertyTest {

  private static final WorkflowTaskConfiguration NO_CONFIG =
      new WorkflowTaskConfiguration();

  private static Metadata metadataWith(String num, String otherKey,
      String otherValue) {
    Metadata metadata = new Metadata();
    if (num != null) {
      metadata.addMetadata("num", num);
    }
    metadata.addMetadata(otherKey, otherValue);
    return metadata;
  }

  /**
   * Running the task once adds one to the number, and running it again adds
   * one more. A workflow that loops on this task counts its passes by the
   * number, so a run that moved it by anything but one would miscount.
   */
  @HegelTest
  void eachRunAddsOneToTheNumber(TestCase tc) {
    int start = tc.draw(integers().min(-1000).max(1000), "start");
    int runs = tc.draw(integers().min(0).max(5), "runs");
    Metadata metadata = metadataWith(String.valueOf(start), "other", "value");
    NumIncrementTask task = new NumIncrementTask();

    for (int i = 0; i < runs; i++) {
      task.run(metadata, NO_CONFIG);
    }

    assertEquals(String.valueOf(start + runs), metadata.getMetadata("num"),
        runs + " runs from " + start + " left " + metadata.getMetadata("num"));
  }

  /**
   * The task touches nothing but the number. It runs against the workflow's
   * shared context, which every other task in the workflow is reading.
   */
  @HegelTest
  void nothingButTheNumberIsTouched(TestCase tc) {
    int start = tc.draw(integers().min(-1000).max(1000), "start");
    String otherKey = tc.draw(sampledFrom(List.of("other", "num2", "N")),
        "otherKey");
    Metadata metadata = metadataWith(String.valueOf(start), otherKey, "value");

    new NumIncrementTask().run(metadata, NO_CONFIG);

    assertEquals("value", metadata.getMetadata(otherKey),
        otherKey + " was changed by a task that only counts");
    assertEquals(2, metadata.getAllKeys().size(),
        "the task added or removed a key: " + metadata.getAllKeys());
  }

  /**
   * A context with no number, or with an empty one, is left as it was. The
   * task is put in workflows that may run before anything has set the number,
   * and it documents that case by returning early rather than by failing.
   */
  @HegelTest
  void aMissingOrEmptyNumberIsLeftAlone(TestCase tc) {
    boolean present = tc.draw(sampledFrom(List.of(true, false)), "present");
    Metadata metadata = metadataWith(present ? "" : null, "other", "value");

    new NumIncrementTask().run(metadata, NO_CONFIG);

    assertEquals(present ? "" : null, metadata.getMetadata("num"),
        "the number was invented or changed: " + metadata.getMetadata("num"));
    assertEquals("value", metadata.getMetadata("other"),
        "the rest of the context was changed");
  }
}
