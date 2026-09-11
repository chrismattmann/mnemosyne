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

import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleStage;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;

import junit.framework.TestCase;

/**
 * An instance that is running again has not ended.
 *
 * <p>
 * The end date was stamped on reaching the done stage and then never touched,
 * so an instance that went back to work kept it. Anything asking how long a
 * task had been running subtracted its start from an end that had already
 * happened, and showed a duration frozen at the moment it first finished.
 * </p>
 *
 * <p>
 * Observed on a join recorded as finished at 16:16:25 which began its actual
 * work at 16:36. It reported five hours fifteen minutes, unchanging, for the
 * rest of the evening while four shard processes were busy. Worse, an instance
 * with both a status of Executing and an end date reads as a contradiction, and
 * that is the one place you look to find out whether a stage is moving.
 * </p>
 */
public class TestEndDateFollowsTheRun extends TestCase {

  private WorkflowState state(String name, String category) {
    WorkflowState s = new WorkflowState();
    s.setName(name);
    WorkflowLifecycleStage stage = new WorkflowLifecycleStage();
    stage.setName(category);
    s.setCategory(stage);
    return s;
  }

  private WorkflowInstance instance() {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("urn:oodt:instance");
    return inst;
  }

  public void testFinishingStampsAnEndDate() {
    WorkflowInstance inst = instance();
    assertNull(inst.getEndDate());
    inst.setState(state("ExecutionComplete", "done"));
    assertNotNull("reaching the done stage is what finishing is",
        inst.getEndDate());
  }

  public void testFinishingTwiceKeepsTheFirstEnd() {
    WorkflowInstance inst = instance();
    inst.setState(state("ExecutionComplete", "done"));
    java.util.Date first = inst.getEndDate();
    inst.setState(state("Success", "done"));
    assertEquals("still the same finish, so the same end",
        first, inst.getEndDate());
  }

  /** The bug. */
  public void testRunningAgainClearsTheEndDate() {
    WorkflowInstance inst = instance();
    inst.setState(state("ExecutionComplete", "done"));
    assertNotNull(inst.getEndDate());

    inst.setState(state("Executing", "running"));
    assertNull("an instance that is running again has not ended; keeping the "
        + "old end freezes every duration computed from it",
        inst.getEndDate());
  }

  public void testAndFinishingAgainStampsAFreshEnd() throws Exception {
    WorkflowInstance inst = instance();
    inst.setState(state("ExecutionComplete", "done"));
    java.util.Date first = inst.getEndDate();

    Thread.sleep(10);
    inst.setState(state("Executing", "running"));
    inst.setState(state("ExecutionComplete", "done"));

    assertNotNull(inst.getEndDate());
    assertTrue("the second run's end is the one that matters",
        inst.getEndDate().after(first));
  }

  /** Queued and blocked are not finished either. */
  public void testOtherNonDoneCategoriesAlsoClearIt() {
    for (String category : new String[] {"waiting", "running", "initial"}) {
      WorkflowInstance inst = instance();
      inst.setState(state("ExecutionComplete", "done"));
      assertNotNull(inst.getEndDate());
      inst.setState(state("Queued", category));
      assertNull("category [" + category + "] is not finished",
          inst.getEndDate());
    }
  }

  /** A state with no category says nothing, so it must not clear anything. */
  public void testAnUncategorisedStateLeavesItAlone() {
    WorkflowInstance inst = instance();
    inst.setState(state("ExecutionComplete", "done"));
    java.util.Date end = inst.getEndDate();

    WorkflowState vague = new WorkflowState();
    vague.setName("Whatever");
    inst.setState(vague);

    assertEquals(end, inst.getEndDate());
  }
}
