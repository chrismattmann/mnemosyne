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
    GateCondition.reset();

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

  // ---- dynamic workflows -------------------------------------------------

  /**
   * A workflow added at runtime has to be added to the repository the engine
   * resolves against, or the instance started from it refers to a model
   * nothing can find.
   *
   * <p>
   * The workflow manager and the engine each built a repository from the same
   * property. That looks equivalent and is not: the factory returns a new
   * object per call, so an addition to one was invisible to the other.
   * executeDynamicWorkflow added the workflow to the manager's repository and
   * started an instance the engine resolved against its own, where the
   * workflow did not exist. The instance was created, reported a valid id,
   * and stayed in Queued forever. This test holds the engine to the part it
   * can guarantee: what it hands out is what it resolves against.
   * </p>
   */
  public void testTheEngineResolvesAgainstTheRepositoryItExposes()
      throws Exception {
    assertNotNull("an engine that resolves models must expose the repository"
        + " it resolves against, so a caller adding a workflow at runtime can"
        + " add it where the engine will look",
        engine.getWorkflowRepository());

    Workflow dynamic = new Workflow();
    dynamic.getTasks().add(
        engine.getWorkflowRepository().getWorkflowTaskById("urn:oodt:e2e:PhaseWork"));

    String id = engine.getWorkflowRepository().addWorkflow(dynamic);
    assertNotNull("the repository should give the new workflow an id", id);

    assertNotNull("a workflow added through the engine's repository must be"
        + " resolvable from it: this is what the processor queue does when it"
        + " loads the instance, and returning null here is what left dynamic"
        + " instances stuck in Queued",
        engine.getWorkflowRepository().getWorkflowById(id));
  }

  /**
   * Two repositories built from the same configuration are two repositories.
   *
   * <p>
   * This is the trap the manager fell into. It built one from a property and
   * the engine built another from the same property, which reads as though
   * both refer to the same thing. A workflow added at runtime went into one
   * of them, and the instance started from it was resolved against the other.
   * Pinned here so the next caller tempted to build "the" repository from the
   * property has this written down.
   * </p>
   */
  public void testAsecondRepositoryBuiltTheSameWayIsAdifferentRepository()
      throws Exception {
    PackagedWorkflowRepository other = new PackagedWorkflowRepository(
        java.util.Arrays.asList(new File(MODEL_DIR).listFiles()));

    Workflow dynamic = new Workflow();
    dynamic.getTasks().add(
        engine.getWorkflowRepository().getWorkflowTaskById("urn:oodt:e2e:PhaseWork"));
    String id = engine.getWorkflowRepository().addWorkflow(dynamic);

    assertNotNull("the repository it was added to must hold it",
        engine.getWorkflowRepository().getWorkflowById(id));
    assertNull("a repository built separately from the same files does not"
        + " see a workflow added to the other at runtime -- adding to one and"
        + " resolving against the other is what left dynamic instances in"
        + " Queued with no model",
        other.getWorkflowById(id));
  }

  // ---- parallel ----------------------------------------------------------

  /**
   * A parallel block is dissolved by the repository: the workflow written in
   * the file cannot be fetched by its id, and its children are registered
   * under that id as an event instead. Starting them is how the block runs.
   */
  public void testParallelBlockRunsAllOfItsTasks() throws Exception {
    assertNull("a parallel workflow is dissolved, not stored",
        engine.getWorkflowRepository()
            .getWorkflowById("urn:oodt:e2e:BothAtOnce"));

    List<?> children = engine.getWorkflowRepository()
        .getWorkflowsForEvent("urn:oodt:e2e:BothAtOnce");
    assertEquals("each bare task should have been given a workflow",
        2, children.size());

    for (Object child : children) {
      engine.startWorkflow((Workflow) child, new Metadata());
    }

    awaitRecorded(2);

    List<String> ran = RecordingTask.recorded();
    assertTrue("alpha should have run, got " + ran, ran.contains("alpha"));
    assertTrue("beta should have run, got " + ran, ran.contains("beta"));
  }

  /**
   * The generated wrappers survive being loaded alongside another file. Until
   * recently the second file's pass emptied them and the task was lost, so
   * starting one ran nothing at all.
   */
  public void testGeneratedWrapperStillCarriesItsTaskAcrossFiles()
      throws Exception {
    for (Object child : engine.getWorkflowRepository()
        .getWorkflowsForEvent("urn:oodt:e2e:BothAtOnce")) {
      assertEquals("a wrapper with no task would run nothing",
          1, ((Workflow) child).getTasks().size());
    }
  }

  /**
   * Definitions resolve across files: these tasks are declared in one file and
   * referenced from the workflow in another.
   */
  public void testIdRefResolvesAcrossFiles() throws Exception {
    assertNotNull(engine.getWorkflowRepository()
        .getWorkflowTaskById("urn:oodt:e2e:Alpha"));
    assertNotNull(engine.getWorkflowRepository()
        .getWorkflowById("urn:oodt:e2e:TwoStep"));
  }

  // ---- conditions: reproducer for #82, not yet passing --------------------

  //
  // Conditions do not gate anything in this engine: a condition on a task is
  // never evaluated, and a condition on a workflow is evaluated and its answer
  // discarded. Both are recorded in #82 along with why, so these three are
  // deliberately not named test* and JUnit does not collect them. Rename them
  // and they are the reproducer.
  //
  // They are kept here rather than pasted into the issue because the fixture
  // and the gate condition they need are already in the tree, and because the
  // shape of the assertion is the specification: a closed gate must stop the
  // work, and the engine must be shown to have asked.
  //

  /**
   * An open gate lets the work through. Establishes that the guarded workflow
   * runs at all, so that the closed-gate case below means something.
   */
  public void testOpenGateLetsTheTaskRun() throws Exception {
    GateCondition.open(true);

    engine.startWorkflow(modelFor("urn:oodt:e2e:GuardedTaskWorkflow"),
        new Metadata());

    awaitRecorded(1);
    assertEquals(java.util.Arrays.asList("guarded-task"),
        RecordingTask.recorded());
  }

  /**
   * A closed gate must stop the task. A condition that is never consulted
   * looks exactly like one that always passes, until it is supposed to stop
   * something -- so this asserts both that the task did not run and that the
   * engine actually asked.
   */
  public void testClosedGateStopsTheTask() throws Exception {
    GateCondition.open(false);

    engine.startWorkflow(modelFor("urn:oodt:e2e:GuardedTaskWorkflow"),
        new Metadata());

    Thread.sleep(6000);

    String observed = "evaluations=" + GateCondition.evaluations()
        + " ran=" + RecordingTask.recorded();
    assertEquals("a closed gate must stop the task running; " + observed,
        java.util.Collections.emptyList(), RecordingTask.recorded());
    assertTrue("the engine should have consulted the condition; " + observed,
        GateCondition.evaluations() > 0);
  }

  /**
   * A condition written on the workflow rather than the task. The repository
   * hoists it into a generated task placed first, so it should gate the same
   * way.
   */
  public void testConditionOnTheWorkflowAlsoGates() throws Exception {
    GateCondition.open(false);

    engine.startWorkflow(modelFor("urn:oodt:e2e:GuardedWorkflow"),
        new Metadata());

    Thread.sleep(6000);

    String observed = "evaluations=" + GateCondition.evaluations()
        + " ran=" + RecordingTask.recorded();
    assertEquals("a closed gate must stop the workflow's tasks; " + observed,
        java.util.Collections.emptyList(), RecordingTask.recorded());
    assertTrue("the hoisted condition should have been consulted; " + observed,
        GateCondition.evaluations() > 0);
  }

  // ---- ordered phases ----------------------------------------------------

  /**
   * The three phases a workflow has always claimed to have, in order.
   *
   * Order is the whole point. A precondition evaluated beside the work it
   * guards has not gated anything, and a post-condition evaluated while the
   * tasks are still running is judging nothing.
   */
  public void testConditionsRunBeforeAndAfterTheWork() throws Exception {
    engine.startWorkflow(modelFor("urn:oodt:e2e:Phased"), new Metadata());

    awaitRecordedContains("after");

    assertEquals("preconditions, then work, then post-conditions",
        java.util.Arrays.asList("before", "work", "after"),
        RecordingTask.recorded());
  }

  /**
   * Both conditions blocks are read. Only the first used to be, so a workflow
   * declaring pre and post lost one of them depending on the order written.
   */
  public void testBothConditionsBlocksAreRead() throws Exception {
    Workflow phased = modelFor("urn:oodt:e2e:Phased");

    assertEquals("one precondition", 1, phased.getPreConditions().size());
    assertEquals("one post-condition", 1, phased.getPostConditions().size());
  }

  /**
   * A failing precondition stops the work, and the post-condition with it:
   * there is nothing for it to judge.
   */
  public void testFailedPreconditionStopsBothLaterPhases() throws Exception {
    GateCondition.open(false);

    engine.startWorkflow(modelFor("urn:oodt:e2e:Phased"), new Metadata());

    Thread.sleep(6000);

    assertFalse("the work must not have run: " + RecordingTask.recorded(),
        RecordingTask.recorded().contains("work"));
    assertFalse("nor the post-condition: " + RecordingTask.recorded(),
        RecordingTask.recorded().contains("after"));
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

  private void awaitRecordedContains(String entry) throws Exception {
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      if (RecordingTask.recorded().contains(entry)) {
        return;
      }
      Thread.sleep(100);
    }
    StringBuilder dump = new StringBuilder();
    try {
      for (Object o : instanceRepo.getWorkflowInstances()) {
        WorkflowInstance wi = (WorkflowInstance) o;
        dump.append("\n  ").append(wi.getParentChildWorkflow().getId())
            .append(" -> ")
            .append(wi.getState() == null ? "null" : wi.getState().getName())
            .append(" [")
            .append(wi.getState() == null || wi.getState().getCategory() == null
                ? "null" : wi.getState().getCategory().getName())
            .append("]");
      }
    } catch (Exception ignore) {
      dump.append(" <dump failed>");
    }
    fail("Timed out after " + TIMEOUT_MILLIS + "ms waiting for [" + entry
        + "]; recorded " + RecordingTask.recorded() + "; instances:" + dump);
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
