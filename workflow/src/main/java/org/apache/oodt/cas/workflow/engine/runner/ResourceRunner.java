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

//OODT imports
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.exceptions.JobExecutionException;
import org.apache.oodt.cas.resource.structs.exceptions.JobRepositoryException;
import org.apache.oodt.cas.resource.system.ResourceManagerClient;
import org.apache.oodt.cas.resource.system.rpc.ResourceManagerFactory;
import org.apache.oodt.cas.workflow.engine.processor.TaskProcessor;
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycle;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.metadata.CoreMetKeys;
import org.apache.oodt.cas.workflow.structs.TaskJobInput;
import org.apache.oodt.cas.workflow.structs.WorkflowStatus;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

//JDK imports
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * Submits a {@link WorkflowTask} to the Resource Manager.
 *
 * Unlike {@link AsynchronousLocalEngineRunner}, which runs a task in a local
 * thread and can therefore mark it complete when that thread returns, this
 * runner hands the task to the Resource Manager and returns immediately. The
 * job outlives the call, so completion has to be observed rather than awaited:
 * submitted jobs are tracked and polled on a background monitor, which moves
 * the owning {@link org.apache.oodt.cas.workflow.structs.WorkflowInstance}
 * to its terminal state and persists it.
 *
 * @author mattmann
 * @version $Revision$
 *
 */
