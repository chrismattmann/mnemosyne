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
import org.apache.oodt.cas.workflow.engine.processor.TaskProcessor;
import org.apache.oodt.cas.workflow.engine.processor.WorkflowProcessor;
import org.apache.oodt.cas.workflow.engine.runner.EngineRunner;
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.structs.FILOPrioritySorter;

//JDK imports
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogManager;

//JUnit imports
import junit.framework.TestCase;

/**
 * What happens to work the runner cannot start yet.
 *
 * getNext() removes the processor it hands out. When the runner had no free
 * slot for it, the old code let it fall out of scope: the instance stayed in
 * whatever state the querier had just set, nothing was left to run it, and
 * nothing reported it as failed. On a deployment with a full resource manager
 * that is a task quietly disappearing.
 *
 * @author mattmann
 */
public class TestTaskRunnerRequeue extends TestCase {

  private QuerierAndRunnerUtils utils;

  public TestTaskRunnerRequeue() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.utils = new QuerierAndRunnerUtils();
  }

  /**
   * The bug, stated directly: with the runner full, the task must still be
   * somewhere.
   */
  public void testTaskIsNotLostWhenTheRunnerIsFull() throws Exception {
    TaskQuerier querier = querierHolding(1);
    CountingRunner runner = new CountingRunner(false);
    TaskRunner taskRunner = new TaskRunner(querier, runner, 20);

    runFor(taskRunner, 250);

    assertEquals("nothing should have been executed", 0, runner.executed.get());
    assertEquals("and the task must still be queued",
        1, querier.getRunnableProcessors().size());
  }

  /**
   * Once a slot frees up, the task that was held back runs.
   */
  public void testHeldTaskRunsOnceASlotOpens() throws Exception {
    TaskQuerier querier = querierHolding(1);
    CountingRunner runner = new CountingRunner(false);
    TaskRunner taskRunner = new TaskRunner(querier, runner, 20);

    Thread thread = new Thread(taskRunner);
    thread.start();
    try {
      Thread.sleep(150);
      assertEquals(0, runner.executed.get());

      runner.openSlots = true;
      Thread.sleep(300);

      assertEquals("the held task should run once there is room",
          1, runner.executed.get());
      assertTrue(querier.getRunnableProcessors().isEmpty());
    } finally {
      taskRunner.setRunning(false);
      thread.join(5000);
    }
  }

  /**
   * Requeueing puts it back at the front, so a full runner does not reorder
   * what the prioritizer decided.
   */
  public void testRequeuePutsTheProcessorBackAtTheFront() throws Exception {
    TaskQuerier querier = querierHolding(2);
    TaskProcessor first = querier.getNext();
    assertNotNull(first);
    assertEquals(1, querier.getRunnableProcessors().size());

    querier.requeue(first);

    assertEquals(2, querier.getRunnableProcessors().size());
    assertSame("it should go back where it came from",
        first, querier.getNext());
  }

  public void testRequeueIgnoresNull() throws Exception {
    TaskQuerier querier = querierHolding(1);
    querier.requeue(null);
    assertEquals(1, querier.getRunnableProcessors().size());
  }

  /**
   * An empty queue yields nothing rather than throwing, which is the state the
   * runner polls in for most of an idle engine's life.
   */
  public void testEmptyQueueYieldsNull() throws Exception {
    assertNull(querierHolding(0).getNext());
  }

  // ---- helpers -----------------------------------------------------------

  /**
   * A querier whose queue is preloaded and whose own thread is never started,
   * so these tests observe only what the runner does to the queue.
   */
  private TaskQuerier querierHolding(int count) throws Exception {
    TaskQuerier querier = new TaskQuerier(new MockProcessorQueue(),
        new FILOPrioritySorter(), null, 2);
    List<WorkflowProcessor> queued = new Vector<WorkflowProcessor>();
    for (int i = 0; i < count; i++) {
      queued.add(utils.getProcessor(i + 1.0, "Loaded", "initial"));
    }
    querier.getRunnableProcessors().addAll(queued);
    return querier;
  }

  private void runFor(TaskRunner taskRunner, long millis) throws Exception {
    Thread thread = new Thread(taskRunner);
    thread.start();
    try {
      Thread.sleep(millis);
    } finally {
      taskRunner.setRunning(false);
      thread.join(5000);
    }
  }

  private static class CountingRunner extends EngineRunner {

    private final AtomicInteger executed = new AtomicInteger();

    private volatile boolean openSlots;

    CountingRunner(boolean openSlots) {
      this.openSlots = openSlots;
    }

    @Override
    public void execute(TaskProcessor taskProcessor) {
      executed.incrementAndGet();
    }

    @Override
    public void shutdown() {
    }

    @Override
    public boolean hasOpenSlots(TaskProcessor taskProcessor) {
      return openSlots;
    }

    @Override
    public void setInstanceRepository(WorkflowInstanceRepository instRep) {
    }
  }
}
