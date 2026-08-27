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
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.apache.oodt.cas.resource.structs.ResourceNode;

/**
 * Properties of {@link ResourceManagerUtils}, the layer PCS puts between itself
 * and the resource manager.
 *
 * <p>The health monitor asks this class for the batch stubs it should probe. If
 * the list it gets back is shorter than the truth, a dead node goes unreported;
 * if the class cannot tell the difference between "no nodes" and "could not
 * ask", the report is wrong in a way the operator cannot see.
 */
class ResourceManagerUtilsPropertyTest {

  /** A resource node id. */
  private static final Generator<String> NODE_ID =
      text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");

  private static URL url() {
    try {
      return new URL("http://localhost:9002");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A reachable resource manager's nodes are passed through untouched, and an
   * unreachable one yields null — the sentinel the health monitor checks for
   * before iterating. Substituting an empty list would make an unreachable
   * resource manager look like one with no nodes.
   */
  @HegelTest
  void nodesArePassedThroughOrReportedAsUnknown(TestCase tc) throws Exception {
    List<String> ids = tc.draw(lists(NODE_ID).minSize(0).maxSize(6), "ids");
    List<Integer> capacities =
        tc.draw(lists(integers().min(0).max(64)).minSize(1).maxSize(6), "capacities");
    boolean failing = tc.draw(booleans(), "failing");

    List<ResourceNode> nodes = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      nodes.add(new ResourceNode(
          ids.get(i), new URL("http://localhost:" + (9100 + i)),
          capacities.get(i % capacities.size())));
    }

    ResourceManagerUtils utils =
        new ResourceManagerUtils(new StubResourceManagerClient(nodes, failing, url()));

    List answer = utils.safeGetResourceNodes();

    if (failing) {
      assertNull(answer, "an unreachable resource manager reported a node list");
    } else {
      assertNotNull(answer, "a reachable resource manager reported no node list at all");
      assertEquals(nodes.size(), answer.size(), "nodes were gained or lost");
      for (int i = 0; i < nodes.size(); i++) {
        assertEquals(nodes.get(i).getNodeId(), ((ResourceNode) answer.get(i)).getNodeId(),
            "node " + i + " changed identity");
        assertEquals(nodes.get(i).getCapacity(), ((ResourceNode) answer.get(i)).getCapacity(),
            "node " + i + " changed capacity");
      }
    }
  }

  /**
   * Swapping in a client adopts that client's URL, so the address the health
   * report prints for the resource manager is the address being talked to.
   */
  @HegelTest
  void adoptingAClientAdoptsItsUrl(TestCase tc) throws Exception {
    int port = tc.draw(integers().min(1024).max(49151), "port");
    URL clientUrl = new URL("http://localhost:" + port);

    ResourceManagerUtils utils = new ResourceManagerUtils(
        new StubResourceManagerClient(new ArrayList<ResourceNode>(), false, url()));
    StubResourceManagerClient replacement =
        new StubResourceManagerClient(new ArrayList<ResourceNode>(), false, clientUrl);
    utils.setClient(replacement);

    assertSame(replacement, utils.getClient(), "the client was not adopted");
    assertEquals(clientUrl, utils.getResmgrUrl(), "the reported URL is not the client's URL");
  }

  /**
   * A URL string that is not a URL leaves the utils usable rather than throwing
   * out of the constructor. PCS builds these from configuration, and a typo
   * there should degrade the health report, not abort start-up.
   */
  @HegelTest
  void aMalformedUrlStringDoesNotAbortConstruction(TestCase tc) {
    String junk = tc.draw(text().minSize(1).maxSize(10).categories("Lu", "Ll"), "junk");

    ResourceManagerUtils utils = new ResourceManagerUtils(junk);

    assertNull(utils.getResmgrUrl(), "a malformed URL string produced a URL");
  }
}
