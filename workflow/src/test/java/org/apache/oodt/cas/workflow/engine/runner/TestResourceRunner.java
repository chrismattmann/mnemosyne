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

package org.apache.oodt.cas.workflow.engine.runner;

//OODT imports
import org.apache.oodt.cas.workflow.engine.QuerierAndRunnerUtils;
import org.apache.oodt.cas.workflow.engine.processor.TaskProcessor;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.resource.structs.JobStatus;

//JUnit imports
import junit.framework.TestCase;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.resource.structs.JobInput;
import org.apache.oodt.cas.workflow.metadata.CoreMetKeys;
import org.apache.oodt.cas.workflow.structs.TaskJobInput;
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

/**
 * Exercises {@link ResourceRunner} against a stand-in Resource Manager.
 *
 * The runner was previously unusable for three separate reasons, one per test
 * below: it refused all work, it never recorded submissions, and it never
 * observed jobs finishing, so an instance handed to it stayed in the running
 * category forever.
 *
 * @author mattmann
 */
public class TestResourceRunner extends TestCase {

  private static final long FAST_POLL_SECONDS = 1;

  private ResourceRunner runner;

  /**
   * The engine only calls execute() when hasOpenSlots() agrees, so a runner
   * that always answers false is never given work at all. This is what made
   * the ResourceRunner appear broken.
   */
  public void testHasOpenSlotsReflectsQueueCapacity() throws Exception {
    MockResourceManagerClient client = new MockResourceManagerClient();
    client.setQueueCapacity(10);
    client.setQueueSize(3);
    runner = new ResourceRunner(client, null, FAST_POLL_SECONDS);

    assertTrue("runner should accept work when the queue has room",
        runner.hasOpenSlots(null));

    client.setQueueSize(10);
    assertFalse("runner should refuse work when the queue is full",
        runner.hasOpenSlots(null));
  }

  /**
   * A resource manager that cannot report capacity should not cause the runner
   * to refuse everything; it should admit work conservatively instead.
   */
  public void testHasOpenSlotsFallsBackWhenCapacityUnknown() throws Exception {
    MockResourceManagerClient client = new MockResourceManagerClient();
    client.setQueueCapacity(0);
    runner = new ResourceRunner(client, null, FAST_POLL_SECONDS);

    assertTrue("unknown capacity should still admit a first job",
        runner.hasOpenSlots(null));
  }

  /**
   * A submission has to be recorded, or nothing can ever observe it finishing.
   */
  public void testExecuteSubmitsAndTracksTheJob() throws Exception {
    MockResourceManagerClient client = new MockResourceManagerClient();
    client.setQueueCapacity(10);
    client.setNextJobId("job-1");
    runner = new ResourceRunner(client, null, FAST_POLL_SECONDS);

    TaskProcessor processor = newTaskProcessor();
    runner.execute(processor);

    assertEquals("the job should have been submitted to the resource manager",
        1, client.getSubmittedJobs().size());
    assertEquals("the submitted job should be tracked until it completes",
        1, runner.getOutstandingJobCount());
  }

  /**
   * The job outlives the execute() call, so completion is observed by the
   * monitor rather than awaited. Once seen, the job stops being tracked.
   */
  public void testMonitorReleasesCompletedJobs() throws Exception {
    MockResourceManagerClient client = new MockResourceManagerClient();
    client.setQueueCapacity(10);
    client.setNextJobId("job-1");
    runner = new ResourceRunner(client, null, FAST_POLL_SECONDS);

    runner.execute(newTaskProcessor());
    assertEquals(1, runner.getOutstandingJobCount());

    client.setJobComplete("job-1", true);

    long deadline = System.currentTimeMillis() + 15000;
    while (runner.getOutstandingJobCount() > 0
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(200);
    }

    assertEquals("a completed job should no longer be tracked",
        0, runner.getOutstandingJobCount());
  }

  /**
   * A submission the resource manager rejects must not leave the task tracked,
   * or the workflow would wait on a job that does not exist.
   */
  public void testRejectedSubmissionIsNotTracked() throws Exception {
    MockResourceManagerClient client = new MockResourceManagerClient();
    client.setQueueCapacity(10);
    client.setNextJobId(null);
    runner = new ResourceRunner(client, null, FAST_POLL_SECONDS);

    runner.execute(newTaskProcessor());

    assertEquals("a rejected submission should not be tracked",
        0, runner.getOutstandingJobCount());
  }

