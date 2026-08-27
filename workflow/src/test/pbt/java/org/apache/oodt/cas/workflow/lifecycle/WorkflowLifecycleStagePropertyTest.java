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

package org.apache.oodt.cas.workflow.lifecycle;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Properties of {@link WorkflowLifecycleStage}, one phase of a lifecycle.
 *
 * <p>A stage is identified by its name alone. That matters more than it looks:
 * a lifecycle holds its stages in a sorted set, so two stages the class calls
 * equal are one stage as far as the lifecycle is concerned, and the number of
 * stages is the denominator of the percent-complete the manager reports.
 */
class WorkflowLifecycleStagePropertyTest {

  /** A small alphabet of stage names, so that names collide. */
  private static final List<String> NAMES =
      List.of("queued", "running", "done");

  private static WorkflowLifecycleStage stage(String name, int order,
      int priority) {
    return new WorkflowLifecycleStage(name, new ArrayList<WorkflowState>(),
        order, priority);
  }

  /**
   * Two stages are equal exactly when they are named the same, whatever order
   * or priority they carry, and equal stages hash alike. A state's category is
   * compared to a stage read from the lifecycle by these methods.
   */
  @HegelTest
  void stagesAreEqualByNameAndHashByNameToo(TestCase tc) {
    String leftName = tc.draw(sampledFrom(NAMES), "leftName");
    String rightName = tc.draw(sampledFrom(NAMES), "rightName");
    WorkflowLifecycleStage left = stage(leftName,
        tc.draw(integers().min(0).max(3), "leftOrder"),
        tc.draw(integers().min(0).max(3), "leftPriority"));
    WorkflowLifecycleStage right = stage(rightName,
        tc.draw(integers().min(0).max(3), "rightOrder"),
        tc.draw(integers().min(0).max(3), "rightPriority"));

    assertEquals(leftName.equals(rightName), left.equals(right),
        left + " and " + right + " compare wrongly");
    if (left.equals(right)) {
      assertEquals(left.hashCode(), right.hashCode(),
          "equal stages hash differently");
    }
    assertTrue(left.equals(left), "a stage did not equal itself");
    assertFalse(left.equals(leftName),
        "a stage equalled something that is not a stage");
  }

  /**
   * A stage keeps its name out of a hash-based collection. The lifecycle
   * reader files stages as it meets them and looks them up again by name as it
   * reads the states inside them.
   */
  @HegelTest
  void aStageIsFoundAgainInAMapKeyedByStages(TestCase tc) {
    int count = tc.draw(integers().min(1).max(4), "count");
    Map<WorkflowLifecycleStage, String> byStage = new HashMap<>();
    List<String> names = new ArrayList<>(count);

    for (int i = 0; i < count; i++) {
      String name = tc.draw(sampledFrom(NAMES), "name" + i);
      names.add(name);
      byStage.put(stage(name, tc.draw(integers().min(0).max(3), "order" + i), 0),
          name);
    }

    for (String name : names) {
      assertEquals(name, byStage.get(stage(name, 99, 99)),
          "a stage named " + name + " was not found again");
    }
  }

  /**
   * A stage built without an explicit priority expresses no preference, and
   * one built with an order keeps it. Order and priority mean different things
   * — where the stage falls in the progression, and how strongly the engine
   * prefers it when several states are eligible — so setting one must not
   * disturb the other.
   */
  @HegelTest
  void orderAndPriorityAreSeparate(TestCase tc) {
    String name = tc.draw(sampledFrom(NAMES), "name");
    int order = tc.draw(integers().min(-5).max(5), "order");
    int priority = tc.draw(integers().min(-5).max(5), "priority");

    WorkflowLifecycleStage withoutPriority =
        new WorkflowLifecycleStage(name, new ArrayList<WorkflowState>(), order);
    WorkflowLifecycleStage withPriority = stage(name, order, priority);

    assertEquals(0, withoutPriority.getPriority(),
        "a stage nobody prioritised prefers itself by "
            + withoutPriority.getPriority());
    assertEquals(order, withoutPriority.getOrder(), "the order changed");
    assertEquals(order, withPriority.getOrder(),
        "setting a priority changed the order");
    assertEquals(priority, withPriority.getPriority(), "the priority changed");
    assertNotNull(withPriority.getStates(), "a stage has null states");
  }

  /**
   * A stage built by the default constructor is ready to have states added to
   * it. The lifecycle reader builds one and fills it in as it reads.
   */
  @HegelTest
  void aDefaultStageIsReadyToBeFilledIn(TestCase tc) {
    String name = tc.draw(sampledFrom(NAMES), "name");

    WorkflowLifecycleStage stage = new WorkflowLifecycleStage();
    stage.setName(name);

    assertNotNull(stage.getStates(), "a new stage has null states");
    assertTrue(stage.getStates().isEmpty(), "a new stage already has states");
    assertEquals(0, stage.getPriority(),
        "a new stage already prefers itself");
    assertEquals(name, stage.getName(), "the name changed");
    assertEquals(name, stage.toString(), "a stage does not print as its name");
  }
}
