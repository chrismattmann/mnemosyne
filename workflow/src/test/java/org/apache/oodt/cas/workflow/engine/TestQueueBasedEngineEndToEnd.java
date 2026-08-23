/**
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

//JDK imports
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;

//JUnit imports
import junit.framework.TestCase;

/**
 * Runs a workflow through the queue-based engine, for real.
 *
 * Everything else in this module tests a piece in isolation: a runner, a
 * querier, a repository, a state transitioner. All of that can be green while
 * the engine as a whole does nothing, because what the pieces have to agree
 * about is exactly what isolated tests cannot check. This starts the engine,
 * hands it a two-task workflow, and waits for the work to happen.
 *
 * @author mattmann
 */
public class TestQueueBasedEngineEndToEnd extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String MODEL_DIR = "./src/test/resources/wengine-e2e";

  private static final long TIMEOUT_MILLIS = 30000;

  private PrioritizedQueueBasedWorkflowEngine engine;

  private MemoryWorkflowInstanceRepository instanceRepo;

  private AsynchronousLocalEngineRunner runner;

  public TestQueueBasedEngineEndToEnd() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    RecordingTask.reset();

    this.instanceRepo = new MemoryWorkflowInstanceRepository(500);
    this.runner = new AsynchronousLocalEngineRunner();

    PackagedWorkflowRepository modelRepo = new PackagedWorkflowRepository(
        java.util.Arrays.asList(new File(MODEL_DIR).listFiles()));

    this.engine = new PrioritizedQueueBasedWorkflowEngine(instanceRepo,
        new HighestFIFOPrioritySorter(1, 50, 1),
        new WorkflowLifecycleManager(LIFECYCLE), runner, modelRepo, 1);
  }

  @Override
  protected void tearDown() throws Exception {
    if (this.engine != null) {
      this.engine.shutdown();
    }
    if (this.runner != null) {
      this.runner.shutdown();
    }
    RecordingTask.reset();
    super.tearDown();
  }

  /**
   * The whole point: hand the engine a workflow and get the work done.
   */
  public void testTwoTaskWorkflowRunsToCompletion() throws Exception {
    Workflow workflow = modelFor("urn:oodt:e2e:TwoStep");
    assertNotNull("the fixture workflow should load", workflow);
    assertEquals(2, workflow.getTasks().size());

    WorkflowInstance inst = engine.startWorkflow(workflow, new Metadata());
    assertNotNull(inst);

    awaitRecorded(2);

    List<String> ran = RecordingTask.recorded();
    assertEquals("both tasks should have run: " + ran, 2, ran.size());
    assertEquals("and in the order the workflow declares",
        java.util.Arrays.asList("first", "second"), ran);
  }

  /**
   * Having run the work, the engine should also say so. State and execution
   * are separate claims and this asserts the second one.
   */
  public void testWorkflowReachesADoneState() throws Exception {
    WorkflowInstance inst = engine.startWorkflow(modelFor("urn:oodt:e2e:TwoStep"),
        new Metadata());

    awaitRecorded(2);
    String category = awaitDoneCategory(inst.getId());

    assertEquals("the workflow should end in the done category",
        "done", category);
  }

  /**
   * Metadata handed to the engine reaches the tasks.
   */
  public void testStartingMetadataReachesTheTasks() throws Exception {
    Metadata met = new Metadata();
    met.addMetadata("SuppliedAtStart", "yes");

    engine.startWorkflow(modelFor("urn:oodt:e2e:TwoStep"), met);
    awaitRecorded(2);

    assertTrue("the first task should see what the caller supplied",
        RecordingTask.keysSeenBy("first").contains("SuppliedAtStart"));
  }

  /**
   * Brian's first objection on the umbrella issue: whether metadata flows
   * through from one task to the next, or whether each task is handed a
   * context that has lost what the previous one wrote.
   */
  public void testMetadataWrittenByATaskReachesTheNextTask() throws Exception {
    engine.startWorkflow(modelFor("urn:oodt:e2e:TwoStep"), new Metadata());
    awaitRecorded(2);

    assertTrue("the second task should see what the first one wrote, saw: "
        + RecordingTask.keysSeenBy("second"),
        RecordingTask.keysSeenBy("second").contains("ranBy-first"));
  }

  /**
   * A task that throws should fail the workflow, not leave it running or
   * quietly report success.
   */
  public void testFailingTaskFailsTheWorkflow() throws Exception {
    RecordingTask.failEverything(true);

    WorkflowInstance inst = engine.startWorkflow(
        modelFor("urn:oodt:e2e:TwoStep"), new Metadata());

    awaitRecorded(1);
    String category = awaitDoneCategory(inst.getId());

    assertEquals("a failing task should drive the workflow to done",
        "done", category);
    WorkflowInstance finished =
        instanceRepo.getWorkflowInstanceById(inst.getId());
    assertEquals("and it should say it failed",
        "Failure", finished.getState().getName());
  }

  // ---- helpers -----------------------------------------------------------

  private Workflow modelFor(String id) throws Exception {
    for (Object w : engine.getWorkflowRepository().getWorkflows()) {
      if (id.equals(((Workflow) w).getId())) {
        return (Workflow) w;
      }
    }
    return null;
  }

  private void awaitRecorded(int expected) throws Exception {
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      if (RecordingTask.recorded().size() >= expected) {
        return;
      }
      Thread.sleep(100);
    }
    fail("Timed out after " + TIMEOUT_MILLIS + "ms waiting for " + expected
        + " tasks to run; recorded " + RecordingTask.recorded());
  }

  /**
   * @return the category the instance settled in, for the caller to assert on
   */
  private String awaitDoneCategory(String instanceId) throws Exception {
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      WorkflowInstance current = instanceRepo.getWorkflowInstanceById(instanceId);
      if (current != null && current.getState() != null
          && current.getState().getCategory() != null) {
        last = current.getState().getCategory().getName();
        if ("done".equals(last)) {
          return last;
        }
      }
      Thread.sleep(100);
    }
    return "timed out, last category was [" + last + "]";
  }
}
