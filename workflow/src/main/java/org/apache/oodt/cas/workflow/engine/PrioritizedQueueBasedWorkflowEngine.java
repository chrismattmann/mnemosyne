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

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.engine.processor.WorkflowProcessorQueue;
import org.apache.oodt.cas.workflow.engine.runner.AbstractEngineRunnerBase;
import org.apache.oodt.cas.workflow.engine.runner.EngineRunner;
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycle;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleStage;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.repository.WorkflowRepository;
import org.apache.oodt.cas.workflow.structs.HighestFIFOPrioritySorter;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.PrioritySorter;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.EngineException;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;

import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.apache.oodt.cas.workflow.engine.processor.WorkflowProcessor;
import org.apache.oodt.cas.workflow.engine.runner.AsynchronousLocalEngineRunner;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports

/**
 * 
 * Describe your class here.
 * 
 * @author mattmann
 * @author bfoster
 * @version $Revision$
 * 
 */
public class PrioritizedQueueBasedWorkflowEngine implements WorkflowEngine {

  private static final Logger LOG = Logger
      .getLogger(PrioritizedQueueBasedWorkflowEngine.class.getName());
  private final Thread queuerThread;
  private final Thread runnerThread;
  private final WorkflowInstanceRepository repo;
  private final WorkflowRepository modelRepo;
  private final WorkflowLifecycleManager lifecycle;
  private final PrioritySorter prioritizer;
  private WorkflowProcessorQueue processorQueue;
  private URL wmgrUrl;
  private EngineRunner runner;
  private final TaskQuerier querier;
  private final TaskRunner taskRunner;

  public PrioritizedQueueBasedWorkflowEngine(WorkflowInstanceRepository repo,
      PrioritySorter prioritizer, WorkflowLifecycleManager lifecycle,
      EngineRunner runner, WorkflowRepository modelRepo, long querierWaitSeconds) {
    this.repo = repo;
    this.prioritizer = prioritizer == null ? new HighestFIFOPrioritySorter(1,
        50, 1) : prioritizer;
    this.lifecycle = lifecycle;
    this.modelRepo = modelRepo;
    this.processorQueue = new WorkflowProcessorQueue(repo, lifecycle, modelRepo);
    this.runner = runner;
    this.runner.setInstanceRepository(repo);

    // Task QUEUER thread
    this.querier = new TaskQuerier(processorQueue, this.prioritizer,
        this.repo, querierWaitSeconds);
    queuerThread = new Thread(this.querier);
    queuerThread.start();

    // Task Runner thread
    this.taskRunner = new TaskRunner(this.querier, runner);
    runnerThread = new Thread(this.taskRunner);
    runnerThread.start();

  }

  public void setEngineRunner(EngineRunner runner) {
    this.runner = runner;
  }

