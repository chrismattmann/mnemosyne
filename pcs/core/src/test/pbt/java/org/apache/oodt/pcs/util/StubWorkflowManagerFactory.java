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
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.system.WorkflowManager;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;
import org.apache.oodt.cas.workflow.system.rpc.WorkflowManagerFactory;

/**
 * A {@link WorkflowManagerFactory} that hands out an in-memory client.
 *
 * <p>{@link org.apache.oodt.pcs.tools.PCSHealthMonitor} builds its own workflow
 * manager client from a URL and offers no way to replace it, so the only way in
 * is the same one production uses: the {@code workflow.client.factory} system
 * property. A test sets that property to this class, puts the counts it wants
 * into {@link #countsByStatus}, and gets a health monitor talking to a workflow
 * manager it controls.
 *
 * <p>The state is static because the factory is constructed reflectively. Tests
 * must {@link #reset()} it and restore the system property afterwards.
 */
public class StubWorkflowManagerFactory implements WorkflowManagerFactory {

  /** Instance counts the stub client will report, keyed by workflow state. */
  public static Map<String, Integer> countsByStatus = new LinkedHashMap<>();

  /** Whether the stub client should fail every request. */
  public static boolean failing = false;

  /** Whether the stub client should fail only the per-status count call. */
  public static boolean failCounts = false;

  private URL url;

  /** Puts the factory back to a state no test is relying on. */
  public static void reset() {
    countsByStatus = new LinkedHashMap<>();
    failing = false;
    failCounts = false;
  }

  @Override
  public void setPort(int port) {}

  @Override
  public void setUrl(URL url) {
    this.url = url;
  }

  @Override
  public WorkflowManager createServer() {
    throw new UnsupportedOperationException("the stub factory serves clients only");
  }

  @Override
  public WorkflowManagerClient createClient() {
    return new StubWorkflowManagerClient(
        new ArrayList<WorkflowInstance>(), countsByStatus, true, failing, failCounts, url);
  }
}
