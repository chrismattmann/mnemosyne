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
