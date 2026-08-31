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

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.LogManager;

import junit.framework.TestCase;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

/**
 * What a workflow reports while its children are working, and whether it can
 * get out of the states it passes through on the way.
 */
public class TestWorkflowProcessorRunningStates extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  public TestWorkflowProcessorRunningStates() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  /**
   * The reported bug: a phase that had fanned out its tasks and was watching
   * them finish said it was queued, which is the stage meaning "not started".
   */
  public void testAWorkflowWithWorkInFlightReportsExecuting() throws Exception {
    SequentialProcessor phase = phaseWithOneUnfinishedChild();
    setState(phase, "Queued", "waiting");

    phase.nextState();

    assertEquals("Executing", phase.getWorkflowInstance().getState().getName());
    assertEquals("running", phase.getWorkflowInstance().getState()
        .getCategory().getName());
  }

  /** A task is put into Executing by whatever runs it, not by this. */
  public void testATaskIsNotMovedToExecutingByItself() throws Exception {
    TaskProcessor task = task();
    setState(task, "Queued", "waiting");

    task.nextState();

    assertFalse("Executing".equals(
        task.getWorkflowInstance().getState().getName()));
  }

  /**
   * A workflow that declares its own pre-conditions passes through
   * PreConditionSuccess. Nothing moved it on from there, so once its children
   * finished it stayed: never done, never given an end date, and offered to
   * the querier for ever.
   */
  public void testAWorkflowLeavesPreConditionSuccess() throws Exception {
    SequentialProcessor phase = phaseWithOneUnfinishedChild();
    setState(phase, "PreConditionSuccess", "transition");

    phase.nextState();

    assertEquals("a workflow stayed where nothing could move it on",
        "Executing", phase.getWorkflowInstance().getState().getName());
  }

  /** And once the children are done, it finishes from there too. */
  public void testAWorkflowFinishesFromPreConditionSuccess() throws Exception {
    SequentialProcessor phase = phaseWithOneUnfinishedChild();
    setState((WorkflowProcessor) phase.getSubProcessors().get(0),
        "Success", "done");
    setState(phase, "PreConditionSuccess", "transition");

    phase.nextState();

    assertEquals("Success", phase.getWorkflowInstance().getState().getName());
    assertEquals("done", phase.getWorkflowInstance().getState().getCategory()
        .getName());
  }

  private SequentialProcessor phaseWithOneUnfinishedChild() throws Exception {
    WorkflowLifecycleManager manager = manager();
    SequentialProcessor phase = new SequentialProcessor(manager, instance(
        "urn:oodt:phase"));
    TaskProcessor child = new TaskProcessor(manager, instance("urn:oodt:task"));
    setState(child, "Queued", "waiting");
    phase.setSubProcessors(Arrays.asList((WorkflowProcessor) child));
    return phase;
  }

  private TaskProcessor task() throws Exception {
    return new TaskProcessor(manager(), instance("urn:oodt:task"));
  }

  private WorkflowLifecycleManager manager() throws Exception {
    return new WorkflowLifecycleManager(LIFECYCLE);
  }

  private WorkflowInstance instance(String id) {
    WorkflowInstance instance = new WorkflowInstance();
    instance.setId(id);
    instance.setParentChildWorkflow(new ParentChildWorkflow(new Graph()));
    instance.setSharedContext(new Metadata());
    return instance;
  }

  private void setState(WorkflowProcessor processor, String name,
      String category) throws Exception {
    processor.setState(processor.getLifecycleManager().getDefaultLifecycle()
        .createState(name, category, ""));
  }
}
