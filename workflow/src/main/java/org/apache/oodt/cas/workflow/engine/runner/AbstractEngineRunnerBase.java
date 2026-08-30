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
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.engine.processor.TaskProcessor;
import org.apache.oodt.cas.workflow.metadata.CoreMetKeys;
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycle;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;

import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports

/**
 * 
 * An abstract base class providing helper functionality to persist
 * {@link WorkflowInstance}s, to get {@link WorkflowLifecycle}s from underlying
 * {@link TaskProcessor}s, and to get {@link WorkflowTask}s from the underlying
 * {@link TaskProcessor}.
 * 
 * @author mattmann
 * @version $Revision$
 * 
 */
public abstract class AbstractEngineRunnerBase extends EngineRunner
    implements CoreMetKeys {

  protected WorkflowInstanceRepository instRep;

  /* where the workflow manager this runner belongs to can be reached */
  protected URL wmgrUrl;

  private static final Logger LOG = Logger
      .getLogger(AbstractEngineRunnerBase.class.getName());

  /**
   * Creates a new AbsractEngineRunnerBase with the provided
   * {@link WorkflowInstanceRepository}.
   *
   */
  public AbstractEngineRunnerBase() {
    this.instRep = null;
  }

  /**
   * Tells this runner where its workflow manager is, so that a task can be
   * told in turn.
   */
  public void setWorkflowManagerUrl(URL wmgrUrl) {
    this.wmgrUrl = wmgrUrl;
  }

  /**
   * Puts the standard keys into the instance's shared context before a task
   * runs.
   *
   * <p>
   * The older engine does this in IterativeWorkflowProcessorThread and the
   * wengine runners did not, so under wengine every task that reads one of
   * these got null. StdPGETaskInstance requires WorkflowInstId and refuses to
   * start without it, which meant no PGE task could run at all -- and PGE
   * tasks are most of what a deployment runs. BranchRedirector reads
   * WorkflowManagerUrl from the same place, so a nested sub-workflow only
   * worked if the caller happened to pass that key by hand when starting the
   * workflow.
   * </p>
   *
   * <p>
   * replaceMetadata rather than addMetadata, as the older engine does: the
   * context is shared for the life of an instance, so adding would accumulate
   * a fresh copy of every key each time a task ran.
   * </p>
   */
  protected void stampTaskMetadata(TaskProcessor taskProcessor,
      WorkflowTask task) {
    WorkflowInstance instance = taskProcessor.getWorkflowInstance();
    if (instance == null || instance.getSharedContext() == null) {
      return;
    }

    Metadata context = instance.getSharedContext();
    if (task != null && task.getTaskId() != null) {
      context.replaceMetadata(TASK_ID, task.getTaskId());
    }
    if (instance.getId() != null) {
      context.replaceMetadata(WORKFLOW_INST_ID, instance.getId());
      // The older engine sets the job id to the instance id too, with a TODO
      // saying so; kept the same rather than inventing a different answer.
      context.replaceMetadata(JOB_ID, instance.getId());
    }
    String hostname = getHostname();
    if (hostname != null) {
      context.replaceMetadata(PROCESSING_NODE, hostname);
    }
    if (wmgrUrl != null) {
      context.replaceMetadata(WORKFLOW_MANAGER_URL, wmgrUrl.toString());
    }
    if (instance.getParentChildWorkflow() != null) {
      if (instance.getParentChildWorkflow().getId() != null) {
        context.replaceMetadata(WORKFLOW_ID,
            instance.getParentChildWorkflow().getId());
      }
      if (instance.getParentChildWorkflow().getName() != null) {
        context.replaceMetadata(WORKFLOW_NAME,
            instance.getParentChildWorkflow().getName());
      }
    }
  }

  private String getHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException ignored) {
      return null;
    }
  }

  protected WorkflowTask getTaskFromProcessor(TaskProcessor taskProcessor) {
    if (taskProcessor.getWorkflowInstance() != null
        && taskProcessor.getWorkflowInstance().getParentChildWorkflow() != null
        && taskProcessor.getWorkflowInstance().getParentChildWorkflow()
            .getGraph() != null && 
           taskProcessor.getWorkflowInstance().getParentChildWorkflow().getGraph().getTask() != null) {
      return taskProcessor.getWorkflowInstance().getParentChildWorkflow()
            .getGraph().getTask();
    } else {
      return taskProcessor.getWorkflowInstance().getParentChildWorkflow()
                          .getTasks().get(0);
    }
  }

  protected WorkflowLifecycle getLifecycle(TaskProcessor taskProcessor) {
    return taskProcessor.getLifecycleManager().getDefaultLifecycle();
  }

  protected synchronized void persist(WorkflowInstance instance) {
    if(instRep == null) {
      return;
    }
    try {
      if (instance.getId() == null || (instance.getId().equals(""))) {
        // we have to persist it by adding it
        // rather than updating it
        instRep.addWorkflowInstance(instance);
      } else {
        // persist by update
        instRep.updateWorkflowInstance(instance);
      }
    } catch (InstanceRepositoryException e) {
      LOG.log(Level.SEVERE, e.getMessage());
      LOG.log(Level.WARNING, "Unabled to persist workflow instance: ["
          + instance.getId() + "]: Message: " + e.getMessage());
    }    
  }

}
