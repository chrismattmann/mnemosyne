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

package org.apache.oodt.cas.workflow.engine.processor;

import java.util.Collections;

import junit.framework.TestCase;

import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

/**
 * A waiting reason has to name what is being waited for.
 *
 * <p>
 * "condition:unknown" is barely better than the status it was meant to
 * explain. A gated instance said exactly that for every gate, because the
 * reason was looked up in the preConditions structure while the thing
 * actually holding it was a prerequisite -- which is how the queue engine
 * dispatches a condition: as an instance of its own.
 * </p>
 */
public class TestWaitingOnNames extends TestCase {

  private WorkflowLifecycleManager lifecycle;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    lifecycle = new WorkflowLifecycleManager(
        "./src/main/resources/examples/wengine/wengine-lifecycle.xml");
  }

  public void testAConditionGateIsNamedAsACondition() {
    TaskProcessor gated = processor("urn:drat:RatAggregator", "task");
    gated.setPrerequisites(Collections.<WorkflowProcessor>singletonList(
        processor("urn:drat:MapsDone", "condition", "Queued")));

    assertTrue("the reason should have been recorded", gated.recordWaitingOn());
    assertEquals("condition:urn:drat:MapsDone",
        gated.getWorkflowInstance().getWaitingOn());
  }

  public void testATaskGateIsNamedAsATask() {
    TaskProcessor gated = processor("urn:drat:RatAggregator", "task");
    gated.setPrerequisites(Collections.<WorkflowProcessor>singletonList(
        processor("urn:drat:RepoCrawler", "task", "Executing")));

    assertTrue(gated.recordWaitingOn());
    assertEquals("task:urn:drat:RepoCrawler",
        gated.getWorkflowInstance().getWaitingOn());
  }

  /**
   * A gate that failed is done and still gates this processor for good.
   * Reporting it beats reporting nothing, which is what not-done asked for.
   */
  public void testAFailedGateIsStillNamed() {
    TaskProcessor gated = processor("urn:drat:RatAggregator", "task");
    gated.setPrerequisites(Collections.<WorkflowProcessor>singletonList(
        processor("urn:drat:MapsDone", "condition", "Failure")));

    assertTrue(gated.recordWaitingOn());
    assertEquals("condition:urn:drat:MapsDone",
        gated.getWorkflowInstance().getWaitingOn());
  }

  /** Nothing outstanding is no reason, and clears one already recorded. */
  public void testNothingOutstandingIsNoReason() {
    TaskProcessor gated = processor("urn:drat:RatAggregator", "task");
    gated.setPrerequisites(Collections.<WorkflowProcessor>singletonList(
        processor("urn:drat:MapsDone", "condition", "Success")));
    gated.getWorkflowInstance().setWaitingOn("condition:urn:drat:MapsDone");

    assertTrue("clearing the reason is a change", gated.recordWaitingOn());
    assertNull(gated.getWorkflowInstance().getWaitingOn());
  }

  /** The same answer twice is not a second write. */
  public void testAnUnchangedReasonIsNotAChange() {
    TaskProcessor gated = processor("urn:drat:RatAggregator", "task");
    gated.setPrerequisites(Collections.<WorkflowProcessor>singletonList(
        processor("urn:drat:MapsDone", "condition", "Queued")));

    assertTrue(gated.recordWaitingOn());
    assertFalse("the same reason should not count as a change",
        gated.recordWaitingOn());
  }

  private TaskProcessor processor(String taskId, String executionType) {
    return processor(taskId, executionType, "Queued");
  }

  private TaskProcessor processor(String taskId, String executionType,
      String stateName) {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("winst-" + taskId);
    inst.setCurrentTaskId(taskId);
    Graph graph = new Graph();
    graph.setExecutionType(executionType);
    if ("condition".equals(executionType)) {
      // The engine synthesises a task to run a condition and gives it a
      // "-task" id; the condition itself is what a reader recognises.
      WorkflowCondition cond = new WorkflowCondition();
      cond.setConditionId(taskId);
      cond.setConditionName(taskId);
      graph.setCond(cond);
      inst.setCurrentTaskId(taskId + "-task");
    }
    inst.setParentChildWorkflow(new ParentChildWorkflow(graph));
    TaskProcessor processor = new TaskProcessor(lifecycle, inst);
    // After construction: the processor's constructor puts every instance
    // into Null, so a state set before it is built is discarded.
    WorkflowState state = new WorkflowState();
    state.setName(stateName);
    inst.setState(state);
    return processor;
  }
}