  /**
   * Stops the querier and runner threads this engine started.
   *
   * Both were started in the constructor and there was no way to stop either,
   * so a process embedding this engine could not shut it down and every test
   * that built one leaked two threads for the life of the JVM.
   */
  @Override
  public void shutdown() {
    this.querier.setRunning(false);
    this.taskRunner.setRunning(false);
    this.queuerThread.interrupt();
    this.runnerThread.interrupt();
    if (this.runner != null) {
      this.runner.shutdown();
    }
    // Last, and only once nothing above can still write. An instance
    // repository that keeps a store open holds it for the life of the
    // process otherwise: the manager stopped serving, its threads stopped,
    // and an embedded database went on running a timer and holding its file
    // lock, keeping the JVM alive with nothing listening on it.
    WorkflowInstanceRepository repo = getInstanceRepository();
    if (repo != null) {
      try {
        repo.release();
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "Error releasing the instance repository: "
            + e.getMessage());
      }
    }
  }

  /**
   * The instances this engine is running: the tasks its runner has in hand,
   * and the workflows above them that are running by virtue of those tasks.
   *
   * <p>
   * This engine never implemented it and inherited the interface's empty
   * answer, so everything asking "is this instance running" was told no about
   * every instance. What reads that is the report of whether an instance has
   * been abandoned -- a workflow the engine is not running and has not
   * finished -- and with the answer always no, every live instance in the
   * deployment was reported as abandoned, and none of them showed a wall
   * clock. The feature has worked on the thread pool engine and done nothing
   * here since it was written.
   * </p>
   *
   * <p>
   * A workflow is not handed to a runner: it runs by having children that
   * are. Reporting only what the runner holds would leave every phase of a
   * pipeline looking abandoned while its own tasks ran, so a workflow in a
   * running state counts as running here too.
   * </p>
   */
  public Collection<String> getExecutingInstanceIds() {
    Set<String> executing = new LinkedHashSet<String>();
    if (this.runner instanceof AsynchronousLocalEngineRunner) {
      executing.addAll(((AsynchronousLocalEngineRunner) this.runner)
          .getExecutingInstanceIds());
    }
    for (WorkflowProcessor processor : this.processorQueue.getProcessors()) {
      WorkflowInstance inst = processor.getWorkflowInstance();
      if (inst == null || inst.getState() == null
          || inst.getState().getCategory() == null) {
        continue;
      }
      if ("running".equals(inst.getState().getCategory().getName())) {
        executing.add(inst.getId());
      }
    }
    return executing;
  }

  /**
   * @return the model repository this engine reads workflows from
   */
  public WorkflowRepository getWorkflowRepository() {
    return this.modelRepo;
  }

  /**
   * The statuses this engine's lifecycle declares, in the order the lifecycle
   * lists them, so a reader is offered stages before the states inside them.
   *
   * <p>
   * Duplicates are dropped: a status can appear in more than one lifecycle,
   * and a filter wants it once.
   * </p>
   */
  @Override
  /**
   * The stage each status sits in, from the same walk that lists the
   * statuses. A stage is the category: "done" is the one that means over.
   */
  public Map<String, String> getStatusCategories() {
    Map<String, String> categories = new LinkedHashMap<String, String>();
    if (this.lifecycle == null) {
      return categories;
    }
    WorkflowLifecycle cycle = this.lifecycle.getDefaultLifecycle();
    if (cycle == null) {
      return categories;
    }
    for (Object o : cycle.getStages()) {
      WorkflowLifecycleStage stage = (WorkflowLifecycleStage) o;
      if (stage.getStates() == null) {
        continue;
      }
      for (Object s : stage.getStates()) {
        String name = ((WorkflowState) s).getName();
        if (name != null && !name.equals("") && !categories.containsKey(name)) {
          categories.put(name, stage.getName());
        }
      }
    }
    return categories;
  }

  public List<String> getSupportedStatuses() {
    List<String> statuses = new Vector<String>();
    if (this.lifecycle == null) {
      return statuses;
    }
    WorkflowLifecycle cycle = this.lifecycle.getDefaultLifecycle();
    if (cycle == null) {
      return statuses;
    }
    for (Object o : cycle.getStages()) {
      WorkflowLifecycleStage stage = (WorkflowLifecycleStage) o;
      if (stage.getStates() == null) {
        continue;
      }
      for (Object s : stage.getStates()) {
        String name = ((WorkflowState) s).getName();
        if (name != null && !name.equals("") && !statuses.contains(name)) {
          statuses.add(name);
        }
      }
    }
    return statuses;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#startWorkflow(org.apache
   * .oodt.cas.workflow.structs.Workflow, org.apache.oodt.cas.metadata.Metadata)
   */
  @Override
  public WorkflowInstance startWorkflow(Workflow workflow, Metadata metadata)
      throws EngineException {
    // TODO Auto-generated method stub

    // looks like the work to do here is
    // create a new WorkflowInstance
    // create a new WorkflowProcessor around it
    // set it in Queued status
    // commit it to workflow instance repo and it will get picked up

    WorkflowInstance inst = new WorkflowInstance();
    inst.setParentChildWorkflow(workflow instanceof ParentChildWorkflow ? (ParentChildWorkflow) workflow
        : new ParentChildWorkflow(workflow));
    inst.setStartDate(Calendar.getInstance().getTime());
    inst.setCurrentTaskId(workflow.getTasks().get(0).getTaskId());
    inst.setSharedContext(metadata);
    inst.setPriority(Priority.getDefault()); // FIXME: this should be sensed or
                                             // passed in
    WorkflowLifecycle cycle = getLifecycleForWorkflow(workflow);
    WorkflowState state = cycle.createState("Null", "initial",
        "Workflow created by Engine.");
    inst.setState(state);
    persist(inst);
    return inst;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#stopWorkflow(java.lang
   * .String)
   */
  /**
   * Stops a running instance, and says so in its state.
   *
   * <p>
   * This was an empty method. The rpc above it returned true whatever
   * happened, so the command line reported "Successfully stopped workflow"
   * for an instance that carried on running -- which is worse than not
   * offering the operation at all, because the report was believed.
   * </p>
   *
   * <p>
   * Stopping is two things: the task that is running has to be interrupted,
   * and the instance has to be marked so that the querier does not simply
   * pick it up again on its next pass. Doing only the first leaves an
   * instance the engine restarts a second later.
   * </p>
   */
  @Override
  public void stopWorkflow(String workflowInstId) {
    moveTo(workflowInstId, "Stopped", "done", "Stopped by request");
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#pauseWorkflowInstance
   * (java.lang.String)
   */
  /**
   * Holds an instance where it is. Also an empty method, reported as success
   * the same way. Paused is a holding state, so the querier leaves it alone
   * until something moves it on.
   */
  @Override
  public void pauseWorkflowInstance(String workflowInstId) {
    moveTo(workflowInstId, "Paused", "holding", "Paused by request");
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#resumeWorkflowInstance
   * (java.lang.String)
   */
  /**
   * Puts a held instance back in the queue, where the querier will find it.
   */
  @Override
  public void resumeWorkflowInstance(String workflowInstId) {
    moveTo(workflowInstId, "Queued", "waiting", "Resumed by request");
  }

  /**
   * Moves one instance to a named state and writes it down.
   *
   * <p>
   * The task running for it is interrupted first, so that a stop actually
   * stops rather than marking an instance that goes on working. The state is
   * set on the processor as well as persisted: the processor is what the
   * querier reads, so an instance whose state was only written to the
   * repository would be picked straight back up.
   * </p>
   */
  private void moveTo(String workflowInstId, String stateName,
      String category, String message) {
    if (workflowInstId == null) {
      return;
    }

    WorkflowProcessor processor = getProcessor(workflowInstId);
    if (processor == null) {
      LOG.log(Level.WARNING, "Asked to move instance " + workflowInstId
          + " to " + stateName + ", but this engine is not tracking it");
      return;
    }

    WorkflowState state = this.lifecycle
        .getDefaultLifecycle().createState(stateName, category, message);
    if (state.getCategory() == null) {
      LOG.log(Level.WARNING, "The lifecycle in use declares no '" + category
          + "' category, so instance " + workflowInstId
          + " cannot be moved to " + stateName);
      return;
    }

    /*
     * The whole tree, not just the workflow that was named.
     *
     * A workflow does not run: its tasks do, each as a processor of its own
     * with an instance id of its own, and it is one of those the runner is
     * holding a thread for. Stopping only the id the caller passed leaves the
     * task running under a workflow that reports itself stopped -- which is
     * the same false report by a shorter route.
     */
    for (WorkflowProcessor each : treeUnder(processor)) {
      WorkflowInstance inst = each.getWorkflowInstance();
      if (inst == null) {
        continue;
      }

      if (this.runner instanceof AsynchronousLocalEngineRunner) {
        ((AsynchronousLocalEngineRunner) this.runner).stop(inst.getId());
      }

      each.setState(state);
      inst.setState(state);
      this.processorQueue.persist(inst);
    }

    LOG.info("Instance " + workflowInstId + " is now " + stateName);
  }

  /** A processor and everything beneath it, parents first. */
  private List<WorkflowProcessor> treeUnder(WorkflowProcessor processor) {
    List<WorkflowProcessor> all = new ArrayList<WorkflowProcessor>();
    all.add(processor);
    List<WorkflowProcessor> children = processor.getSubProcessors();
    if (children != null) {
      for (WorkflowProcessor child : children) {
        if (child != null) {
          all.addAll(treeUnder(child));
        }
      }
    }
    return all;
  }

  /** The processor for one instance, or null when there is not one. */
  private WorkflowProcessor getProcessor(String workflowInstId) {
    for (WorkflowProcessor processor : this.processorQueue.getProcessors()) {
      WorkflowInstance inst = processor.getWorkflowInstance();
      if (inst != null && workflowInstId.equals(inst.getId())) {
        return processor;
      }
    }
    return null;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#getInstanceRepository()
   */
  @Override
  public WorkflowInstanceRepository getInstanceRepository() {
    return this.repo;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#updateMetadata(java.
   * lang.String, org.apache.oodt.cas.metadata.Metadata)
   */
  @Override
  public boolean updateMetadata(String workflowInstId, Metadata met) {
    if (workflowInstId == null || met == null) {
      return false;
    }
    try {
      WorkflowInstance inst = repo.getWorkflowInstanceById(workflowInstId);
      if (inst == null) {
        return false;
      }
      Metadata ctx = inst.getSharedContext();
      if (ctx == null) {
        ctx = new Metadata();
        inst.setSharedContext(ctx);
      }
      // Overlay the supplied keys. W1 replaces the whole context; the PGE
      // watcher therefore sends a merged copy. Merging here means a W2
      // caller that only has the new keys does not wipe WorkflowInstId.
      ctx.replaceMetadata(met);
      persist(inst);
      return true;
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Could not update metadata for instance "
          + workflowInstId + ": " + e.getMessage());
      return false;
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#setWorkflowManagerUrl
   * (java.net.URL)
   */
  @Override
  public void setWorkflowManagerUrl(URL url) {
    this.wmgrUrl = url;
    // Passed on rather than only kept. A task reads the manager's URL out of
    // its shared context, and the runner is what puts it there.
    if (this.runner instanceof AbstractEngineRunnerBase) {
      ((AbstractEngineRunnerBase) this.runner).setWorkflowManagerUrl(url);
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#getWallClockMinutes(
   * java.lang.String)
   */
  @Override
  public double getWallClockMinutes(String workflowInstId) {
    // TODO Auto-generated method stub
    return 0;
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.oodt.cas.workflow.engine.WorkflowEngine#
   * getCurrentTaskWallClockMinutes(java.lang.String)
   */
  @Override
  public double getCurrentTaskWallClockMinutes(String workflowInstId) {
    // TODO Auto-generated method stub
    return 0;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#getWorkflowInstanceMetadata
   * (java.lang.String)
   */
  @Override
  public Metadata getWorkflowInstanceMetadata(String workflowInstId) {
    if (workflowInstId == null) {
      return new Metadata();
    }
    try {
      WorkflowInstance inst = repo.getWorkflowInstanceById(workflowInstId);
      if (inst == null || inst.getSharedContext() == null) {
        return new Metadata();
      }
      return inst.getSharedContext();
    } catch (Exception e) {
      LOG.log(Level.FINE, "No instance metadata for " + workflowInstId + ": "
          + e.getMessage());
      return new Metadata();
    }
  }

  private synchronized void persist(WorkflowInstance inst) throws EngineException {
    try {
      if (inst.getId() == null || (inst.getId().equals(""))) {
        // we have to persist it by adding it
        // rather than updating it
        repo.addWorkflowInstance(inst);
      } else {
        // persist by update
        repo.updateWorkflowInstance(inst);
      }
    } catch (InstanceRepositoryException e) {
      LOG.log(Level.SEVERE, e.getMessage());
      throw new EngineException(e.getMessage());
    }
  }

  private WorkflowLifecycle getLifecycleForWorkflow(Workflow workflow) {
    return lifecycle.getLifecycleForWorkflow(workflow) != null ? lifecycle
        .getLifecycleForWorkflow(workflow) : lifecycle.getDefaultLifecycle();
  }

}
