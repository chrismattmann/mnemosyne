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
import java.util.List;
import java.util.Map;
import java.util.Vector;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;

/**
 * An in-memory {@link WorkflowManagerClient} for the PCS property tests.
 *
 * <p>{@link WorkflowManagerUtils} exists to put a forgiving face on a remote
 * workflow manager: it swallows failures and substitutes safe defaults. That
 * translation is the thing worth testing, and testing it needs a workflow
 * manager whose answers — and failures — are chosen by the test. This class is
 * that workflow manager, holding its instances in a list rather than over a
 * socket.
 */
public class StubWorkflowManagerClient implements WorkflowManagerClient {

  private final List<WorkflowInstance> instances;
  private final Map<String, Integer> countsByStatus;
  private final boolean alive;
  private final boolean failing;
  private final boolean failCounts;
  private URL url;

  /** Records the {@code (id, status)} pairs passed to status updates. */
  private final List<String[]> statusUpdates = new Vector<>();

  public StubWorkflowManagerClient(List<WorkflowInstance> instances,
      Map<String, Integer> countsByStatus, boolean alive, boolean failing, URL url) {
    this(instances, countsByStatus, alive, failing, false, url);
  }

  /**
   * @param failCounts fail only the per-status count call, leaving the client
   *     otherwise healthy. This is the shape of a workflow manager that is up
   *     but cannot answer one question, which is exactly when the -1 sentinel
   *     in {@link WorkflowManagerUtils} matters.
   */
  public StubWorkflowManagerClient(List<WorkflowInstance> instances,
      Map<String, Integer> countsByStatus, boolean alive, boolean failing, boolean failCounts,
      URL url) {
    this.instances = instances;
    this.countsByStatus = countsByStatus;
    this.alive = alive;
    this.failing = failing;
    this.failCounts = failCounts;
    this.url = url;
  }

  public List<String[]> getStatusUpdates() {
    return statusUpdates;
  }

  private void failIfAsked() throws Exception {
    if (failing) {
      throw new Exception("workflow manager is unreachable");
    }
  }

  @Override
  public Vector getWorkflowInstances() throws Exception {
    failIfAsked();
    return new Vector<>(instances);
  }

  @Override
  public Vector getWorkflowInstancesByStatus(String status) throws Exception {
    failIfAsked();
    Vector<WorkflowInstance> matching = new Vector<>();
    for (WorkflowInstance instance : instances) {
      if (status.equals(instance.getStatus())) {
        matching.add(instance);
      }
    }
    return matching;
  }

  @Override
  public int getNumWorkflowInstancesByStatus(String status) throws Exception {
    failIfAsked();
    if (failCounts) {
      throw new Exception("workflow manager cannot count instances right now");
    }
    Integer count = countsByStatus.get(status);
    return count == null ? 0 : count;
  }

  @Override
  public boolean updateWorkflowInstanceStatus(String workflowInstId, String status)
      throws Exception {
    failIfAsked();
    statusUpdates.add(new String[] {workflowInstId, status});
    return true;
  }

  @Override
  public boolean isAlive() {
    return alive;
  }

  @Override
  public URL getWorkflowManagerUrl() {
    return url;
  }

  @Override
  public void setWorkflowManagerUrl(URL workflowManagerUrl) {
    this.url = workflowManagerUrl;
  }

  @Override
  public void close() {}

  // ---------------------------------------------------------------------
  // Everything below is outside what WorkflowManagerUtils asks of a client.
  // ---------------------------------------------------------------------

  @Override
  public boolean refreshRepository() throws Exception {
    failIfAsked();
    return true;
  }

  @Override
  public String executeDynamicWorkflow(List<String> taskIds, Metadata metadata) throws Exception {
    failIfAsked();
    return null;
  }

  @Override
  public List getRegisteredEvents() throws Exception {
    failIfAsked();
    return new Vector();
  }

  @Override
  public WorkflowInstancePage getFirstPage() throws Exception {
    failIfAsked();
    return null;
  }

  @Override
  public WorkflowInstancePage getNextPage(WorkflowInstancePage currentPage) throws Exception {
    failIfAsked();
    return null;
  }

  @Override
  public WorkflowInstancePage getPrevPage(WorkflowInstancePage currentPage) throws Exception {
    failIfAsked();
    return null;
  }

  @Override
  public WorkflowInstancePage getLastPage() throws Exception {
    failIfAsked();
    return null;
  }

  @Override
  public WorkflowInstancePage paginateWorkflowInstances(int pageNum, String status)
      throws Exception {
    failIfAsked();
    return null;
  }

  @Override
  public WorkflowInstancePage paginateWorkflowInstances(int pageNum) throws Exception {
    failIfAsked();
    return null;
  }

  @Override
  public List getWorkflowsByEvent(String eventName) throws Exception {
    failIfAsked();
    return new Vector();
  }

  @Override
  public Metadata getWorkflowInstanceMetadata(String wInstId) throws Exception {
    failIfAsked();
    return new Metadata();
  }

  @Override
  public boolean setWorkflowInstanceCurrentTaskStartDateTime(
      String wInstId, String startDateTimeIsoStr) throws Exception {
    failIfAsked();
    return true;
  }

  @Override
  public double getWorkflowCurrentTaskWallClockMinutes(String workflowInstId) throws Exception {
    failIfAsked();
    return 0.0;
  }

  @Override
  public double getWorkflowWallClockMinutes(String workflowInstId) throws Exception {
    failIfAsked();
    return 0.0;
  }

  @Override
  public boolean stopWorkflowInstance(String workflowInstId) throws Exception {
    failIfAsked();
    return true;
  }

  @Override
  public boolean pauseWorkflowInstance(String workflowInstId) throws Exception {
    failIfAsked();
    return true;
  }

  @Override
  public boolean resumeWorkflowInstance(String workflowInstId) throws Exception {
    failIfAsked();
    return true;
  }

  @Override
  public boolean setWorkflowInstanceCurrentTaskEndDateTime(
      String wInstId, String endDateTimeIsoStr) throws Exception {
    failIfAsked();
    return true;
  }

  @Override
  public boolean updateWorkflowInstance(WorkflowInstance instance) throws Exception {
    failIfAsked();
    return true;
  }

  @Override
  public boolean updateMetadataForWorkflow(String workflowInstId, Metadata metadata)
      throws Exception {
    failIfAsked();
    return true;
  }

  @Override
  public boolean sendEvent(String eventName, Metadata metadata) throws Exception {
    failIfAsked();
    return true;
  }

  @Override
  public WorkflowTask getTaskById(String taskId) throws Exception {
    failIfAsked();
    return null;
  }

  @Override
  public WorkflowCondition getConditionById(String conditionId) throws Exception {
    failIfAsked();
    return null;
  }

  @Override
  public WorkflowInstance getWorkflowInstanceById(String wInstId) throws Exception {
    failIfAsked();
    for (WorkflowInstance instance : instances) {
      if (wInstId.equals(instance.getId())) {
        return instance;
      }
    }
    return null;
  }

  @Override
  public Workflow getWorkflowById(String workflowId) throws Exception {
    failIfAsked();
    return null;
  }

  @Override
  public Vector getWorkflows() throws Exception {
    failIfAsked();
    return new Vector();
  }

  @Override
  public int getNumWorkflowInstances() throws Exception {
    failIfAsked();
    return instances.size();
  }
}
