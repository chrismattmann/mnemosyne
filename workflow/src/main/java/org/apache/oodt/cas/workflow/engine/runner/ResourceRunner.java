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
import java.io.IOException;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.JobStatus;
import org.apache.oodt.cas.resource.structs.exceptions.JobExecutionException;
import org.apache.oodt.cas.resource.structs.exceptions.JobRepositoryException;
import org.apache.oodt.cas.resource.system.ResourceManagerClient;
import org.apache.oodt.cas.resource.system.rpc.ResourceManagerFactory;
import org.apache.oodt.cas.workflow.engine.processor.TaskProcessor;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
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

    // Before the context is read, not after: the shared context is what
    // travels to the node, and the keys stamped here are the ones a task
    // cannot start without. AsynchronousLocalEngineRunner has always called
    // this; ResourceRunner inherited it and never did, so a task that ran
    // locally failed remotely with "Must specify WorkflowInstId" from
    // PGETaskInstance -- which is most of what a deployment runs.
    stampTaskMetadata(taskProcessor, workflowTask);

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
    // The runner owns this client for its whole life and never released it.
    if (this.rClient != null) {
      try {
        this.rClient.close();
      } catch (IOException e) {
        LOG.log(Level.WARNING, "Unable to close resource manager client: "
            + e.getMessage());
      }
    }
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

  /**
   * Move a task to Executing once the Resource Manager has actually placed it
   * on a node.
   *
   * <p>
   * Nothing set this for a task. A task went to WaitingOnResources when it was
   * handed to a runner and stayed there until it finished, so "queued, waiting
   * for a free node" and "running on the GPU right now" were the same state.
   * The Resource Manager would show both nodes saturated while the Workflow
   * Manager, OPSUI and Gloss all reported nothing executing, and there was no
   * way to tell from the workflow side how much of a run was actually moving.
   * </p>
   *
   * <p>
   * Taken from what the Resource Manager reports rather than set at
   * submission, because a submitted job may sit in its queue. Marking it
   * Executing on acceptance would trade one wrong answer for another, with
   * every queued task claiming to run.
   * </p>
   */
  private void markExecuting(TaskProcessor taskProcessor, String jobId) {
    WorkflowState current = taskProcessor.getWorkflowInstance().getState();
    if (current != null && "Executing".equals(current.getName())) {
      return;
    }
    WorkflowLifecycle lifecycle = getLifecycle(taskProcessor);
    WorkflowState state = lifecycle.createState("Executing", "running",
        "Resource manager job: [" + jobId + "] placed on a node");
    taskProcessor.setState(state);
    persist(taskProcessor.getWorkflowInstance());
  }

  /**
   * Whether the Resource Manager has this job on a node, as opposed to still
   * holding it in a queue.
   */
  static boolean isOnANode(String status) {
    return JobStatus.SCHEDULED.equals(status)
        || JobStatus.EXECUTED.equals(status);
  }

  /**
   * The job's status, or null if it could not be read. Null means "ask again
   * next pass", never "something has changed".
   */
  protected String safeGetJobStatus(String jobId) {
    try {
      Job job = rClient.getJobInfo(jobId);
      return job != null ? job.getStatus() : null;
    } catch (Exception e) {
      LOG.log(Level.FINE, "Could not read status for job: [" + jobId
          + "]: Message: " + e.getMessage());
      return null;
    }
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
    adoptMetadataWrittenByTheNode(taskProcessor);
    WorkflowLifecycle lifecycle = getLifecycle(taskProcessor);
    WorkflowState state = lifecycle.createState("ExecutionComplete",
        "transition", msg);
    taskProcessor.setState(state);
    persist(taskProcessor.getWorkflowInstance());
  }

  /**
   * Take back what the node wrote while the task was away.
   *
   * <p>The processor holds a copy of the instance read before the job was
   * submitted. While the task runs elsewhere, TaskJob on that node writes to
   * the same instance through the workflow manager: the node it actually ran
   * on, and its task start and end times. Those updates land in the
   * repository, not in this copy -- and persisting this copy afterwards puts
   * the pre-dispatch values back over them.</p>
   *
   * <p>ProcessingNode is where it showed: an instance that ran on a compute
   * node kept reporting the manager's own host, because the correction was
   * written and then overwritten a moment later. The node is the authority on
   * what happened to a task it ran, so its keys win here.</p>
   *
   * <p>Only the shared context is taken. State belongs to this runner, which
   * is about to set it.</p>
   */
  private void adoptMetadataWrittenByTheNode(TaskProcessor taskProcessor) {
    WorkflowInstance mine = taskProcessor.getWorkflowInstance();
    if (instRep == null || mine == null || mine.getId() == null) {
      return;
    }
    try {
      WorkflowInstance stored = instRep.getWorkflowInstanceById(mine.getId());
      if (stored == null || stored.getSharedContext() == null) {
        return;
      }
      Metadata context = mine.getSharedContext();
      if (context == null) {
        context = new Metadata();
        mine.setSharedContext(context);
      }
      context.replaceMetadata(stored.getSharedContext());
    } catch (Exception e) {
      // Worth saying, not worth failing the task over: the work is done and
      // the instance still advances, it just reports the pre-dispatch values.
      LOG.log(Level.WARNING, "Unable to read back the metadata written by the "
          + "node for instance [" + mine.getId() + "]: " + e.getMessage(), e);
    }
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
          } else if (isOnANode(safeGetJobStatus(jobId))) {
            // Only for jobs that are not finished, and only until the state
            // has been recorded once. Completion is what this monitor is
            // really for and its handling is left exactly as it was.
            markExecuting(taskProcessor, jobId);
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
