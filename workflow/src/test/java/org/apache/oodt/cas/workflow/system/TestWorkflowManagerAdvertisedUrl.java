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
package org.apache.oodt.cas.workflow.system;

import junit.framework.TestCase;

/**
 * The address the manager hands to a compute node.
 *
 * <p>It was built from InetAddress.getLocalHost().getHostName(), the short
 * local name -- "ninja" -- which resolves on the machine that produced it and
 * nowhere else. A node was told to call back on http://ninja:9201, could not
 * resolve it, and every status and metadata update it pushed was lost. The
 * visible half was ProcessingNode: an instance kept naming the manager's own
 * host as the machine that ran the task, because the node's correction never
 * arrived.</p>
 */
public class TestWorkflowManagerAdvertisedUrl extends TestCase {

  private String previous;

  @Override
  protected void setUp() {
    previous = System.getProperty(
        AvroRpcWorkflowManager.WORKFLOW_MANAGER_URL_PROPERTY);
  }

  @Override
  protected void tearDown() {
    if (previous == null) {
      System.clearProperty(
          AvroRpcWorkflowManager.WORKFLOW_MANAGER_URL_PROPERTY);
    } else {
      System.setProperty(
          AvroRpcWorkflowManager.WORKFLOW_MANAGER_URL_PROPERTY, previous);
    }
  }

  public void testThePropertyNameIsTheOneDeploymentsSet() {
    assertEquals("org.apache.oodt.cas.workflow.manager.url",
        AvroRpcWorkflowManager.WORKFLOW_MANAGER_URL_PROPERTY);
  }

  public void testAConfiguredAddressIsUsedVerbatim() {
    System.setProperty(AvroRpcWorkflowManager.WORKFLOW_MANAGER_URL_PROPERTY,
        "http://10.168.168.6:9201");
    assertEquals("a deployment that sets this means it",
        "http://10.168.168.6:9201",
        System.getProperty(
            AvroRpcWorkflowManager.WORKFLOW_MANAGER_URL_PROPERTY));
  }

  public void testAnEmptyPropertyIsNotAnAddress() {
    // Blank has to fall back to the hostname rather than produce
    // "http://:9201", which parses and then fails at call time.
    System.setProperty(AvroRpcWorkflowManager.WORKFLOW_MANAGER_URL_PROPERTY,
        "   ");
    String configured = System.getProperty(
        AvroRpcWorkflowManager.WORKFLOW_MANAGER_URL_PROPERTY);
    assertTrue("blank must be treated as unset",
        configured == null || configured.trim().isEmpty());
  }
}
