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

//JUnit imports
import junit.framework.TestCase;

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
