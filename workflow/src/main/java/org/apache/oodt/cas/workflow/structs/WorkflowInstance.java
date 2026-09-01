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

package org.apache.oodt.cas.workflow.structs;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.commons.util.DateConvert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.Date;

/**
 * A WorkflowInstance is an instantiation of the abstract description of a
 * Workflow provided by the {@link Workflow} class. WorkflowInstances have
 * status, and in general are data structures intended to be used as a means for
 * monitoring the status of an executing {@link Workflow}.
 * 
 * As of Apache OODT 0.4, the internal {@link Workflow} implementation uses
 * {@link ParentChildWorkflow}, introduced as part of OODT-70, and the
 * PackagedWorkflowRepository. {@link Workflow} instances given to the class
 * will automatically convert to {@link ParentChildWorkflow} implementations
 * internally, and the existing {@link #getWorkflow()} and
 * {@link #setWorkflow(Workflow)} methods have been deprecated in favor of
 * {@link #getParentChildWorkflow()} and
 * {@link #setParentChildWorkflow(ParentChildWorkflow)} which will supersede
 * those methods, and eventually turn into their concrete implementations.
 * 
 * In addition, as of Apache OODT 0.4 the internal {@link #state} member
 * variable now uses {@link WorkflowState} for representation. This requires the
 * use of {@link org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycle} which has now moved from being simply a UI
 * utility class for the Worklow Monitor web application to actually being fully
 * integrated with the Workflow Manager. For backwards compatibility the
 * {@link #setStatus(String)} and {@link #getStatus()} methods are still
 * supported, but are deprecated. Developers using this class should move
 * towards using {@link #setState(WorkflowState)} and {@link #getState()}.
 * 
 * @author mattmann
 * @author bfoster
 * @version $Revision$
 * 
 */
public class WorkflowInstance {

  private static final Logger logger = LoggerFactory.getLogger(WorkflowInstance.class);

  /**
   * The workflow this instance runs, stored as given.
   *
   * This used to be declared as, and forcibly converted to, a
   * ParentChildWorkflow, so every instance carried the graph model whether or
   * not the engine handling it had any use for one. Engines that need the
   * graph obtain it through {@link #getParentChildWorkflow()}, which derives
   * it on demand, so the shared structure stays free of engine-specific types
   * while both engines keep working.
   */
  private Workflow workflow;

  /**
   * Memoised graph view of {@link #workflow}. Derived, never authoritative:
   * callers hold on to it and read through it repeatedly, so the same object
   * is handed out each time rather than a fresh wrapper per call.
   */
  private transient ParentChildWorkflow graphView;

  private String id;

  private WorkflowState state;

  private String currentTaskId;

  private Date startDate;

  private Date endDate;

  private Metadata sharedContext;

  private Priority priority;

  private int timesBlocked;

  private String waitingOn;

  /**
   * Default Constructor.
   * 
   */
  public WorkflowInstance() {
    this(null, null, null, null, new Date(), null, new Metadata(),
        0, Priority.getDefault());
  }

  public WorkflowInstance(Workflow workflow, String id, WorkflowState state,
      String currentTaskId, Date startDate, Date endDate, 
      Metadata sharedContext, int timesBlocked, Priority priority) {
    this.workflow = workflow != null ? workflow : new Workflow();
    this.id = id;
    this.state = state;
    this.currentTaskId = currentTaskId;
    this.startDate = startDate;
    this.endDate = endDate;
    this.sharedContext = sharedContext;
    this.timesBlocked = timesBlocked;
    this.priority = priority;
  }

  /**
   * @return the id
   */
  public String getId() {
    return id;
  }

  /**
   * @param id
   *          the id to set
   */
  public void setId(String id) {
    this.id = id;
  }

  /**
   * @return the status
   */
  @Deprecated
  public String getStatus() {
    return state != null ? state.getName() : "Null";
  }

