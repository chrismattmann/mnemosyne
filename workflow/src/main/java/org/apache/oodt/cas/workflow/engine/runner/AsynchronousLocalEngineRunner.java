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

//JDK imports
import org.apache.oodt.cas.workflow.engine.processor.TaskProcessor;
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycle;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;
import org.apache.oodt.cas.workflow.util.GenericWorkflowObjectFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports

/**
 * Runs a local version of a {@link TaskProcessor} asynchronously.
 * 
 * @author mattmann (Chris Mattmann)
 * @author bfoster (Brian Foster)
 */
public class AsynchronousLocalEngineRunner extends AbstractEngineRunnerBase {

  private static final Logger LOG = Logger
      .getLogger(AsynchronousLocalEngineRunner.class.getName());

  public static final int DEFAULT_NUM_THREADS = 25;

  private final ExecutorService executor;

  /*
   * What the pool is running, keyed by the instance it is running it for.
   *
   * These were the Thread objects the work was written as, which is not what
   * runs it: a Thread handed to an ExecutorService is used as a Runnable, so
   * it is never started and a pool thread calls its run() instead.
   * Interrupting one of those objects therefore reached nothing -- the task
   * carried on, and so did shutdown's attempt to stop everything. A Future
   * cancels the thread that is actually running the work.
   */
  private final Map<String, Future<?>> workerMap;

  public AsynchronousLocalEngineRunner() {
    this(DEFAULT_NUM_THREADS);
  }

  public AsynchronousLocalEngineRunner(int numThreads) {
    super();
    this.executor = Executors.newFixedThreadPool(numThreads);
    this.workerMap = new ConcurrentHashMap<String, Future<?>>();
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.runner.EngineRunner#execute(org.apache
   * .oodt.cas.workflow.engine.processor.TaskProcessor)
   */
  @Override
  public void execute(final TaskProcessor taskProcessor) {
    Thread worker = new Thread() {

      @Override
      public void run() {
        WorkflowLifecycle lifecycle = getLifecycle(taskProcessor);
        WorkflowTask workflowTask = getTaskFromProcessor(taskProcessor);
        // Before the task is built, so what it reads from the shared context
        // is there when it runs. See stampTaskMetadata.
        stampTaskMetadata(taskProcessor, workflowTask);
        WorkflowTaskInstance inst = GenericWorkflowObjectFactory
            .getTaskObjectFromClassName(workflowTask.getTaskInstanceClassName());
        try {
          inst.run(taskProcessor.getWorkflowInstance().getSharedContext(),
              workflowTask.getTaskConfig());
          String msg = "Task: [" + workflowTask.getTaskName()
              + "] for instance id: ["
              + taskProcessor.getWorkflowInstance().getId()
              + "] completed successfully";
          LOG.log(Level.INFO, msg);
          WorkflowState state = lifecycle.createState("ExecutionComplete", "transition", msg);
          taskProcessor.setState(state);
          persist(taskProcessor.getWorkflowInstance());
        } catch (Exception e) {
          LOG.log(Level.SEVERE, e.getMessage());
          String msg = "Exception executing task: ["
              + workflowTask.getTaskName() + "]: Message: " + e.getMessage();
          LOG.log(Level.WARNING, msg);
          WorkflowState state = lifecycle.createState("Failure", "done", msg);
          taskProcessor.setState(state);
          persist(taskProcessor.getWorkflowInstance());
        } finally {
          workerMap.remove(taskProcessor.getWorkflowInstance().getId());
        }

      }

      /*
       * (non-Javadoc)
       * 
       * @see java.lang.Thread#interrupt()
       */
      @SuppressWarnings("deprecation")
      @Override
      public void interrupt() {
        super.interrupt();
       
      }

    };

    // Keyed by the instance being run, and removed when the run ends.
    //
    // It was keyed by a fresh UUID and never cleared, so it grew for the life
    // of the process and could not answer the one question worth asking of
    // it: which instances is this engine running? Nothing asked, because the
    // engine returned the interface's empty default instead, and so anything
    // reading "is this instance running" was told no about every instance --
    // which is how live work came to be reported as abandoned, and why none
    // of it had a wall clock.
    synchronized (this.workerMap) {
      this.workerMap.put(taskProcessor.getWorkflowInstance().getId(),
          this.executor.submit(worker));
    }
  }

  /**
   * The instances this runner is running right now.
   *
   * <p>
   * A task that is blocked is still running: a redirector waiting for the
   * workflow it started has a thread, has begun, and has not finished.
   * </p>
   */
  public java.util.Collection<String> getExecutingInstanceIds() {
    return new java.util.ArrayList<String>(this.workerMap.keySet());
  }

  /**
   * Interrupts the task this runner is running for one instance.
   *
   * <p>
   * Whoever asked for the stop needs to know whether there was anything to
   * stop, so this says whether a worker was actually running. The thread is
   * interrupted rather than killed: Thread.stop leaves whatever it was in the
   * middle of half done, and a pge run is usually in the middle of writing to
   * the catalog.
   * </p>
   *
   * @return whether a worker was found and interrupted
   */
  public boolean stop(String instanceId) {
    if (instanceId == null) {
      return false;
    }

    Future<?> worker;
    synchronized (this.workerMap) {
      worker = this.workerMap.remove(instanceId);
    }

    if (worker == null) {
      return false;
    }

    LOG.info("Stopping the task running for instance " + instanceId);
    // true: interrupt it if it has already started, which is the case worth
    // handling -- a task that has not started yet is simply dropped.
    return worker.cancel(true);
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.oodt.cas.workflow.engine.EngineRunner#shutdown()
   */
  @Override
  public void shutdown() {
    for (Future<?> worker : this.workerMap.values()) {
      if (worker != null) {
        worker.cancel(true);
      }
    }
    this.workerMap.clear();
    // Otherwise the pool's own threads keep the JVM alive after everything
    // running on them has been cancelled.
    this.executor.shutdownNow();
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.runner.EngineRunner#hasOpenSlots(org
   * .apache.oodt.cas.workflow.engine.processor.TaskProcessor)
   */
  @Override
  public boolean hasOpenSlots(TaskProcessor taskProcessor) {
    // TODO Auto-generated method stub
    return true;
  }

  /* (non-Javadoc)
   * @see org.apache.oodt.cas.workflow.engine.runner.EngineRunner#setInstanceRepository(org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository)
   */
  @Override
  public void setInstanceRepository(WorkflowInstanceRepository instRep) {
    this.instRep = instRep;    
  }

}
