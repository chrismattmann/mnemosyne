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

package org.apache.oodt.cas.workflow.engine.processor;

//JDK imports
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.metadata.Metadata;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;

import java.util.Calendar;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import java.util.Date;
import java.util.List;
import java.util.Vector;

//OODT imports

/**
 * 
 * WorkflowProcessor which handles running task workflows.
 * 
 * @author bfoster
 * @author mattmann
 * 
 * @version $Revision$
 */
public class TaskProcessor extends WorkflowProcessor {

  public static final double DOUBLE = 0.1;
  public static final int INT = 60;

  /** Seconds to wait before looking at a blocked task again, by default. */
  public static final long DEFAULT_BLOCK_SECONDS = 2L;
  private Class<? extends WorkflowTaskInstance> instanceClass;
  private String jobId;
  
  public TaskProcessor(WorkflowLifecycleManager lifecycleManager, WorkflowInstance instance) {
    super(lifecycleManager, instance);
  }

  public Class<? extends WorkflowTaskInstance> getInstanceClass() {
    return this.instanceClass;
  }

  public void setJobId(String jobId) {
    this.jobId = jobId;
  }

  public String getJobId() {
    return this.jobId;
  }

  public void setInstanceClass(
      Class<? extends WorkflowTaskInstance> instanceClass) {
    this.instanceClass = instanceClass;
  }

  @Override
  public void setWorkflowInstance(WorkflowInstance instance) {
    instance.setPriority(Priority
        .getPriority(instance.getPriority().getValue() + DOUBLE));
    super.setWorkflowInstance(instance);
  }

  @Override
  public List<TaskProcessor> getRunnableWorkflowProcessors() {
    List<TaskProcessor> tps = super.getRunnableWorkflowProcessors();
    if (tps.size() == 0) {
      if (this.getWorkflowInstance().getState().getName().equals("Blocked")) {
        long elapsedSeconds = secondsBlocked();
        // The wait being over is not the same as the reason for it being
        // over. This offered a blocked task once its back-off had elapsed
        // whatever its conditions said, so a task bailed because its gate had
        // not opened ran anyway a couple of minutes later -- which is a gate
        // that delays rather than one that holds.
        if (elapsedSeconds >= blockTimeElapseSeconds()
            && this.passedPreConditions()) {
          tps.add(this);
        }
      } else if (this.isAnyState("Loaded", "Queued", "PreConditionSuccess") && 
          !this.isAnyState("Executing") && this.passedPreConditions()){
        tps.add(this);
      }
    }
    return tps;
  }

  /**
   * How long this task has been blocked, in seconds.
   *
   * <p>
   * A state's start time lives on the state object and is not written to the
   * instance repository, so a manager restarted while an instance was blocked
   * rebuilds it without one. Reading it then threw, on the very path that
   * exists to describe an instance a restart left behind. Stamped when it is
   * first missed instead, so the wait runs from when this engine first saw
   * the instance blocked, which is the only thing it can honestly measure
   * from.
   * </p>
   */
  protected long secondsBlocked() {
    WorkflowState state = this.getWorkflowInstance().getState();
    Date blockedAt = state.getStartTime();
    if (blockedAt == null) {
      blockedAt = new Date();
      state.setStartTime(blockedAt);
    }
    long elapsed = (System.currentTimeMillis() - blockedAt.getTime()) / 1000L;
    return elapsed < 0 ? 0 : elapsed;
  }

  /**
   * How long to wait before looking at a blocked task again, in seconds.
   *
   * <p>
   * BlockTimeElapse has always been minutes, by way of a divide by sixty that
   * also floored the elapsed time -- so a task blocked for 119 seconds
   * counted one minute, and the default of two waited anywhere up to three.
   * Minutes are too coarse for a wait that resolves in seconds, which is what
   * a condition usually does, so BlockTimeElapseSeconds says it precisely.
   * </p>
   *
   * <p>
   * A configuration that says BlockTimeElapse still means minutes: it was
   * written meaning minutes and reinterpreting it would quietly change what
   * somebody's deployment does. Only the default moves, from two minutes to
   * two seconds -- and the coarse default was an accident of the divide
   * rather than a decision, reachable only by a lifecycle that declares a
   * transition into Blocked.
   * </p>
   */
  private static final Logger LOG =
      Logger.getLogger(TaskProcessor.class.getName());

  /** How many times a task may be put back after failing. */
  static final String MAX_RETRIES = "MaxRetries";

  /** How many of those it has used, carried on the instance itself. */
  static final String RETRIES_USED = "TaskRetriesUsed";