public class ResourceRunner extends AbstractEngineRunnerBase implements CoreMetKeys,
    WorkflowStatus {

  private static final Logger LOG = Logger.getLogger(ResourceRunner.class
      .getName());

  protected static final String DEFAULT_QUEUE_NAME = "high";

  /**
   * How often the monitor asks the Resource Manager whether outstanding jobs
   * have finished.
   */
  protected static final long DEFAULT_POLL_INTERVAL_SECONDS = 5;

  /**
   * Used when the Resource Manager cannot report its queue capacity. Allowing
   * a single outstanding submission keeps the engine moving without flooding a
   * resource manager whose capacity is unknown.
   */
  protected static final int UNKNOWN_CAPACITY_SLOTS = 1;

  protected ResourceManagerClient rClient;

  /**
   * Jobs submitted but not yet observed to finish, keyed by resource manager
   * job id. Concurrent because the monitor reads it while the engine's
   * TaskRunner thread submits into it.
   */
  private final Map<String, TaskProcessor> outstandingJobs;

  private final ScheduledExecutorService monitor;

  /**
   * Most recently submitted job. Retained for {@link #stopJob(String)}, which
   * predates multi-job tracking.
   */
  private String currentJobId;

  public ResourceRunner(URL resUrl, WorkflowInstanceRepository instRep) {
    this(resUrl, instRep, DEFAULT_POLL_INTERVAL_SECONDS);
  }

  public ResourceRunner(URL resUrl, WorkflowInstanceRepository instRep,
      long pollIntervalSeconds) {
    // Obtained from the factory rather than constructed directly, so the
    // runner speaks whichever transport the Resource Manager is configured
    // for. Hardcoding the XML-RPC client here meant this runner could not
    // talk to an Avro resource manager at all.
    this(ResourceManagerFactory.getResourceManagerClient(resUrl), instRep,
        pollIntervalSeconds);
  }

  /**
   * Takes an already-built client, so the runner can be exercised against a
   * stand-in Resource Manager rather than requiring a live one.
   */
  public ResourceRunner(ResourceManagerClient rClient,
      WorkflowInstanceRepository instRep, long pollIntervalSeconds) {
    super();
    this.rClient = rClient;
    this.instRep = instRep;
    this.outstandingJobs = new ConcurrentHashMap<String, TaskProcessor>();
    this.monitor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread t = new Thread(runnable, "ResourceRunner-job-monitor");
      t.setDaemon(true);
      return t;
    });
    this.monitor.scheduleWithFixedDelay(new JobMonitor(), pollIntervalSeconds,
        pollIntervalSeconds, TimeUnit.SECONDS);
  }

  /* (non-Javadoc)
   * @see org.apache.oodt.cas.workflow.engine.runner.EngineRunner#execute(org.apache.oodt.cas.workflow.engine.processor.TaskProcessor)
   */
  @Override
  public void execute(TaskProcessor taskProcessor) {
    Job workflowTaskJob = new Job();
    WorkflowTask workflowTask = getTaskFromProcessor(taskProcessor);
    workflowTaskJob.setName(workflowTask.getTaskId());
    workflowTaskJob
        .setJobInstanceClassName("org.apache.oodt.cas.workflow.structs.TaskJob");
    workflowTaskJob
        .setJobInputClassName("org.apache.oodt.cas.workflow.structs.TaskJobInput");
    workflowTaskJob.setLoadValue(2);
    workflowTaskJob.setQueueName(workflowTask.getTaskConfig().getProperty(
        QUEUE_NAME) != null ? workflowTask.getTaskConfig().getProperty(
        QUEUE_NAME) : DEFAULT_QUEUE_NAME);

    if (workflowTask.getTaskConfig().getProperty(TASK_LOAD) != null) {
      workflowTaskJob.setLoadValue(Integer.valueOf(workflowTask.getTaskConfig()
          .getProperty(TASK_LOAD)));
    }

    TaskJobInput in = new TaskJobInput();
    in.setDynMetadata(taskProcessor.getWorkflowInstance().getSharedContext());
    in.setTaskConfig(workflowTask.getTaskConfig());
    in.setWorkflowTaskInstanceClassName(workflowTask.getTaskInstanceClassName());

    try {
      String jobId = rClient.submitJob(workflowTaskJob, in);
      this.currentJobId = jobId;
      if (jobId != null) {
        this.outstandingJobs.put(jobId, taskProcessor);
        LOG.log(Level.INFO, "Submitted task: [" + workflowTask.getTaskName()
            + "] for instance id: ["
            + taskProcessor.getWorkflowInstance().getId()
            + "] to the resource manager as job: [" + jobId + "]");
      } else {
        // A null job id means the submission was not accepted. Without this
        // the task would sit in the running category forever, because no job
        // exists for the monitor to observe.
        failTask(taskProcessor, workflowTask,
            "Resource manager returned no job id for task: ["
                + workflowTask.getTaskName() + "]");
      }
    } catch (JobExecutionException e) {
      LOG.log(Level.WARNING,
          "Job execution exception using resource manager to execute job: Message: "
              + e.getMessage());
      failTask(taskProcessor, workflowTask,
          "Unable to submit task: [" + workflowTask.getTaskName()
              + "] to the resource manager: Message: " + e.getMessage());
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.oodt.cas.workflow.engine.EngineRunner#shutdown()
   */
  @Override
  public void shutdown() {
    this.monitor.shutdownNow();
    this.outstandingJobs.clear();
  }

  /* (non-Javadoc)
   * @see org.apache.oodt.cas.workflow.engine.runner.EngineRunner#hasOpenSlots(org.apache.oodt.cas.workflow.engine.processor.TaskProcessor)
   */
  @Override
  public boolean hasOpenSlots(TaskProcessor taskProcessor) {
    try {
      int capacity = rClient.getJobQueueCapacity();
      int queued = rClient.getJobQueueSize();
      if (capacity <= 0) {
        // Capacity unknown or unreported; fall back rather than refusing all
        // work, which is what made this runner unusable.
        return this.outstandingJobs.size() < UNKNOWN_CAPACITY_SLOTS;
      }
      return queued < capacity;
    } catch (JobRepositoryException e) {
      LOG.log(Level.WARNING,
          "Unable to read resource manager queue capacity: Message: "
              + e.getMessage());
      return false;
    }
  }

  /* (non-Javadoc)
   * @see org.apache.oodt.cas.workflow.engine.runner.EngineRunner#setInstanceRepository(org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository)
   */
  @Override
  public void setInstanceRepository(WorkflowInstanceRepository instRep) {
    this.instRep = instRep;
  }

  /**
   * Number of jobs submitted and not yet observed to finish.
   */
  protected int getOutstandingJobCount() {
    return this.outstandingJobs.size();
  }

  protected boolean safeCheckJobComplete(String jobId) {
    try {
      return rClient.isJobComplete(jobId);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Exception checking completion status for job: ["
          + jobId + "]: Messsage: " + e.getMessage());
      return false;
    }
  }

  protected boolean stopJob(String jobId) {
    if (this.rClient != null && jobId != null) {
      if (!this.rClient.killJob(jobId)) {
        LOG.log(Level.WARNING, "Attempt to kill " + "current resmgr job: ["
            + jobId + "]: failed");
        return false;
      } else {
        this.outstandingJobs.remove(jobId);
        return true;
      }
    } else {
      return false;
    }
  }

  /**
   * Moves a task to its terminal state and persists the owning instance,
   * mirroring what {@link AsynchronousLocalEngineRunner} does when a local
   * task returns.
   */
  private void completeTask(TaskProcessor taskProcessor, String msg) {
    WorkflowLifecycle lifecycle = getLifecycle(taskProcessor);
    WorkflowState state = lifecycle.createState("ExecutionComplete",
        "transition", msg);
    taskProcessor.setState(state);
    persist(taskProcessor.getWorkflowInstance());
  }

  private void failTask(TaskProcessor taskProcessor, WorkflowTask task,
      String msg) {
    LOG.log(Level.WARNING, msg);
    WorkflowLifecycle lifecycle = getLifecycle(taskProcessor);
    WorkflowState state = lifecycle.createState("Failure", "done", msg);
    taskProcessor.setState(state);
    persist(taskProcessor.getWorkflowInstance());
  }

  /**
   * Polls the Resource Manager for jobs this runner submitted and advances the
   * owning workflow instances when they finish.
   */
  private class JobMonitor implements Runnable {

    @Override
    public void run() {
      // Iterating the entry set of a ConcurrentHashMap is safe against
      // concurrent submission; removal during iteration is likewise supported.
      for (Map.Entry<String, TaskProcessor> entry : outstandingJobs.entrySet()) {
        String jobId = entry.getKey();
        TaskProcessor taskProcessor = entry.getValue();
        try {
          if (safeCheckJobComplete(jobId)) {
            outstandingJobs.remove(jobId);
            completeTask(taskProcessor, "Resource manager job: [" + jobId
                + "] completed");
          }
        } catch (Exception e) {
          // A failure observing one job must not stop the monitor, or every
          // other outstanding job would stall behind it.
          LOG.log(Level.WARNING, "Exception advancing job: [" + jobId
              + "]: Message: " + e.getMessage());
        }
      }
    }
  }

}
