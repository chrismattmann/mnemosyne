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

package org.apache.oodt.pcs.util;

import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;
import org.apache.oodt.cas.workflow.system.rpc.RpcCommunicationFactory;

import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 
 * A set of utility methods that can be used by PCS that need to communicate
 * with the Workflow Manager.
 * 
 * @author mattmann
 * @version $Revision$
 */
public class WorkflowManagerUtils implements Serializable, AutoCloseable {

  /* our workflow manager client */
  private WorkflowManagerClient client;

  /* our log stream */
  private static final Logger LOG = Logger.getLogger(WorkflowManagerUtils.class.getName());

  private URL wmUrl;

  public WorkflowManagerUtils(String urlStr) {
    this(safeGetUrlFromString(urlStr));
  }

  public WorkflowManagerUtils(URL url) {
    this.client = RpcCommunicationFactory.createClient(url);
    this.wmUrl = url;
  }

  public WorkflowManagerUtils(WorkflowManagerClient client) {
    this.client = client;
  }

  public void updateWorkflowInstanceStatus(String wInstId, String status) {
    try {
      this.client.updateWorkflowInstanceStatus(wInstId, status);
    } catch (Exception e) {
      LOG.log(Level.SEVERE, e.getMessage());
    }

  }

  public List<WorkflowInstance> safeGetWorkflowInstances() {
    if (!isConnected()) {
      return Collections.EMPTY_LIST;
    }

    try {
      return this.client.getWorkflowInstances();
    } catch (Exception ignore) {
      return Collections.EMPTY_LIST;
    }
  }

  public boolean isConnected() {
    try {
      // Was a raw XmlRpcClient calling "workflowmgr.getWorkflowInstances",
      // which probed a transport the workflow manager no longer speaks: against
      // an Avro workflow manager this reported not-connected however healthy
      // the server was. isAlive goes over whichever transport the client is
      // configured for, and asks the far cheaper question.
      return this.client.isAlive();
    } catch (Exception ignore) {
      return false;
    }
  }

  /**
   * The statuses the manager's engine can report, or an empty list.
   *
   * <p>
   * Callers that enumerate statuses have had to be told them in advance, in
   * a file written per deployment. The manager knows: its engine reads a
   * lifecycle. A manager too old to be asked answers with nothing, and the
   * caller keeps whatever it was configured with.
   * </p>
   */
  public List safeGetSupportedStatuses() {
    if (!isConnected()) {
      return new java.util.ArrayList();
    }
    try {
      List statuses = this.client.getSupportedWorkflowStatuses();
      return statuses == null ? new java.util.ArrayList() : statuses;
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Unable to get the supported workflow statuses"
          + " from the Workflow Manager: Message: " + e.getMessage());
      return new java.util.ArrayList();
    }
  }

  public List safeGetWorkflowInstancesByStatus(String status) {
    try {
      return this.client.getWorkflowInstancesByStatus(status);
    } catch (Exception e) {
      LOG.log(Level.WARNING,
          "exception obtaining workflow instances by status: [" + status
              + "]: message: " + e.getMessage());
      return null;
    }
  }

  public int safeGetNumWorkflowInstancesByStatus(String status) {
    try {
      return this.client.getNumWorkflowInstancesByStatus(status);
    } catch (Exception e) {
      LOG.log(Level.WARNING,
          "exception obtaining num workflow instances by status: [" + status
              + "]: message: " + e.getMessage());
      return -1;
    }
  }

  /**
   * @return the client
   */
  public WorkflowManagerClient getClient() {
    return client;
  }

  /**
   * @param client
   *          the client to set
   */
  public void setClient(WorkflowManagerClient client) {
    this.client = client;
    if (this.client != null) {
      this.wmUrl = this.client.getWorkflowManagerUrl();
    }
  }

  private static URL safeGetUrlFromString(String urlStr) {
    URL url = null;

    try {
      url = new URL(urlStr);
    } catch (MalformedURLException e) {
      LOG.log(Level.SEVERE, "PCS: Unable to generate url from url string: ["
          + urlStr + "]: Message: " + e.getMessage());
    }

    return url;
  }

  /**
   * 
   * @return The {@link URL} pointer to the Workflow Manager that this
   *         WorkflowManagerUtils communicates with.
   */
  public URL getWmUrl() {
    return this.wmUrl;
  }

  @Override
  public void close() throws IOException {
    if (this.client != null) {
      this.client.close();
      this.client = null;
    }
  }

}