  /**
   * Sets the current {@link WorkflowState} to the provided status.
   * 
   * @param status
   *          The provided status to set.
   */
  @Deprecated
  public void setStatus(String status) {
    WorkflowState state = new WorkflowState();
    state.setName(status);
    // Through setState, so there is one way for this instance's state to
    // change. Assigning the field directly meant a status set by name
    // skipped whatever setState does -- recording when the instance
    // finished, for one -- and the two paths drifted apart silently.
    setState(state);
    logger.debug("Workflow state updated to: {}", state.getName());
  }

  /**
   * @return the state
   */
  public WorkflowState getState() {
    return state;
  }

  /**
   * @param state
   *          the state to set
   */
  public void setState(WorkflowState state) {
    this.state = state;
    stampEndDateIfFinished(state);
  }

  /**
   * Records when this instance finished, the first time it is set finished.
   *
   * <p>
   * A wall clock is the difference between two times, and the queue-based
   * engine recorded only the start: nothing in it ever set an end date, so
   * everything downstream had nothing to subtract from. Finished work showed
   * no elapsed time at all, or read as though it were still running.
   * </p>
   *
   * <p>
   * Done here rather than where instances are written because there is more
   * than one writer -- the processor queue, the task querier and the engine
   * each persist -- and only one place where a workflow becomes finished.
   * Reaching a state in the lifecycle's done stage is what finishing is.
   * </p>
   *
   * <p>
   * Set once. The end of a workflow is when it first finished, not when
   * something last wrote it down, so an engine that stamps its own end date
   * keeps it and a state written twice does not move it.
   * </p>
   */
  private void stampEndDateIfFinished(WorkflowState state) {
    if (state == null || state.getCategory() == null
        || !"done".equals(state.getCategory().getName())) {
      return;
    }
    if (this.endDate != null) {
      return;
    }
    this.endDate = new Date();
  }

  /**
   * @return the workflow
   */
  @Deprecated
  public Workflow getWorkflow() {
    return workflow;
  }

  /**
   * @param workflow
   *          the workflow to set
   */
  @Deprecated
  public void setWorkflow(Workflow workflow) {
    // Stored as given. No conversion here: whether this instance needs a graph
    // is the engine's concern, not the structure's.
    this.workflow = workflow != null ? workflow : new Workflow();
    this.graphView = null;
  }

  /**
   * 
   * @return The workflow, with its parent/child relationships.
   */
  public ParentChildWorkflow getParentChildWorkflow() {
    if (this.workflow instanceof ParentChildWorkflow) {
      return (ParentChildWorkflow) this.workflow;
    }
    if (this.graphView == null) {
      this.graphView = new ParentChildWorkflow(
          this.workflow != null ? this.workflow : new Workflow());
    }
    return this.graphView;
  }

  /**
   * Sets the Parent Child workflow.
   * 
   * @param workflow
   *          The workflow to set.
   */
  public void setParentChildWorkflow(ParentChildWorkflow workflow) {
    // A graph-shaped workflow is still just the workflow; store it directly so
    // the view and the stored value cannot drift apart.
    this.workflow = workflow;
    this.graphView = workflow;
  }

  /**
   * @return the currentTaskId
   */
  public String getCurrentTaskId() {
    return currentTaskId;
  }

  /**
   * @param currentTaskId
   *          the currentTaskId to set
   */
  public void setCurrentTaskId(String currentTaskId) {
    this.currentTaskId = currentTaskId;
  }

  /**
   * @return the sharedContext
   */
  public Metadata getSharedContext() {
    return sharedContext;
  }

  /**
   * @param sharedContext
   *          the sharedContext to set
   */
  public void setSharedContext(Metadata sharedContext) {
    this.sharedContext = sharedContext;
  }

  /**
   * @return the priority
   */
  public Priority getPriority() {
    return priority;
  }

  /**
   * @param priority
   *          the priority to set
   */
  public void setPriority(Priority priority) {
    this.priority = priority;
  }