  /**
   * The keys a task cannot start without have to be in the shared context
   * before it is captured for the wire.
   *
   * <p>AsynchronousLocalEngineRunner has always called stampTaskMetadata.
   * ResourceRunner extended the same base class, inherited the method and
   * never called it, so a task that ran locally failed on a node with
   * "Must specify WorkflowInstId" from PGETaskInstance -- and PGE tasks are
   * most of what a deployment runs. It showed up in the workflow manager too:
   * the instance metadata was visibly sparse, because stamping writes into
   * the instance's shared context and nothing was writing.</p>
   */
  public void testExecuteStampsTheTaskMetadata() throws Exception {
    MockResourceManagerClient client = new MockResourceManagerClient();
    client.setQueueCapacity(10);
    client.setNextJobId("job-1");
    runner = new ResourceRunner(client, null, FAST_POLL_SECONDS);

    runner.execute(newTaskProcessor());

    assertEquals(1, client.getSubmittedInputs().size());
    JobInput submitted = client.getSubmittedInputs().get(0);
    assertTrue(submitted instanceof TaskJobInput);
    Metadata met = ((TaskJobInput) submitted).getDynMetadata();
    assertNotNull("a task with no metadata cannot run", met);
    assertNotNull("PGETaskInstance refuses to start without it",
        met.getMetadata(CoreMetKeys.WORKFLOW_INST_ID));
    assertNotNull("the task has to know which task it is",
        met.getMetadata(CoreMetKeys.TASK_ID));
  }

  /**
   * What the node wrote while the task was away must survive completion.
   *
   * <p>The processor holds a copy of the instance read before the job was
   * submitted. TaskJob on the node writes the machine it actually ran on
   * through the workflow manager, into the repository. Persisting the
   * pre-dispatch copy afterwards put the old values straight back: an
   * instance that ran on a compute node kept naming the manager's own host,
   * because the correction was written and then overwritten.</p>
   */
  public void testCompletionKeepsWhatTheNodeWrote() throws Exception {
    MockResourceManagerClient client = new MockResourceManagerClient();
    client.setQueueCapacity(10);
    client.setNextJobId("job-1");

    TaskProcessor processor = newTaskProcessor();
    String instanceId = processor.getWorkflowInstance().getId();

    // The repository as the node left it: same instance, its own hostname.
    WorkflowInstance asTheNodeLeftIt = new WorkflowInstance();
    asTheNodeLeftIt.setId(instanceId);
    Metadata nodeContext = new Metadata();
    nodeContext.addMetadata(CoreMetKeys.PROCESSING_NODE, "spaghetti");
    asTheNodeLeftIt.setSharedContext(nodeContext);

    runner = new ResourceRunner(client, repositoryHolding(asTheNodeLeftIt),
        FAST_POLL_SECONDS);
    runner.execute(processor);

    // The processor still holds the pre-dispatch value.
    processor.getWorkflowInstance().getSharedContext()
        .replaceMetadata(CoreMetKeys.PROCESSING_NODE, "ninja");

    client.setJobComplete("job-1", true);
    long deadline = System.currentTimeMillis() + 15000;
    while (runner.getOutstandingJobCount() > 0
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }

    assertEquals("the node ran it, so the node's answer is the true one",
        "spaghetti",
        processor.getWorkflowInstance().getSharedContext()
            .getMetadata(CoreMetKeys.PROCESSING_NODE));
  }

  /** A repository that knows one instance: the one the node wrote. */
  private WorkflowInstanceRepository repositoryHolding(
      final WorkflowInstance stored) {
    return (WorkflowInstanceRepository) java.lang.reflect.Proxy.newProxyInstance(
        WorkflowInstanceRepository.class.getClassLoader(),
        new Class[] {WorkflowInstanceRepository.class},
        new java.lang.reflect.InvocationHandler() {
          public Object invoke(Object proxy, java.lang.reflect.Method method,
              Object[] args) {
            if ("getWorkflowInstanceById".equals(method.getName())) {
              return stored;
            }
            Class<?> r = method.getReturnType();
            if (r == boolean.class) { return Boolean.FALSE; }
            if (r == int.class) { return Integer.valueOf(0); }
            return null;
          }
        });
  }

