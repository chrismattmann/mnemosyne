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

package org.apache.oodt.cas.pge;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;

/**
 * An in-memory {@link WorkflowManagerClient} for the CAS-PGE property tests.
 *
 * <p>{@link PGETaskInstance} reports its progress to the workflow manager and
 * treats a refusal as a failed task. Both halves of that need a workflow
 * manager whose answer the test chooses, and this is it: it records what it was
 * told and returns {@link #accepting}.
 */
public class StubWorkflowManagerClient implements WorkflowManagerClient {

  /** Whether the workflow manager accepts what it is told. */
  private final boolean accepting;

  /** The {@code (id, status)} pairs passed to status updates, in order. */
  private final List<String[]> statusUpdates = new ArrayList<>();

  /** The metadata passed to the last workflow metadata update. */
  private Metadata lastMetadata;

  public StubWorkflowManagerClient(boolean accepting) {
    this.accepting = accepting;
  }

  public List<String[]> getStatusUpdates() {
    return statusUpdates;
  }

  public Metadata getLastMetadata() {
    return lastMetadata;
  }

  @Override
  public boolean updateWorkflowInstanceStatus(String workflowInstId, String status) {
    statusUpdates.add(new String[] {workflowInstId, status});
    return accepting;
  }

  @Override
  public boolean updateMetadataForWorkflow(String workflowInstId, Metadata metadata) {
    lastMetadata = metadata;
    return accepting;
  }

  @Override
  public void close() {}

  // ---------------------------------------------------------------------
  // Everything below is outside what PGETaskInstance asks of a client.
  // ---------------------------------------------------------------------

  @Override
  public boolean refreshRepository() {
    return accepting;
  }

  @Override
  public String executeDynamicWorkflow(List<String> taskIds, Metadata metadata) {
    return null;
  }

  @Override
  public List getRegisteredEvents() {
    return new Vector();
  }

  @Override
  public WorkflowInstancePage getFirstPage() {
    return null;
  }

  @Override
  public WorkflowInstancePage getNextPage(WorkflowInstancePage currentPage) {
    return null;
  }

  @Override
  public WorkflowInstancePage getPrevPage(WorkflowInstancePage currentPage) {
    return null;
  }

  @Override
  public WorkflowInstancePage getLastPage() {
    return null;
  }

  @Override
  public WorkflowInstancePage paginateWorkflowInstances(int pageNum, String status) {
    return null;
  }

  @Override
  public WorkflowInstancePage paginateWorkflowInstances(int pageNum) {
    return null;
  }

  @Override
  public List getWorkflowsByEvent(String eventName) {
    return new Vector();
  }

  @Override
  public Metadata getWorkflowInstanceMetadata(String wInstId) {
    return new Metadata();
  }

  @Override
  public boolean setWorkflowInstanceCurrentTaskStartDateTime(
      String wInstId, String startDateTimeIsoStr) {
    return accepting;
  }

  @Override
  public double getWorkflowCurrentTaskWallClockMinutes(String workflowInstId) {
    return 0.0;
  }

  @Override
  public double getWorkflowWallClockMinutes(String workflowInstId) {
    return 0.0;
  }

  @Override
  public boolean stopWorkflowInstance(String workflowInstId) {
    return accepting;
  }

  @Override
  public boolean pauseWorkflowInstance(String workflowInstId) {
    return accepting;
  }

  @Override
  public boolean resumeWorkflowInstance(String workflowInstId) {
    return accepting;
  }

  @Override
  public boolean setWorkflowInstanceCurrentTaskEndDateTime(
      String wInstId, String endDateTimeIsoStr) {
    return accepting;
  }

  @Override
  public boolean updateWorkflowInstance(WorkflowInstance instance) {
    return accepting;
  }

  @Override
  public boolean sendEvent(String eventName, Metadata metadata) {
    return accepting;
  }

  @Override
  public WorkflowTask getTaskById(String taskId) {
    return null;
  }

  @Override
  public WorkflowCondition getConditionById(String conditionId) {
    return null;
  }

  @Override
  public WorkflowInstance getWorkflowInstanceById(String wInstId) {
    return null;
  }

  @Override
  public Workflow getWorkflowById(String workflowId) {
    return null;
  }

  @Override
  public Vector getWorkflows() {
    return new Vector();
  }

  @Override
  public int getNumWorkflowInstancesByStatus(String status) {
    return 0;
  }

  @Override
  public int getNumWorkflowInstances() {
    return 0;
  }

  @Override
  public Vector getWorkflowInstancesByStatus(String status) {
    return new Vector();
  }

  @Override
  public Vector getWorkflowInstances() {
    return new Vector();
  }

  @Override
  public URL getWorkflowManagerUrl() {
    return null;
  }

  @Override
  public boolean isAlive() {
    return accepting;
  }

  @Override
  public void setWorkflowManagerUrl(URL workflowManagerUrl) {}
}
