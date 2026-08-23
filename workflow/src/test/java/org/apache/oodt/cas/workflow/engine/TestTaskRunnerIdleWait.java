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
import org.apache.oodt.cas.workflow.engine.runner.EngineRunner;
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.structs.FILOPrioritySorter;

//JDK imports
import java.util.concurrent.atomic.AtomicInteger;

//JUnit imports
import junit.framework.TestCase;

/**
 * What the runner does when there is nothing to run.
 *
 * It used to poll continuously. A jstack of an idle engine showed this thread
 * runnable with 522 seconds of CPU in 522 seconds of wall clock: one core, held
 * at full tilt, executing getNext() against an empty queue. Its counterpart
 * TaskQuerier had always waited between passes; this loop simply never got the
 * same treatment.
 *
 * @author mattmann
 */
public class TestTaskRunnerIdleWait extends TestCase {

  private String priorProperty;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.priorProperty = System.getProperty(TaskRunner.IDLE_WAIT_PROPERTY);
    System.clearProperty(TaskRunner.IDLE_WAIT_PROPERTY);
  }

  @Override
  protected void tearDown() throws Exception {
    if (this.priorProperty == null) {
      System.clearProperty(TaskRunner.IDLE_WAIT_PROPERTY);
    } else {
      System.setProperty(TaskRunner.IDLE_WAIT_PROPERTY, this.priorProperty);
    }
    super.tearDown();
  }

  /**
   * The point of the whole change: an idle runner polls at a human rate rather
   * than as fast as the CPU allows. Before this, a quarter second of idling
   * ran getNext() hundreds of thousands of times.
   */
  public void testIdleRunnerDoesNotSpin() throws Exception {
    CountingQuerier querier = new CountingQuerier();
    TaskRunner taskRunner = new TaskRunner(querier, new NoOpRunner(), 50);

    Thread thread = new Thread(taskRunner);
    thread.start();
    Thread.sleep(400);
    taskRunner.setRunning(false);
    thread.join(5000);

    int polls = querier.calls.get();
    assertTrue("an idle runner should have polled a handful of times, not "
        + polls, polls > 0 && polls < 40);
  }

  /**
   * The wait is skipped when work was handed over, so a busy engine is not
   * slowed down by the pause that keeps an idle one quiet.
   */
  public void testRunnerWithWorkDoesNotWait() throws Exception {
    CountingQuerier querier = new CountingQuerier();
    querier.work = (TaskProcessor) new QuerierAndRunnerUtils()
        .getProcessor(1.0, "Loaded", "initial");
    TaskRunner taskRunner = new TaskRunner(querier, new NoOpRunner(), 1000);

    Thread thread = new Thread(taskRunner);
    thread.start();
    Thread.sleep(300);
    taskRunner.setRunning(false);
    thread.join(5000);

    assertTrue("work should be handed over without waiting between tasks, "
        + "got " + querier.calls.get() + " polls",
        querier.calls.get() > 10);
  }

  public void testDefaultIdleWaitIsUsedWhenUnset() {
    assertEquals(TaskRunner.DEFAULT_IDLE_WAIT_MILLIS,
        new TaskRunner(new CountingQuerier(), new NoOpRunner())
            .getIdleWaitMillis());
  }

  public void testPropertyOverridesTheDefault() {
    System.setProperty(TaskRunner.IDLE_WAIT_PROPERTY, "250");
    assertEquals(250L, new TaskRunner(new CountingQuerier(), new NoOpRunner())
        .getIdleWaitMillis());
  }

  /**
   * A misconfigured property should not stop the engine from starting.
   */
  public void testUnparseablePropertyFallsBackToTheDefault() {
    System.setProperty(TaskRunner.IDLE_WAIT_PROPERTY, "soon");
    assertEquals(TaskRunner.DEFAULT_IDLE_WAIT_MILLIS,
        new TaskRunner(new CountingQuerier(), new NoOpRunner())
            .getIdleWaitMillis());
  }

  /**
   * Zero is the old behaviour, and remains reachable for anyone who wants it.
   */
  public void testZeroMeansPollContinuously() {
    System.setProperty(TaskRunner.IDLE_WAIT_PROPERTY, "0");
    assertEquals(0L, new TaskRunner(new CountingQuerier(), new NoOpRunner())
        .getIdleWaitMillis());
  }

  /**
   * Interrupting the thread ends the loop rather than being swallowed, so a
   * shutdown does not have to wait out the current sleep.
   */
  public void testInterruptEndsTheLoop() throws Exception {
    TaskRunner taskRunner = new TaskRunner(new CountingQuerier(),
        new NoOpRunner(), 60000);

    Thread thread = new Thread(taskRunner);
    thread.start();
    Thread.sleep(100);
    thread.interrupt();
    thread.join(5000);

    assertFalse("the runner thread should have ended on interrupt",
        thread.isAlive());
  }

  // ---- helpers -----------------------------------------------------------

  /**
   * A querier that records how often it was asked for work.
   */
  private static class CountingQuerier extends TaskQuerier {

    private final AtomicInteger calls = new AtomicInteger();

    /** Handed back on every poll when set; null means the queue is empty. */
    private TaskProcessor work;

    CountingQuerier() {
      super(new MockProcessorQueue(), new FILOPrioritySorter(), null, 2);
    }

    @Override
    public TaskProcessor getNext() {
      calls.incrementAndGet();
      return work;
    }
  }

  private static class NoOpRunner extends EngineRunner {

    @Override
    public void execute(TaskProcessor taskProcessor) {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public boolean hasOpenSlots(TaskProcessor taskProcessor) {
      return true;
    }

    @Override
    public void setInstanceRepository(WorkflowInstanceRepository instRep) {
    }
  }
}
