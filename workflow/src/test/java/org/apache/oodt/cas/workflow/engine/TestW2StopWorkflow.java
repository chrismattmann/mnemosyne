/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements.  See the NOTICE.txt file distributed with this work for
 * additional information regarding copyright ownership.  The ASF licenses this
 * file to you under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.oodt.cas.workflow.engine;

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.engine.runner.AsynchronousLocalEngineRunner;
import org.apache.oodt.cas.workflow.instrepo.MemoryWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.repository.PackagedWorkflowRepository;
import org.apache.oodt.cas.workflow.structs.HighestFIFOPrioritySorter;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

import junit.framework.TestCase;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Stopping a running workflow under the queue engine.
 *
 * <p>
 * The engine's stopWorkflow was an empty method and the rpc above it returned
 * true whatever happened, so the command line reported "Successfully stopped
 * workflow" while the workflow carried on running. These assert on what
 * happened to the workflow rather than on what the call returned.
 * </p>
 */
public class TestW2StopWorkflow extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String MODEL_DIR = "./src/test/resources/wengine-stop";

  private MemoryWorkflowInstanceRepository instanceRepo;
  private PrioritizedQueueBasedWorkflowEngine engine;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    SleepingTask.reset();
    this.instanceRepo = new MemoryWorkflowInstanceRepository(500);
    this.engine = new PrioritizedQueueBasedWorkflowEngine(instanceRepo,
        new HighestFIFOPrioritySorter(1, 50, 1),
        new WorkflowLifecycleManager(LIFECYCLE),
        new AsynchronousLocalEngineRunner(),
        new PackagedWorkflowRepository(
            Arrays.asList(new File(MODEL_DIR).listFiles())),
        1);
  }

  @Override
  protected void tearDown() throws Exception {
    this.engine.shutdown();
    SleepingTask.reset();
    super.tearDown();
  }

  /** The task that was running is interrupted rather than left alone. */
  public void testStoppingARunningWorkflowInterruptsItsTask() throws Exception {
    WorkflowInstance inst = startAndWaitForItToBeRunning();

    engine.stopWorkflow(inst.getId());

    assertTrue("the task was never interrupted, so nothing was stopped",
        waitFor(SleepingTask.interrupted));
    assertFalse("the task ran to completion despite being stopped",
        SleepingTask.ranToCompletion.get());
  }

  /** And the instance says so afterwards, to anyone who reads it. */
  public void testAStoppedWorkflowIsInADoneState() throws Exception {
    WorkflowInstance inst = startAndWaitForItToBeRunning();

    engine.stopWorkflow(inst.getId());

    WorkflowInstance stopped = waitForCategory(inst.getId(), "done");
    assertNotNull("a stopped workflow never reached a done state", stopped);
    assertEquals("Stopped", stopped.getState().getName());
  }

  /**
   * And it stays stopped. Interrupting the thread without marking the
   * instance leaves something the querier picks up again on its next pass,
   * which is a stop that lasts about a second.
   */
  public void testAStoppedWorkflowIsNotPickedUpAgain() throws Exception {
    WorkflowInstance inst = startAndWaitForItToBeRunning();

    engine.stopWorkflow(inst.getId());
    assertNotNull(waitForCategory(inst.getId(), "done"));

    // Several passes of a querier configured to wait one second.
    Thread.sleep(4000);

    WorkflowInstance after = instanceRepo.getWorkflowInstanceById(inst.getId());
    assertEquals("the engine started it again after it was stopped",
        "done", after.getState().getCategory().getName());
    assertFalse("the task was restarted after the workflow was stopped",
        SleepingTask.ranToCompletion.get());
  }

  /** Pausing holds it; the instance is not done, but it is not running on. */
  public void testPausingARunningWorkflowHoldsIt() throws Exception {
    WorkflowInstance inst = startAndWaitForItToBeRunning();

    engine.pauseWorkflowInstance(inst.getId());

    WorkflowInstance paused = waitForCategory(inst.getId(), "holding");
    assertNotNull("a paused workflow never reached a holding state", paused);
    assertEquals("Paused", paused.getState().getName());
  }

  /** Stopping something this engine has never heard of is not an error. */
  public void testStoppingAnUnknownInstanceIsHarmless() {
    engine.stopWorkflow("urn:oodt:no-such-instance");
    engine.stopWorkflow(null);
  }

  private WorkflowInstance startAndWaitForItToBeRunning() throws Exception {
    WorkflowInstance inst = engine.startWorkflow(
        modelFor("urn:oodt:stop:LongRunning"), new Metadata());

    assertTrue("the workflow never began running",
        SleepingTask.started.await(60, TimeUnit.SECONDS));
    return inst;
  }

  private boolean waitFor(java.util.concurrent.atomic.AtomicBoolean flag)
      throws Exception {
    for (int i = 0; i < 100; i++) {
      if (flag.get()) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }

  private WorkflowInstance waitForCategory(String id, String category)
      throws Exception {
    for (int i = 0; i < 100; i++) {
      WorkflowInstance inst = instanceRepo.getWorkflowInstanceById(id);
      if (inst != null && inst.getState() != null
          && inst.getState().getCategory() != null
          && category.equals(inst.getState().getCategory().getName())) {
        return inst;
      }
      Thread.sleep(100);
    }
    return null;
  }

  private Workflow modelFor(String id) throws Exception {
    List<?> workflows = engine.getWorkflowRepository().getWorkflowsForEvent(id);
    assertNotNull("no workflow for event [" + id + "]", workflows);
    assertFalse(workflows.isEmpty());
    return (Workflow) workflows.get(0);
  }
}
