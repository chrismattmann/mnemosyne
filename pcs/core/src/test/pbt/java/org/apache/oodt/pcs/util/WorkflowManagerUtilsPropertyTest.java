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

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

/**
 * Properties of {@link WorkflowManagerUtils}, the layer PCS puts between itself
 * and the workflow manager.
 *
 * <p>Every method on this class is a "safe" variant: it is expected to answer
 * something usable whether or not the far end is reachable. The properties here
 * pin down both halves of that promise — the answer when the workflow manager
 * responds, and the substitute when it does not — using an in-memory
 * {@link StubWorkflowManagerClient} in place of a socket.
 */
class WorkflowManagerUtilsPropertyTest {

  /** A workflow state name. */
  private static final Generator<String> STATUS =
      sampledFrom(List.of("STARTED", "FINISHED", "PAUSED", "QUEUED", "ERROR"));

  /** A workflow instance id. */
  private static final Generator<String> ID =
      text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");

  private static WorkflowInstance instance(String id, String status) {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId(id);
    inst.setStatus(status);
    return inst;
  }

  private static URL url() {
    try {
      return new URL("http://localhost:9001");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A reachable workflow manager's instances are passed through untouched, and
   * an unreachable one yields an empty list rather than null. Callers iterate
   * this result directly, so "no instances" and "cannot tell" have to look the
   * same and neither may be null.
   */
  @HegelTest
  void instancesArePassedThroughOrSubstitutedWithAnEmptyList(TestCase tc) {
    List<String> ids = tc.draw(lists(ID).minSize(0).maxSize(6), "ids");
    boolean alive = tc.draw(booleans(), "alive");
    boolean failing = tc.draw(booleans(), "failing");

    List<WorkflowInstance> instances = new ArrayList<>();
    for (String id : ids) {
      instances.add(instance(id, "STARTED"));
    }

    StubWorkflowManagerClient client = new StubWorkflowManagerClient(
        instances, new LinkedHashMap<String, Integer>(), alive, failing, url());
    WorkflowManagerUtils utils = new WorkflowManagerUtils(client);

    List<WorkflowInstance> answer = utils.safeGetWorkflowInstances();

    assertNotNull(answer, "a null instance list would break every caller's loop");
    if (alive && !failing) {
      assertEquals(instances.size(), answer.size(), "instances were gained or lost");
    } else {
      assertTrue(answer.isEmpty(),
          "instances were reported from a workflow manager that could not answer");
    }
  }

  /**
   * Connectivity is exactly what the client reports, and a client that throws
   * is treated as not connected. Nothing else in PCS gets to decide this: the
   * health report's UP/DOWN line is this boolean.
   */
  @HegelTest
  void connectivityMirrorsTheClient(TestCase tc) {
    boolean alive = tc.draw(booleans(), "alive");

    WorkflowManagerUtils utils = new WorkflowManagerUtils(new StubWorkflowManagerClient(
        new ArrayList<WorkflowInstance>(), new LinkedHashMap<String, Integer>(),
        alive, false, url()));

    assertEquals(alive, utils.isConnected(), "connectivity disagreed with the client");
  }

  /**
   * The count of instances in a state is the workflow manager's count, and an
   * unreachable workflow manager produces the documented sentinel of -1 rather
   * than a plausible-looking zero. The health monitor distinguishes the two:
   * it substitutes zero itself, but only after seeing -1.
   */
  @HegelTest
  void statusCountsArePassedThroughOrReportedAsUnknown(TestCase tc) {
    List<String> statuses = tc.draw(lists(STATUS).minSize(1).maxSize(5), "statuses");
    List<Integer> counts =
        tc.draw(lists(integers().min(0).max(500)).minSize(1).maxSize(5), "counts");
    boolean failing = tc.draw(booleans(), "failing");

    Map<String, Integer> countsByStatus = new LinkedHashMap<>();
    for (int i = 0; i < statuses.size(); i++) {
      countsByStatus.put(statuses.get(i), counts.get(i % counts.size()));
    }

    WorkflowManagerUtils utils = new WorkflowManagerUtils(new StubWorkflowManagerClient(
        new ArrayList<WorkflowInstance>(), countsByStatus, true, failing, url()));

    for (Map.Entry<String, Integer> entry : countsByStatus.entrySet()) {
      int answer = utils.safeGetNumWorkflowInstancesByStatus(entry.getKey());
      if (failing) {
        assertEquals(-1, answer, "an unreachable workflow manager reported a real-looking count");
      } else {
        assertEquals(entry.getValue().intValue(), answer,
            "the count for [" + entry.getKey() + "] changed in transit");
      }
    }
  }

  /**
   * A status update reaches the workflow manager with the instance id and
   * status exactly as given. This call is how a PGE's progress becomes visible;
   * a transposed or dropped argument silently loses the update, and the method
   * returns void so no caller can notice.
   */
  @HegelTest
  void statusUpdatesAreForwardedVerbatim(TestCase tc) {
    String id = tc.draw(ID, "id");
    String status = tc.draw(STATUS, "status");

    StubWorkflowManagerClient client = new StubWorkflowManagerClient(
        new ArrayList<WorkflowInstance>(), new LinkedHashMap<String, Integer>(),
        true, false, url());
    new WorkflowManagerUtils(client).updateWorkflowInstanceStatus(id, status);

    assertEquals(1, client.getStatusUpdates().size(), "the status update never arrived");
    assertEquals(id, client.getStatusUpdates().get(0)[0], "the instance id changed");
    assertEquals(status, client.getStatusUpdates().get(0)[1], "the status changed");
  }

  /**
   * Swapping in a client adopts that client's URL, so the URL the health report
   * prints is the URL the calls actually go to. Reporting one address while
   * talking to another is the failure this rules out.
   */
  @HegelTest
  void adoptingAClientAdoptsItsUrl(TestCase tc) throws Exception {
    int port = tc.draw(integers().min(1024).max(49151), "port");
    URL clientUrl = new URL("http://localhost:" + port);

    WorkflowManagerUtils utils = new WorkflowManagerUtils(new StubWorkflowManagerClient(
        new ArrayList<WorkflowInstance>(), new LinkedHashMap<String, Integer>(),
        true, false, url()));
    StubWorkflowManagerClient replacement = new StubWorkflowManagerClient(
        new ArrayList<WorkflowInstance>(), new LinkedHashMap<String, Integer>(),
        true, false, clientUrl);
    utils.setClient(replacement);

    assertSame(replacement, utils.getClient(), "the client was not adopted");
    assertEquals(clientUrl, utils.getWmUrl(), "the reported URL is not the client's URL");
  }

  /**
   * A URL string that is not a URL leaves the utils with no URL rather than
   * throwing out of the constructor. PCS builds these from configuration, and a
   * typo there should degrade the health report, not abort start-up.
   */
  @HegelTest
  void aMalformedUrlStringDoesNotAbortConstruction(TestCase tc) {
    String junk = tc.draw(text().minSize(1).maxSize(10).categories("Lu", "Ll"), "junk");

    WorkflowManagerUtils utils = new WorkflowManagerUtils(junk);

    assertFalse(utils.isConnected(), "a utils built from a malformed URL claimed to be connected");
  }
}
