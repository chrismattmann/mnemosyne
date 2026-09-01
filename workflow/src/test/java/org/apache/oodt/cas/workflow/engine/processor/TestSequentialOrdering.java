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

  private SequentialProcessor phaseOf(String firstState, String secondState)
      throws Exception {
    WorkflowLifecycleManager manager = new WorkflowLifecycleManager(LIFECYCLE);
    SequentialProcessor phase = new SequentialProcessor(manager,
        instance("urn:oodt:phase"));
    TaskProcessor first = new TaskProcessor(manager, instance("urn:oodt:one"));
    TaskProcessor second = new TaskProcessor(manager, instance("urn:oodt:two"));
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
