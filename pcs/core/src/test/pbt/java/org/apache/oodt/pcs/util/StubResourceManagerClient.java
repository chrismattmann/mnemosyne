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

package org.apache.oodt.pcs.util;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.JobInput;
import org.apache.oodt.cas.resource.structs.ResourceNode;
import org.apache.oodt.cas.resource.structs.exceptions.JobRepositoryException;
import org.apache.oodt.cas.resource.structs.exceptions.MonitorException;
import org.apache.oodt.cas.resource.structs.exceptions.QueueManagerException;
import org.apache.oodt.cas.resource.system.ResourceManagerClient;

/**
 * An in-memory {@link ResourceManagerClient} for the PCS property tests.
 *
 * <p>{@link ResourceManagerUtils} is a thin, forgiving wrapper over a remote
 * resource manager. Testing what it does with a healthy answer and what it does
 * with a failure needs a resource manager that can be told to give either; this
 * is it, holding its nodes in a list rather than over a socket.
 */
public class StubResourceManagerClient implements ResourceManagerClient {

  private final List<ResourceNode> nodes;
  private final boolean failing;
  private URL url;

  /**
   * The URL constructor {@code ResourceManagerFactory} looks for. Registering
   * this class under {@code resmgr.manager.client} lets a test stand a resource
   * manager up without a socket; it reports itself healthy with no nodes, which
   * is what the health monitor properties want.
   */
  public StubResourceManagerClient(URL url) {
    this(new ArrayList<ResourceNode>(), false, url);
  }

  public StubResourceManagerClient(List<ResourceNode> nodes, boolean failing, URL url) {
    this.nodes = nodes;
    this.failing = failing;
    this.url = url;
  }

  @Override
  public List getNodes() throws MonitorException {
    if (failing) {
      throw new MonitorException("resource manager is unreachable");
    }
    return new ArrayList<>(nodes);
  }

  @Override
  public URL getResMgrUrl() {
    return url;
  }

  @Override
  public void setResMgrUrl(URL resMgrUrl) {
    this.url = resMgrUrl;
  }

  @Override
  public boolean isAlive() {
    return !failing;
  }

  // ---------------------------------------------------------------------
  // Everything below is outside what ResourceManagerUtils asks of a client.
  // ---------------------------------------------------------------------

  @Override
  public boolean isJobComplete(String jobId) {
    return false;
  }

  @Override
  public Job getJobInfo(String jobId) {
    return null;
  }

  @Override
  public int getJobQueueSize() {
    return 0;
  }

  @Override
  public int getJobQueueCapacity() {
    return 0;
  }

  @Override
  public boolean killJob(String jobId) {
    return false;
  }

  @Override
  public String getExecutionNode(String jobId) {
    return null;
  }

  @Override
  public String getNodeReport() {
    return "";
  }

  @Override
  public String getExecReport() throws JobRepositoryException {
    return "";
  }

  @Override
  public String submitJob(Job exec, JobInput in) {
    return null;
  }

  @Override
  public boolean submitJob(Job exec, JobInput in, URL hostUrl) {
    return false;
  }

  @Override
  public ResourceNode getNodeById(String nodeId) throws MonitorException {
    for (ResourceNode node : nodes) {
      if (node.getNodeId().equals(nodeId)) {
        return node;
      }
    }
    return null;
  }

  @Override
  public void addQueue(String queueName) throws QueueManagerException {}

  @Override
  public void removeQueue(String queueName) throws QueueManagerException {}

  @Override
  public void addNode(ResourceNode node) throws MonitorException {}

  @Override
  public void removeNode(String nodeId) throws MonitorException {}

  @Override
  public void setNodeCapacity(String nodeId, int capacity) throws MonitorException {}

  @Override
  public void addNodeToQueue(String nodeId, String queueName) throws QueueManagerException {}

  @Override
  public void removeNodeFromQueue(String nodeId, String queueName) throws QueueManagerException {}

  @Override
  public List<String> getQueues() throws QueueManagerException {
    return new ArrayList<>();
  }

  @Override
  public List<String> getNodesInQueue(String queueName) throws QueueManagerException {
    return new ArrayList<>();
  }

  @Override
  public List<String> getQueuesWithNode(String nodeId) throws QueueManagerException {
    return new ArrayList<>();
  }

  @Override
  public String getNodeLoad(String nodeId) throws MonitorException {
    return "0";
  }

  @Override
  public List getQueuedJobs() {
    return new ArrayList();
  }
}
