/*
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
package org.apache.oodt.cas.workflow.engine.processor;


//OODT imports
import org.apache.oodt.cas.workflow.engine.ChangeType;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowStateTransitioner;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

//JDK imports
import java.util.List;
import java.util.Vector;
import java.util.logging.Logger;


/**
 * 
 * The new Apache OODT workflow style of processor. These processors are
 * responsible for returning the set of underlying tasks, or conditions that can
 * run. A sequential version will return only a single sub-processor (condition
 * or task, or even workflow); a parallel version will return many sub
 * processors to run.
 * 
 * @since Apache OODT 0.4.
 * 
 * @author mattmann
 * @author bfoster
 * 
 */
public abstract class WorkflowProcessor implements WorkflowProcessorListener,
    Comparable<WorkflowProcessor> {

  private static final Logger LOG = Logger.getLogger(WorkflowProcessor.class
      .getName());

  private WorkflowInstance workflowInstance;
  private WorkflowProcessor preConditions;
  private WorkflowProcessor postConditions;
  private List<String> excusedSubProcessorIds; // FIXME: read this in
                                               // PackagedRepo: flow through
                                               // instance
  private List<WorkflowProcessor> subProcessors;
  private List<WorkflowProcessorListener> listeners;
  private int minReqSuccessfulSubProcessors; // FIXME: read this in
                                             // PackagedRepo: flow through
                                             // instance
  /**
   * What must succeed before this processor may run.
   *
   * Preconditions gate a task, and the tasks in turn gate the workflow's
   * post-conditions, which is what makes the three phases ordered rather than
   * merely present.
   *
   * Held as the processors themselves rather than as a verdict, so that asking
   * recomputes from their current states. Work is discovered from the instance
   * repository, not by walking down from a parent, so a task arrives at the
   * querier on its own and has to be able to see what gates it; nothing else
   * on the path knows.
   */
  private List<WorkflowProcessor> prerequisites;

  protected WorkflowLifecycleManager lifecycleManager;
  protected WorkflowProcessorHelper helper;
  protected WorkflowStateTransitioner transitioner;

  public WorkflowProcessor(WorkflowLifecycleManager lifecycleManager,
      WorkflowInstance workflowInstance) {
    this.subProcessors = new Vector<WorkflowProcessor>();
    this.listeners = new Vector<WorkflowProcessorListener>();
    this.excusedSubProcessorIds = new Vector<String>();
    this.prerequisites = new Vector<WorkflowProcessor>();
    this.minReqSuccessfulSubProcessors = -1;
    this.lifecycleManager = lifecycleManager;
    this.workflowInstance = workflowInstance;
    this.helper = new WorkflowProcessorHelper(lifecycleManager);
    this.transitioner = new WorkflowStateTransitioner(lifecycleManager);
    WorkflowState initState = helper.getLifecycleForProcessor(this)
        .createState("Null", "initial",
            "Instance created by workflow processor.");
    this.workflowInstance.setState(initState);
  }

  /**
   * @return the workflowInstance
   */
  public WorkflowInstance getWorkflowInstance() {
    return workflowInstance;
  }

  /**
   * @param workflowInstance
   *          the workflowInstance to set
   */
  public void setWorkflowInstance(WorkflowInstance workflowInstance) {
    this.workflowInstance = workflowInstance;
  }

  /**
   * @return the excusedSubProcessorIds
   */
  public List<String> getExcusedSubProcessorIds() {
    return excusedSubProcessorIds;
  }

  /**
   * @param excusedSubProcessorIds
   *          the excusedSubProcessorIds to set
   */
  public void setExcusedSubProcessorIds(List<String> excusedSubProcessorIds) {
    this.excusedSubProcessorIds = excusedSubProcessorIds;
  }

  /**
   * @return the subProcessors
   */
  public List<WorkflowProcessor> getSubProcessors() {
    return subProcessors;
  }

  /**
   * @param subProcessors
   *          the subProcessors to set
   */
  public void setSubProcessors(List<WorkflowProcessor> subProcessors) {
    this.subProcessors = subProcessors;
  }

  /**
   * @return the listeners
   */
  public List<WorkflowProcessorListener> getListeners() {
    return listeners;
  }

  /**
   * @param listeners
   *          the listeners to set
   */
  public void setListeners(List<WorkflowProcessorListener> listeners) {
    this.listeners = listeners;
  }

  /**
   * @return the minReqSuccessfulSubProcessors
   */
  public int getMinReqSuccessfulSubProcessors() {
    return minReqSuccessfulSubProcessors;
  }

  /**
   * @param minReqSuccessfulSubProcessors
   *          the minReqSuccessfulSubProcessors to set
   */
  public void setMinReqSuccessfulSubProcessors(int minReqSuccessfulSubProcessors) {
    this.minReqSuccessfulSubProcessors = minReqSuccessfulSubProcessors;
  }

  /**
   * @return the lifecycleManager
   */
  public WorkflowLifecycleManager getLifecycleManager() {
    return lifecycleManager;
  }

  /**
   * @param lifecycleManager
   *          the lifecycleManager to set
   */
  public void setLifecycleManager(WorkflowLifecycleManager lifecycleManager) {
    this.lifecycleManager = lifecycleManager;
  }

  /**
   * @return the preConditions
   */
  public WorkflowProcessor getPreConditions() {
    return preConditions;
  }

  /**
   * @param preConditions
   *          the preConditions to set
   */
  public void setPreConditions(WorkflowProcessor preConditions) {
    this.preConditions = preConditions;
  }

  /**
   * @return the postConditions
   */
  public WorkflowProcessor getPostConditions() {
    return postConditions;
  }

  /**
   * @param postConditions
   *          the postConditions to set
   */
  public void setPostConditions(WorkflowProcessor postConditions) {
    this.postConditions = postConditions;
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.lang.Comparable#compareTo(java.lang.Object)
   */
  @Override
  public int compareTo(WorkflowProcessor workflowProcessor) {
    return this.getWorkflowInstance().getPriority()
        .compareTo(workflowProcessor.getWorkflowInstance().getPriority());
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowProcessorListener#notifyChange
   * (org.apache.oodt.cas.workflow.engine.WorkflowProcessor,
   * org.apache.oodt.cas.workflow.engine.ChangeType)
   */
  /**
   * Sets this processor's state and tells anyone listening.
   *
   * Use this rather than reaching through to the instance when the change
   * should be seen by the rest of the tree; setting it on the instance
   * directly is silent.
   *
   * @param state
   *          The state to move to.
   */
  public void setState(WorkflowState state) {
    this.workflowInstance.setState(state);
    this.notifyChange(this, ChangeType.STATE);
  }

  /**
   * Reacts to a change below, then passes it on.
   *
   * The listener machinery has been here since the port but nothing ever fired
   * it and nothing was ever registered, so it did nothing at all. A parent now
   * listens to its children, and a child changing state makes the parent work
   * out what that means immediately rather than waiting for the querier to
   * come round again -- which, on a nested workflow, could cost a pass per
   * level.
   *
   * This is a shortcut, not a source of truth. The parent still recomputes
   * from its children through {@link #isDone()} every time, so a notification
   * that is missed, duplicated or delivered out of order costs latency and
   * nothing else. Given that the alternative is a cached verdict that can go
   * stale, and that a stale verdict here means reporting a failed workflow as
   * successful, the recomputation is worth keeping.
   */
  @Override
  public void notifyChange(WorkflowProcessor processor, ChangeType changeType) {
    if (processor != this && ChangeType.STATE.equals(changeType)) {
      this.nextState();
    }
    for (WorkflowProcessorListener listener : this.getListeners()) {
      listener.notifyChange(this, changeType);
    }
  }

  public synchronized List<TaskProcessor> getRunnableWorkflowProcessors() {
    Vector<TaskProcessor> runnableTasks = new Vector<TaskProcessor>();

    // evaluate pre-conditions
    if (!this.passedPreConditions()) {
      // Conditions can gate this processor without being held by it: they run
      // as instances of their own, discovered from the repository like any
      // other work. There is then nothing here to hand back, and asking for
      // it used to throw, killing the querier thread.
      if (this.getPreConditions() != null) {
        for (WorkflowProcessor subProcessor : this.getPreConditions()
            .getRunnableSubProcessors()) {
          for (TaskProcessor tp : subProcessor.getRunnableWorkflowProcessors()) {
            runnableTasks.add(tp);
          }
        }
      }

    } else if (this.isDone().getName().equals("ResultsBail")) {
      for (WorkflowProcessor subProcessor : this.getRunnableSubProcessors()) {
        runnableTasks.addAll(subProcessor.getRunnableWorkflowProcessors());
      }
    } else if (!this.passedPostConditions()) {
      if (this.getPostConditions() != null) {
        for (WorkflowProcessor subProcessor : this.getPostConditions()
            .getRunnableSubProcessors()) {
          for (TaskProcessor tp : subProcessor.getRunnableWorkflowProcessors()) {
            runnableTasks.add(tp);
          }
        }
      }

    }

    return runnableTasks;
  }

  /**
   * Advances this WorkflowProcessor to its next {@link WorkflowState}.
   */
  public synchronized void nextState() {
    if (this.workflowInstance != null
        && this.workflowInstance.getState() != null) {

      // A lifecycle that says where its states can go decides this itself.
      // The chain below is what happens when it says nothing, which is the
      // case for every lifecycle file written before transitions could be
      // declared, so those deployments keep the behaviour they have.
      if (this.transitioner != null
          && this.transitioner.declaresTransitions(this.workflowInstance)) {
        WorkflowState declaredNext = this.transitioner
            .nextState(this.workflowInstance);
        if (declaredNext != null) {
          this.setState(declaredNext);
        }
        return;
      }

      WorkflowState currState = this.workflowInstance.getState();
      WorkflowState nextState = null;
      if (currState.getName().equals("Null")) {
        nextState = this.helper.getLifecycleForProcessor(this).createState(
            "Loaded",
            "initial",
            "Workflow Processor: nextState: " + "loading workflow instance: ["
                + this.workflowInstance.getId() + "]");
      } else if (currState.getName().equals("Loaded")) {
        // "waiting", which is where the lifecycle files Queued. Naming
        // "initial" here put the state in a stage that does not contain it,
        // so a queued workflow reported the stage before the one it was
        // actually in: percent complete was understated, and a query for the
        // instances that are waiting did not return them.
        nextState = this.helper.getLifecycleForProcessor(this).createState(
            "Queued",
            "waiting",
            "Workflow Processor: nextState: " + "queueing instance: ["
                + this.workflowInstance.getId() + "]");
      } else if (currState.getName().equals("Queued")) {
        if (!this.passedPreConditions()) {
          nextState = this.helper.getLifecycleForProcessor(this).createState(
              "PreConditionEval",
              "running",
              "Workflow Processor: nextState: "
                  + "running preconditiosn for workflow instance: ["
                  + this.workflowInstance.getId() + "]");
        } else {
          nextState = stateFromSubProcessors();
        }
      } else if (currState.getName().equals("PreConditionEval")) {
        // The way back out. A processor waiting on something moves here, and
        // until now nothing moved it on again: TaskProcessor offers itself as
        // runnable from Loaded, Queued or PreConditionSuccess, and this is
        // none of those, so whatever it was waiting for could pass and the
        // work would still never run. It showed up as an intermittent hang,
        // because a processor only lands here at all if it is dispositioned
        // while it still has something to wait for.
        if (this.passedPreConditions()) {
          nextState = this.helper.getLifecycleForProcessor(this).createState(
              "PreConditionSuccess",
              "transition",
              "Workflow Processor: nextState: " + "preconditions passed for "
                  + "workflow instance: [" + this.workflowInstance.getId()
                  + "]");
        }
      } else if (currState.getName().equals("Executing")) {
        nextState = stateFromSubProcessors();
      }
      else if(currState.getName().equals("ExecutionComplete")){
        nextState = this.helper.getLifecycleForProcessor(this).createState(
            "Success",
            "done",
            "Workflow Processor: nextState: " + "workflow instance: ["
                + this.workflowInstance.getId() + "] completed successfully");        
      }

      if (nextState != null) {
        this.setState(nextState);
      }

    } else {
      this.setState(helper.getLifecycleForProcessor(this)
          .createState(
              "Unknown",
              "holding",
              "The Workflow Processor for instance : ["
                  + this.getWorkflowInstance().getId() + "] "
                  + "had a null state"));
    }
  }
  
  /**
   * Turns what the sub-processors have done into this processor's next state.
   *
   * Only success was ever acted on here. A processor whose children had failed
   * got no transition at all and sat where it was, so a workflow with a failed
   * task never finished and never reported anything -- it simply stopped. That
   * is the backward status calculation Brian Foster asked for on the umbrella
   * issue: a parent has to be able to move to a failed state, not only to a
   * successful one.
   *
   * @return The state to move to, or null to stay put, which is the right
   *         answer while children are still working.
   */
  private WorkflowState stateFromSubProcessors() {
    WorkflowState result = this.isDone();

    if (result.getName().equals("ResultsSuccess")) {
      return this.helper.getLifecycleForProcessor(this).createState(
          "Success",
          "done",
          "Workflow Processor: nextState: " + "workflow instance: ["
              + this.workflowInstance.getId() + "] completed successfully");
    }

    if (result.getName().equals("ResultsFailure")) {
      return this.helper.getLifecycleForProcessor(this).createState(
          "Failure",
          "done",
          "Workflow Processor: nextState: " + "workflow instance: ["
              + this.workflowInstance.getId() + "] failed: "
              + result.getMessage());
    }

    // ResultsBail: children are still working, so there is nothing to do yet.
    return null;
  }

  /**
   * Evaluates whether or not this processor's {@link WorkflowState}
   * is in any of the provided state names.
   * 
   * @param states The names of states to check this processor's 
   * {@link WorkflowState} against.
   * 
   * @return True, if any of the state names provided is the name of
   * this processor's internal {@link WorkflowState}, False otherwise.
   */
  public boolean isAnyState(String... states) {
    for (String state : states) {
      if (this.getWorkflowInstance().getState().getName().equals(state)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Evaluates whether or not this processor's {@link org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleStage}
   * is in any of the provided category names.
   * 
   * @param categories The names of categories to check this processor's 
   * {@link org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleStage} against.
   * 
   * @return True, if any of the category names provided is the name of
   * this processor's internal {@link org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleStage}, False otherwise.
   */
  public boolean isAnyCategory(String... categories) {
    for (String category : categories) {
      if (this.getWorkflowInstance().getState().getCategory().getName()
          .equals(category)) {
        return true;
      }
    }

    return false;
  }  

  /**
   * @return what must succeed before this processor runs, never null
   */
  public List<WorkflowProcessor> getPrerequisites() {
    return prerequisites;
  }

  /**
   * Declares what must succeed before this processor may run.
   *
   * @param prerequisites
   *          The processors to wait on, wherever they happen to run.
   */
  public void setPrerequisites(
      List<WorkflowProcessor> prerequisites) {
    this.prerequisites = prerequisites != null
        ? prerequisites : new Vector<WorkflowProcessor>();
  }

  protected boolean passedPreConditions() {
    // Recomputed every time rather than read from a verdict someone had to
    // remember to update. Each prerequisite runs as its own instance,
    // dispositioned by the querier like any other work, so its state is the
    // ground truth and there is nothing to keep in step.
    for (WorkflowProcessor condition : this.prerequisites) {
      WorkflowState state = condition.getWorkflowInstance().getState();
      if (state == null || !"Success".equals(state.getName())) {
        return false;
      }
    }

    if (this.getPreConditions() != null) {
      return this.getPreConditions().getWorkflowInstance().getState().getName()
          .equals("Success");
    } else {
      return true;
    }
  }

  protected boolean passedPostConditions() {
    if (this.getPostConditions() != null) {
      return this.getPostConditions().getWorkflowInstance().getState()
          .getName().equals("Success");
    } else {
      return true;
    }
  }

  /**
   * First checks to see if any of this Processor's {@link #subProcessors} have
   * arrived in a state within the done category. If so the method determines if
   * any of the done {@link #subProcessors} are in Failure state. If so, the
   * method compares the number of Failed sub-processors against
   * {@link #minReqSuccessfulSubProcessors}, and if it is greater than it,
   * returns a ResultsFailure {@link WorkflowState}. Otherwise, the method scans
   * the failed sub-processors, and checks to see if all of them have been
   * excused. If they haven't, then a ResultFailure state is returned. Finally,
   * the method checks to ensure that all sub processors are in the done
   * category. If they are, a ResultsSuccess {@link WorkflowState} is returned,
   * otherwise, a ResultsBail state is returned.
   * 
   * @return A {@link WorkflowState}, according to the method description.
   */
  protected WorkflowState isDone() {
    if (this.helper.containsCategory(this.getSubProcessors(), "done")) {
      List<WorkflowProcessor> failedSubProcessors = this.helper
          .getWorkflowProcessorsByState(this.getSubProcessors(), "Failure");
      if (this.minReqSuccessfulSubProcessors != -1
          && failedSubProcessors.size() > (this.getSubProcessors().size() - this.minReqSuccessfulSubProcessors)) {
        return lifecycleManager.getDefaultLifecycle().createState(
            "ResultsFailure", "results",
            "More than the allowed number of sub-processors failed");
      }
      for (WorkflowProcessor subProcessor : failedSubProcessors) {
        if (!this.getExcusedSubProcessorIds().contains(
            subProcessor.getWorkflowInstance().getId())) {
          return lifecycleManager.getDefaultLifecycle().createState(
              "ResultsFailure",
              "results",
              "Sub processor: [" + subProcessor.getWorkflowInstance().getId()
                  + "] failed.");
        }
      }
      if (this.helper
          .allProcessorsSameCategory(this.getSubProcessors(), "done")) {
        return lifecycleManager.getDefaultLifecycle().createState(
            "ResultsSuccess",
            "results",
            "Workflow Processor: processing instance id: ["
            + workflowInstance.getId() + "] is Done.");
      }
    }
    return lifecycleManager.getDefaultLifecycle().createState(
        "ResultsBail",
        "results",
        "All sub-processors for Workflow Processor handling workflow id: ["
            + workflowInstance.getId() + "] are " + "not complete");
  }

  /**
   * This is the core method of the WorkflowProcessor class in the new Wengine
   * style workflows. Instead of requiring that a processor actually walk
   * through the underlying {@link org.apache.oodt.cas.workflow.structs.Workflow}, these style WorkflowProcessors
   * actually require their implementing sub-classes to return the current set
   * of Runnable sub-processors (which could be tasks, conditions, even
   * {@link org.apache.oodt.cas.workflow.structs.Workflow}s themselves.
   * 
   * The Parallel sub-class returns a list of task or condition processors that
   * are able to run at a given time. The Sequential sub-class returns only a
   * single task or condition processor to run, and so forth.
   * 
   * @return The list of WorkflowProcessors able to currently run.
   */
  protected abstract List<WorkflowProcessor> getRunnableSubProcessors();

  protected abstract void handleSubProcessorMetadata(
      WorkflowProcessor workflowProcessor);

}