  /**
   * Where a task goes when it fails: back into the queue if it has retries
   * left, or Failure.
   *
   * <p>
   * A task that failed used to be finished, so one transient fault lost a
   * chunk for good. A translation whose node was restarted, or which arrived
   * while the translation service was still loading its model, is not work
   * that cannot be done; it is work that was asked at a bad moment. On a run
   * of several hundred chunks something is always being asked at a bad
   * moment.
   * </p>
   *
   * <p>
   * The engine cannot tell a transient fault from a permanent one. It sees a
   * task that did not succeed, and a missing input file fails exactly like a
   * service that was not ready yet. So the bound is the safety here rather
   * than any judgement about the failure: a task that will never work costs
   * MaxRetries attempts and then stops, and one that would have worked gets
   * another go, possibly on a different node, which is the part nothing
   * outside the scheduler can do.
   * </p>
   *
   * <p>
   * Off unless asked for. MaxRetries defaults to zero, so a deployment that
   * has not thought about this behaves exactly as it did.
   * </p>
   */
  public WorkflowState failureOrRetry(String msg) {
    int allowed = configuredMaxRetries();
    int used = retriesUsed();

    if (allowed <= 0 || used >= allowed) {
      if (allowed > 0) {
        LOG.log(Level.WARNING, "Task: [" + taskName() + "] for instance: ["
            + this.getWorkflowInstance().getId() + "] failed after " + used
            + " of " + allowed + " retries; giving up. " + msg);
      }
      return this.helper.getLifecycleForProcessor(this)
          .createState("Failure", "done", msg);
    }

    int attempt = used + 1;
    recordRetriesUsed(attempt);
    // Loudly, and counted on the instance, because a run that goes green
    // after enough retries is a broken cluster reporting success. The count
    // is what makes that visible afterwards rather than invisible.
    LOG.log(Level.WARNING, "Task: [" + taskName() + "] for instance: ["
        + this.getWorkflowInstance().getId() + "] failed and is going back in "
        + "the queue, retry " + attempt + " of " + allowed + ". " + msg);
    return this.helper.getLifecycleForProcessor(this).createState(
        "Queued", "waiting", "retry " + attempt + " of " + allowed
            + " after: " + msg);
  }

  private String taskName() {
    return this.getWorkflowInstance().getCurrentTaskId();
  }

  private int configuredMaxRetries() {
    WorkflowTaskConfiguration config = this.getWorkflowInstance()
        .getCurrentTask().getTaskConfig();
    Long configured = asLong(config.getProperty(MAX_RETRIES));
    return configured != null && configured.longValue() > 0L
        ? configured.intValue() : 0;
  }

  /**
   * Kept in the instance's own metadata rather than in memory, so a count
   * survives the manager restarting and a task cannot quietly earn a fresh
   * set of attempts by being reloaded.
   */
  private int retriesUsed() {
    Metadata context = this.getWorkflowInstance().getSharedContext();
    if (context == null) {
      return 0;
    }
    Long used = asLong(context.getMetadata(RETRIES_USED));
    return used != null ? used.intValue() : 0;
  }

  private void recordRetriesUsed(int used) {
    Metadata context = this.getWorkflowInstance().getSharedContext();
    if (context == null) {
      context = new Metadata();
      this.getWorkflowInstance().setSharedContext(context);
    }
    context.replaceMetadata(RETRIES_USED, String.valueOf(used));
  }

  protected long blockTimeElapseSeconds() {
    WorkflowTaskConfiguration config = this.getWorkflowInstance()
        .getCurrentTask().getTaskConfig();
    Long seconds = asLong(config.getProperty("BlockTimeElapseSeconds"));
    if (seconds != null) {
      return seconds.longValue();
    }
    Long minutes = asLong(config.getProperty("BlockTimeElapse"));
    if (minutes != null) {
      return minutes.longValue() * INT;
    }
    return DEFAULT_BLOCK_SECONDS;
  }

  private static Long asLong(String value) {
    if (value == null || value.trim().length() == 0) {
      return null;
    }
    try {
      return Long.valueOf(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  protected boolean hasSubProcessors() {
    return true;
  }

  @Override
  public List<WorkflowProcessor> getRunnableSubProcessors() {
    return new Vector<WorkflowProcessor>();
  }

  @Override
  public void setSubProcessors(List<WorkflowProcessor> subProcessors) {
    // not allowed
  }

  @Override
  public void handleSubProcessorMetadata(WorkflowProcessor workflowProcessor) {
    // do nothing
  }

}
