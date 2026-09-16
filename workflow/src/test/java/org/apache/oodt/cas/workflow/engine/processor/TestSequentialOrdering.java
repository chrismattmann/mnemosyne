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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;

import junit.framework.TestCase;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

/**
 * What a sequential workflow offers the queue, which is one step at a time.
 */
public class TestSequentialOrdering extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  public TestSequentialOrdering() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  /** Nothing has started: the first step is the one to hand out. */
  public void testTheFirstStepIsOfferedFirst() throws Exception {
    SequentialProcessor phase = phaseOf("Queued", "Queued");
    List<WorkflowProcessor> runnable = phase.getRunnableSubProcessors();

    assertEquals(1, runnable.size());
    assertSame(phase.getSubProcessors().get(0), runnable.get(0));
  }

  /**
   * The reported bug. A step that is running used to be walked past, and the
   * one after it handed out -- so a pipeline whose first phase took a while
   * ran every phase at once.
   */
  public void testNothingIsOfferedWhileAStepIsRunning() throws Exception {
    SequentialProcessor phase = phaseOf("Executing", "Queued");

    assertTrue("the step after a running one was handed out",
        phase.getRunnableSubProcessors().isEmpty());
  }

  /** Once it finishes, the next one is offered. */
  public void testTheNextStepFollowsTheFinishedOne() throws Exception {
    SequentialProcessor phase = phaseOf("Success", "Queued");
    List<WorkflowProcessor> runnable = phase.getRunnableSubProcessors();

    assertEquals(1, runnable.size());
    assertSame(phase.getSubProcessors().get(1), runnable.get(0));
  }

  /** And when they are all done there is nothing left to offer. */
  public void testNothingIsOfferedWhenEverythingIsDone() throws Exception {
    SequentialProcessor phase = phaseOf("Success", "Success");
    assertTrue(phase.getRunnableSubProcessors().isEmpty());
  }

  public void testTheCurrentTaskIsTheRunningStep() throws Exception {
    SequentialProcessor phase = phaseOf("Executing", "Queued");
    phase.getRunnableSubProcessors();
    assertEquals("urn:oodt:taskOne",
        phase.getWorkflowInstance().getCurrentTaskId());
  }

  public void testTheCurrentTaskAdvancesWhenAStepFinishes() throws Exception {
    // The bug: this reported the first task for the whole life of the
    // workflow, so an IndexCorpus instance showed IndexMetadataJaccard while
    // fg/bg, two tasks later, was the thing actually running.
    SequentialProcessor phase = phaseOf("Success", "Executing");
    phase.getRunnableSubProcessors();
    assertEquals("urn:oodt:taskTwo",
        phase.getWorkflowInstance().getCurrentTaskId());
  }

  public void testTheCurrentTaskAdvancesToAWaitingStepToo() throws Exception {
    // Not only to a running one: the step that is next to be handed out is
    // the one the workflow is on.
    SequentialProcessor phase = phaseOf("Success", "Queued");
    phase.getRunnableSubProcessors();
    assertEquals("urn:oodt:taskTwo",
        phase.getWorkflowInstance().getCurrentTaskId());
  }

  public void testTheCurrentTaskIsLeftAloneWhenEverythingIsDone()
      throws Exception {
    SequentialProcessor phase = phaseOf("Success", "Success");
    phase.getRunnableSubProcessors();
    // Nothing is current any more; the last thing it was on is the honest
    // answer, and is what the finished instance should keep.
    assertEquals("urn:oodt:taskOne",
        phase.getWorkflowInstance().getCurrentTaskId());
  }

  private SequentialProcessor phaseOf(String firstState, String secondState)
      throws Exception {
    WorkflowLifecycleManager manager = new WorkflowLifecycleManager(LIFECYCLE);
    SequentialProcessor phase = new SequentialProcessor(manager,
        instance("urn:oodt:phase"));
    TaskProcessor first = new TaskProcessor(manager, instance("urn:oodt:one"));
    TaskProcessor second = new TaskProcessor(manager, instance("urn:oodt:two"));
    // The task each child is: what the parent should report while it is on it.
    first.getWorkflowInstance().setCurrentTaskId("urn:oodt:taskOne");
    second.getWorkflowInstance().setCurrentTaskId("urn:oodt:taskTwo");
    // What the engine sets once, at creation, and never moves.
    phase.getWorkflowInstance().setCurrentTaskId("urn:oodt:taskOne");
    setState(first, firstState);
    setState(second, secondState);
    phase.setSubProcessors(Arrays.asList((WorkflowProcessor) first,
        (WorkflowProcessor) second));
    return phase;
  }

  private void setState(WorkflowProcessor processor, String name)
      throws Exception {
    String category = "Success".equals(name) ? "done"
        : ("Executing".equals(name) ? "running" : "waiting");
    processor.setState(processor.getLifecycleManager().getDefaultLifecycle()
        .createState(name, category, ""));
  }

  private WorkflowInstance instance(String id) {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId(id);
    inst.setParentChildWorkflow(new ParentChildWorkflow(new Graph()));
    inst.setSharedContext(new Metadata());
    return inst;
  }
}
