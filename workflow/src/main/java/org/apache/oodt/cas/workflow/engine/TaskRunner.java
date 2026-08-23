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

//JDK imports
import org.apache.oodt.cas.workflow.engine.processor.TaskProcessor;
import org.apache.oodt.cas.workflow.engine.runner.EngineRunner;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports

/**
 * 
 * Implements the TaskRunner framework. Acts as a thread that works with the
 * TaskQuerier to take the next sorted (aka ones that have been sorted with the
 * Workflow PrioritySorter) task and then leverage the Engine's Runner to
 * execute the task.
 * 
 * The TaskRunner thread first pops a task off the list using
 * {@link TaskQuerier#getNext()} and then so long as the thread's
 * {@link #runner} has open slots as returned by
 * , and  is
 * false and {@link #isRunning()} is true, then the task is handed off to the
 * runner for execution.
 * 
 * When there is nothing runnable, the thread waits
 * {@link #DEFAULT_IDLE_WAIT_MILLIS} milliseconds before looking again, or
 * whatever {@link #IDLE_WAIT_PROPERTY} is set to. It does not wait after
 * handing work over, so a busy engine is not slowed down by the pause that
 * keeps an idle one from spinning.
 * 
 * @since Apache OODT 0.5
 * 
 * @author mattmann
 * @author bfoster
 * @version $Revision$
 * 
 */
// TODO(bfoster): Rename... Runner is missleading.
public class TaskRunner implements Runnable {

  private boolean running;

  private final TaskQuerier taskQuerier;

  private final EngineRunner runner;

  /**
   * How long to wait before polling again when there was nothing to run.
   * Zero polls continuously, which is what this loop used to do.
   */
  private final long idleWaitMillis;

  public static final String IDLE_WAIT_PROPERTY =
      "org.apache.oodt.cas.workflow.wengine.taskrunner.idleWaitMillis";

  public static final long DEFAULT_IDLE_WAIT_MILLIS = 100;

  private static final Logger LOG = Logger
      .getLogger(TaskRunner.class.getName());

  public TaskRunner(TaskQuerier taskQuerier, EngineRunner runner) {
    this(taskQuerier, runner, readIdleWaitMillis());
  }

  /**
   * Constructs a TaskRunner with an explicit idle wait.
   *
   * @param taskQuerier
   *          Where runnable work comes from.
   * @param runner
   *          What the work is handed to.
   * @param idleWaitMillis
   *          How long to wait before polling again when there was nothing to
   *          run. Milliseconds rather than seconds because, unlike the
   *          querier's wait, this one decides how quickly a queued task gets
   *          picked up.
   */
  public TaskRunner(TaskQuerier taskQuerier, EngineRunner runner,
      long idleWaitMillis) {
    this.running = true;
    this.taskQuerier = taskQuerier;
    this.runner = runner;
    this.idleWaitMillis = idleWaitMillis > 0 ? idleWaitMillis : 0;
  }

  private static long readIdleWaitMillis() {
    try {
      return Long.parseLong(System.getProperty(IDLE_WAIT_PROPERTY,
          String.valueOf(DEFAULT_IDLE_WAIT_MILLIS)));
    } catch (NumberFormatException e) {
      LOG.log(Level.WARNING, "Property [" + IDLE_WAIT_PROPERTY + "] is not a "
          + "number: [" + System.getProperty(IDLE_WAIT_PROPERTY) + "]; using ["
          + DEFAULT_IDLE_WAIT_MILLIS + "]");
      return DEFAULT_IDLE_WAIT_MILLIS;
    }
  }

  /**
   * @return how long this runner waits before polling again when idle
   */
  public long getIdleWaitMillis() {
    return idleWaitMillis;
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.lang.Runnable#run()
   */
  @Override
  public void run() {
    TaskProcessor nextTaskProcessor;

    while (running) {
      nextTaskProcessor = taskQuerier.getNext();
      boolean ranSomething = false;

      try {
        if (nextTaskProcessor != null && runner.hasOpenSlots(nextTaskProcessor)) {
          runner.execute(nextTaskProcessor);
          ranSomething = true;
        }
      } catch (Exception e) {
        LOG.log(Level.SEVERE, e.getMessage());
        LOG.log(
            Level.SEVERE,
            "Engine failed while submitting jobs to its runner : "
                + e.getMessage(), e);
        this.flagProcessorAsFailed(nextTaskProcessor, e.getMessage());
      }

      // Only wait when there was nothing to do, so a busy engine still hands
      // work over as fast as it arrives. Without this the loop polled an empty
      // queue continuously and held a core at full tilt for as long as the
      // engine was up, which is most of the time on an idle deployment.
      if (!ranSomething && idleWaitMillis > 0) {
        try {
          Thread.sleep(idleWaitMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }

  }

  /**
   * @return the running
   */
  public boolean isRunning() {
    return running;
  }

  /**
   * @param running
   *          the running to set
   */
  public void setRunning(boolean running) {
    this.running = running;
  }

  protected WorkflowTask extractTaskFromProcessor(TaskProcessor taskProcessor) {
    WorkflowInstance inst = taskProcessor.getWorkflowInstance();
    ParentChildWorkflow workflow = inst.getParentChildWorkflow();
    String taskId = inst.getCurrentTaskId();
    for (WorkflowTask task : workflow.getTasks()) {
      if (task.getTaskId().equals(taskId)) {
        return task;
      }
    }

    return null;
  }

  private void flagProcessorAsFailed(TaskProcessor nextTaskProcessor, String msg) {
    nextTaskProcessor.getWorkflowInstance().setState(nextTaskProcessor
        .getLifecycleManager()
        .getDefaultLifecycle()
        .createState("Failure", "done",
            "Failed while submitting job to Runner : " + msg));
    //TODO: persist me?

  }

}
