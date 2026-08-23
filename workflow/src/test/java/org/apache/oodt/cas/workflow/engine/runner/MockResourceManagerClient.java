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
import org.apache.oodt.cas.resource.structs.JobInput;
import org.apache.oodt.cas.resource.structs.ResourceNode;
import org.apache.oodt.cas.resource.structs.exceptions.JobExecutionException;
import org.apache.oodt.cas.resource.structs.exceptions.JobQueueException;
import org.apache.oodt.cas.resource.structs.exceptions.JobRepositoryException;
import org.apache.oodt.cas.resource.structs.exceptions.MonitorException;
import org.apache.oodt.cas.resource.structs.exceptions.QueueManagerException;
import org.apache.oodt.cas.resource.system.ResourceManagerClient;

//JDK imports
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in Resource Manager, so {@link ResourceRunner} can be tested without
 * a live one. Only the handful of operations the runner actually uses carry
 * behaviour; the rest satisfy the interface.
 *
 * @author mattmann
 */
public class MockResourceManagerClient implements ResourceManagerClient {

  private final List<Job> submittedJobs = Collections
      .synchronizedList(new ArrayList<Job>());

  private final Map<String, Boolean> jobCompletion =
      new ConcurrentHashMap<String, Boolean>();

  private int queueCapacity = 10;

  private int queueSize;

  private String nextJobId = "mock-job";

  private URL resMgrUrl;

  // ---- knobs the tests drive -------------------------------------------

  public void setQueueCapacity(int queueCapacity) {
    this.queueCapacity = queueCapacity;
  }

  public void setQueueSize(int queueSize) {
    this.queueSize = queueSize;
  }

  /** A null id models a resource manager refusing the submission. */
  public void setNextJobId(String nextJobId) {
    this.nextJobId = nextJobId;
  }

  public void setJobComplete(String jobId, boolean complete) {
    this.jobCompletion.put(jobId, complete);
  }

  public List<Job> getSubmittedJobs() {
    return this.submittedJobs;
  }

  // ---- the operations ResourceRunner exercises -------------------------

  @Override
  public String submitJob(Job exec, JobInput in) throws JobExecutionException {
    this.submittedJobs.add(exec);
    if (this.nextJobId != null) {
      this.jobCompletion.put(this.nextJobId, false);
    }
    return this.nextJobId;
  }

  @Override
  public boolean isJobComplete(String jobId) throws JobRepositoryException {
    Boolean complete = this.jobCompletion.get(jobId);
    return complete != null && complete;
  }

  @Override
  public int getJobQueueSize() throws JobRepositoryException {
    return this.queueSize;
  }

  @Override
  public int getJobQueueCapacity() throws JobRepositoryException {
    return this.queueCapacity;
  }

  @Override
  public boolean killJob(String jobId) {
    return this.jobCompletion.remove(jobId) != null;
  }

  // ---- remainder of the interface --------------------------------------

  @Override
  public Job getJobInfo(String jobId) throws JobRepositoryException {
    return null;
  }

  @Override
  public boolean isAlive() {
    return true;
  }

  @Override
  public String getExecutionNode(String jobId) {
    return null;
  }

  @Override
  public String getNodeReport() throws MonitorException {
    return null;
  }

  @Override
  public String getExecReport() throws JobRepositoryException {
    return null;
  }

  @Override
  public boolean submitJob(Job exec, JobInput in, URL hostUrl)
      throws JobExecutionException {
    this.submittedJobs.add(exec);
    return true;
  }

  @Override
  public List getNodes() throws MonitorException {
    return Collections.emptyList();
  }

  @Override
  public ResourceNode getNodeById(String nodeId) throws MonitorException {
    return null;
  }

  @Override
  public URL getResMgrUrl() {
    return this.resMgrUrl;
  }

  @Override
  public void setResMgrUrl(URL resMgrUrl) {
    this.resMgrUrl = resMgrUrl;
  }

  @Override
  public void addQueue(String queueName) throws QueueManagerException {
  }

  @Override
  public void removeQueue(String queueName) throws QueueManagerException {
  }

  @Override
  public void addNode(ResourceNode node) throws MonitorException {
  }

  @Override
  public void removeNode(String nodeId) throws MonitorException {
  }

  @Override
  public void setNodeCapacity(String nodeId, int capacity)
      throws MonitorException {
  }

  @Override
  public void addNodeToQueue(String nodeId, String queueName)
      throws QueueManagerException {
  }

  @Override
  public void removeNodeFromQueue(String nodeId, String queueName)
      throws QueueManagerException {
  }

  @Override
  public List<String> getQueues() throws QueueManagerException {
    return Collections.emptyList();
  }

  @Override
  public List<String> getNodesInQueue(String queueName)
      throws QueueManagerException {
    return Collections.emptyList();
  }

  @Override
  public List<String> getQueuesWithNode(String nodeId)
      throws QueueManagerException {
    return Collections.emptyList();
  }

  @Override
  public String getNodeLoad(String nodeId) throws MonitorException {
    return null;
  }

  @Override
  public List getQueuedJobs() throws JobQueueException {
    return Collections.emptyList();
  }
}