  /**
   * Convenience method to format and return the
   *  as a {@link Date}.
   * 
   * @return {@link Date} representation of
   *         {@link #getCurrentTaskStartDateTimeIsoStr()}.
   */
  public Date getCreationDate() {
    return this.startDate;
  }

  /**
   * Convenience method to format and return the
   *  as a {@link Date}.
   * 
   * @return {@link Date} representation of
   *         {@link #getCurrentTaskEndDateTimeIsoStr()}.
   */
  public Date getFinishDate() {
    return this.endDate;
  }

  /**
   * @return the startDate
   */
  public Date getStartDate() {
    return startDate;
  }

  /**
   * @param startDate
   *          the startDate to set
   */
  public void setStartDate(Date startDate) {
    this.startDate = startDate;
  }

  /**
   * @return the endDate
   */
  public Date getEndDate() {
    return endDate;
  }

  /**
   * @param endDate
   *          the endDate to set
   */
  public void setEndDate(Date endDate) {
    this.endDate = endDate;
  }

  /**
   * @return the endDateTimeIsoStr
   */
  @Deprecated
  public String getEndDateTimeIsoStr() {
    return this.endDate != null ? DateConvert.isoFormat(this.endDate) : null;
  }

  /**
   * @param endDateTimeIsoStr
   *          the endDateTimeIsoStr to set
   */
  @Deprecated
  public void setEndDateTimeIsoStr(String endDateTimeIsoStr) {
    if (endDateTimeIsoStr != null && !endDateTimeIsoStr.equals("")) {
      try {
        this.endDate = DateConvert.isoParse(endDateTimeIsoStr);
      } catch (ParseException e) {
        logger.error("Error when parsing end time: {}", e.getMessage());
        // fail silently besides this: it's just a setter
      }
    }
  }

  /**
   * @return the startDateTimeIsoStr
   */
  @Deprecated
  public String getStartDateTimeIsoStr() {
    return this.startDate != null ? DateConvert.isoFormat(this.startDate)
        : null;
  }

  /**
   * @param startDateTimeIsoStr
   *          the startDateTimeIsoStr to set
   */
  @Deprecated
  public void setStartDateTimeIsoStr(String startDateTimeIsoStr) {
    if (startDateTimeIsoStr != null && !startDateTimeIsoStr.equals("")) {
      try {
        this.startDate = DateConvert.isoParse(startDateTimeIsoStr);
      } catch (ParseException e) {
        logger.error("Error when parsing start time: {}", e.getMessage());
        // fail silently besides this: it's just a setter
      }
    }
  }

  /**
   * @return the currentTaskEndDateTimeIsoStr
   */
  @Deprecated
  public String getCurrentTaskEndDateTimeIsoStr() {
    return this.getTaskById(currentTaskId) != null ? 
        (this.getTaskById(currentTaskId).getEndDate() != null ? 
            DateConvert.isoFormat(this.getTaskById(currentTaskId).getEndDate())
        : null):null;
  }

  /**
   * @param currentTaskEndDateTimeIsoStr
   *          the currentTaskEndDateTimeIsoStr to set
   */
  @Deprecated
  public void setCurrentTaskEndDateTimeIsoStr(
      String currentTaskEndDateTimeIsoStr) {
    if (currentTaskEndDateTimeIsoStr != null
        && !currentTaskEndDateTimeIsoStr.equals("") && 
        this.getTaskById(currentTaskId) != null) {
      try {
        this.getTaskById(currentTaskId).
          setEndDate(DateConvert.isoParse(currentTaskEndDateTimeIsoStr));
      } catch (ParseException e) {
        logger.error("Error when parsing time: {}", e.getMessage());
        // fail silently besides this: it's just a setter
      }
    }
  }

  /**
   * @return the currentTaskStartDateTimeIsoStr
   */
  @Deprecated
  public String getCurrentTaskStartDateTimeIsoStr() {
    return this.getTaskById(currentTaskId) != null ? 
        (this.getTaskById(currentTaskId).getStartDate() != null ? DateConvert
        .isoFormat(this.getTaskById(currentTaskId).getStartDate()) : null):null;
  }