  /**
   * A task that the resource manager has put on a node says so.
   *
   * <p>
   * Nothing ever set Executing for a task: it went to WaitingOnResources when
   * handed to a runner and stayed there until it finished, so a queue full of
   * work and a cluster running flat out looked identical from the workflow
   * side.
   * </p>
   */
  public void testATaskPlacedOnANodeReportsExecuting() throws Exception {
    MockResourceManagerClient client = new MockResourceManagerClient();
    client.setQueueCapacity(10);
    client.setNextJobId("job-1");
    runner = new ResourceRunner(client, null, FAST_POLL_SECONDS);

    TaskProcessor processor = newTaskProcessor();
    runner.execute(processor);
    client.setJobStatus("job-1", JobStatus.EXECUTED);

    assertTrue("a task on a node should report Executing",
        waitForState(processor, "Executing"));
  }

  /** A job still sitting in the resource manager's queue does not. */
  public void testAQueuedTaskDoesNotClaimToBeExecuting() throws Exception {
    MockResourceManagerClient client = new MockResourceManagerClient();
    client.setQueueCapacity(10);
    client.setNextJobId("job-1");
    runner = new ResourceRunner(client, null, FAST_POLL_SECONDS);

    TaskProcessor processor = newTaskProcessor();
    runner.execute(processor);
    client.setJobStatus("job-1", JobStatus.QUEUED);

    assertFalse("a queued job must not claim to be running",
        waitForState(processor, "Executing"));
  }

  /**
   * Completion still wins over the status probe, and a finished job stops
   * being tracked exactly as before.
   */
  public void testCompletionIsUnaffectedByTheStatusProbe() throws Exception {
    MockResourceManagerClient client = new MockResourceManagerClient();
    client.setQueueCapacity(10);
    client.setNextJobId("job-1");
    runner = new ResourceRunner(client, null, FAST_POLL_SECONDS);

    runner.execute(newTaskProcessor());
    client.setJobStatus("job-1", JobStatus.EXECUTED);
    client.setJobComplete("job-1", true);

    long deadline = System.currentTimeMillis() + 15000;
    while (runner.getOutstandingJobCount() > 0
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(200);
    }
    assertEquals("a completed job should no longer be tracked",
        0, runner.getOutstandingJobCount());
  }

  /**
   * Which status means "a node is working on this".
   *
   * <p>
   * EXECUTED only. SCHEDULED is set by the job queue when a job is taken off
   * to be considered, and the scheduler puts it back when no node has room, so
   * counting it reported 374 tasks executing against 16 processes actually
   * running.
   * </p>
   */
  public void testOnlyExecutedCountsAsRunning() throws Exception {
    assertTrue(ResourceRunner.isOnANode(JobStatus.EXECUTED));
    assertFalse("SCHEDULED means dequeued for consideration, not running",
        ResourceRunner.isOnANode(JobStatus.SCHEDULED));
    assertFalse(ResourceRunner.isOnANode(JobStatus.QUEUED));
    assertFalse(ResourceRunner.isOnANode(JobStatus.SUCCESS));
    assertFalse(ResourceRunner.isOnANode(JobStatus.FAILURE));
    assertFalse("an unreadable status is not a running one",
        ResourceRunner.isOnANode(null));
  }

  private boolean waitForState(TaskProcessor processor, String name)
      throws Exception {
    long deadline = System.currentTimeMillis() + 8000;
    while (System.currentTimeMillis() < deadline) {
      WorkflowState state = processor.getWorkflowInstance().getState();
      if (state != null && name.equals(state.getName())) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }

  private TaskProcessor newTaskProcessor() throws Exception {
    QuerierAndRunnerUtils utils = new QuerierAndRunnerUtils();
    // getProcessor builds and returns the TaskProcessor itself.
    return (TaskProcessor) utils.getProcessor(1.0, "Loaded", "initial");
  }

  @Override
  protected void tearDown() throws Exception {
    if (runner != null) {
      runner.shutdown();
      runner = null;
    }
    super.tearDown();
  }
}