  /**
   * @param currentTaskStartDateTimeIsoStr
   *          the currentTaskStartDateTimeIsoStr to set
   */
  @Deprecated
  public void setCurrentTaskStartDateTimeIsoStr(
      String currentTaskStartDateTimeIsoStr) {
    if (currentTaskStartDateTimeIsoStr != null
        && !currentTaskStartDateTimeIsoStr.equals("") && 
        this.getTaskById(currentTaskId) != null) {
      try {
        this.getTaskById(currentTaskId).setStartDate(DateConvert
            .isoParse(currentTaskStartDateTimeIsoStr));
      } catch (ParseException e) {
        logger.error("Error when parsing time: {}", e.getMessage());
        // fail silently besides this: it's just a setter
      }
    }
  }
  
  /**
   * Returns the currently executing {@link WorkflowTask}
   * part of this instance.
   * 
   * @return The currently executing {@link WorkflowTask}
   * part of this instance.
   */
  public WorkflowTask getCurrentTask(){
    return getTaskById(currentTaskId);
  }
  

  /**
   * @return the timesBlocked
   */
  public int getTimesBlocked() {
    return timesBlocked;
  }

  /**
   * @param timesBlocked the timesBlocked to set
   */
  /**
   * Record that this instance was put off rather than run.
   *
   * <p>
   * The count is of deferrals, not of time: each one is an occasion when the
   * instance was looked at, found not ready, and left for later. How fast it
   * rises therefore depends on how often the engine looks, so it compares
   * within a deployment and not across them. How long something waited is a
   * different question, and the wall clock already answers it.
   * </p>
   *
   * <p>
   * Both engines record the same event through here: W1 when a pass round its
   * pre-condition wait loop finds the conditions still unsatisfied, W2 when a
   * disposition leaves a processor waiting on the same. Nothing incremented
   * this before, in either engine, so the field was written to a database
   * column, stored in a Lucene index, and shown in the UI, always as zero.
   * </p>
   */
  /**
   * Why this instance is not running, or null when it is.
   *
   * <p>
   * Whether something has been abandoned used to be inferred: not in the
   * engine's executing set, not finished, therefore lost. That is a guess
   * assembled from two places, and it cannot tell an instance nobody is
   * running from one deliberately waiting its turn -- so a queue full of
   * orderly waiting read as a deployment full of abandoned work.
   * </p>
   *
   * <p>
   * Recorded here, the reason survives a restart, which is when the question
   * is actually asked. The repository answers what the instance was waiting
   * for; the engine answers whether it still holds it. Between them there is
   * no guessing left: a reason with an engine behind it is deferred, and the
   * same reason with nothing behind it is abandoned, and now says what it was
   * waiting for when it was lost.
   * </p>
   *
   * @return a short reason such as "condition:urn:drat:MapsDone" or
   *         "task:urn:drat:RepoCrawler", or null
   */
  public String getWaitingOn() {
    return waitingOn;
  }

  public void setWaitingOn(String waitingOn) {
    this.waitingOn = waitingOn;
  }

  public void recordBlocked() {
    this.timesBlocked++;
  }

  public void setTimesBlocked(int timesBlocked) {
    this.timesBlocked = timesBlocked;
  }
  
  
  private WorkflowTask getTaskById(String taskId){
    if(this.workflow.getTasks() != null && 
        this.workflow.getTasks().size() > 0){
      for(WorkflowTask task: this.workflow.getTasks()){
        // A task can reach here with a null id -- AvroTypeFactory.getWorkflowTask
        // passes one straight through -- and this dereferenced it.
        if(task != null && taskId != null && taskId.equals(task.getTaskId())){
          return task;
        }
      }
      
      return null;
    }
    else {
      return null;
    }
  }
}
