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

package org.apache.oodt.cas.resource.monitor;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.resource.structs.ResourceNode;

/**
 * Load-accounting properties of {@link AssignmentMonitor}.
 *
 * <p>The existing unit test checks a fixed nodes.xml against fixed
 * expectations. These properties instead state the arithmetic the scheduler
 * depends on over an arbitrary run of assignments and reductions: the
 * scheduler asks this class how much room a node has, and if that number is
 * ever wrong the cluster is either oversubscribed or left idle.
 *
 * <p>Note on naming: {@code getLoad} on this implementation returns the
 * <em>remaining</em> capacity of a node, not the load on it, and
 * {@code LRUScheduler.nodeAvailable} reads it that way ({@code load <=
 * nodeLoad} means "the job fits"). The properties below are stated against
 * that meaning, because that is the one a caller in this codebase relies on.
 */
class AssignmentMonitorPropertyTest {

  private static Generator<String> nodeId() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  private static ResourceNode node(String id, int capacity) {
    try {
      return new ResourceNode(id, new URL("http://" + id.toLowerCase() + ".example:9000"), capacity);
    } catch (MalformedURLException e) {
      throw new AssertionError(e);
    }
  }

  /** A cluster of distinctly-named nodes, which is what nodes.xml describes. */
  private static List<ResourceNode> drawCluster(TestCase tc) {
    int count = tc.draw(integers().min(1).max(5), "nodeCount");
    List<ResourceNode> nodes = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < count; i++) {
      String id = tc.draw(nodeId(), "node[" + i + "].id");
      if (!seen.add(id)) {
        continue;
      }
      nodes.add(node(id, tc.draw(integers().min(0).max(20), "node[" + i + "].capacity")));
    }
    return nodes;
  }

  /** A freshly loaded cluster has every node completely free. */
  @HegelTest
  void aFreshClusterIsEntirelyFree(TestCase tc) throws Exception {
    List<ResourceNode> nodes = drawCluster(tc);
    AssignmentMonitor monitor = new AssignmentMonitor(nodes);

    for (ResourceNode n : nodes) {
      assertEquals(n.getCapacity(), monitor.getLoad(n), "node " + n.getNodeId() + " not free");
    }
  }

  /**
   * Remaining capacity never leaves the range a node can actually be in, no
   * matter how assignments and reductions interleave. Outside that range the
   * scheduler would either refuse to place a job on an idle node or place one
   * on a node with no room.
   */
  @HegelTest
  void remainingCapacityStaysWithinTheNode(TestCase tc) throws Exception {
    List<ResourceNode> nodes = drawCluster(tc);
    AssignmentMonitor monitor = new AssignmentMonitor(nodes);
    int steps = tc.draw(integers().min(0).max(20), "steps");

    for (int i = 0; i < steps; i++) {
      ResourceNode target = tc.draw(sampledFrom(nodes), "step[" + i + "].node");
      int amount = tc.draw(integers().min(0).max(25), "step[" + i + "].amount");
      if (tc.draw(booleans(), "step[" + i + "].assign")) {
        monitor.assignLoad(target, amount);
      } else {
        monitor.reduceLoad(target, amount);
      }

      for (ResourceNode n : nodes) {
        int free = monitor.getLoad(n);
        assertTrue(free >= 0, "node " + n.getNodeId() + " oversubscribed: free=" + free);
        assertTrue(
            free <= n.getCapacity(),
            "node " + n.getNodeId() + " has more room than capacity: free=" + free);
      }
    }
  }

  /**
   * An assignment that succeeds takes exactly the load it was given, and one
   * that fails takes nothing. The scheduler decrements a node's room by the
   * job's load value and expects the monitor to agree.
   */
  @HegelTest
  void assigningTakesExactlyTheLoadOrNothing(TestCase tc) throws Exception {
    List<ResourceNode> nodes = drawCluster(tc);
    AssignmentMonitor monitor = new AssignmentMonitor(nodes);
    ResourceNode target = tc.draw(sampledFrom(nodes), "node");
    int amount = tc.draw(integers().min(0).max(25), "amount");

    int before = monitor.getLoad(target);
    boolean assigned = monitor.assignLoad(target, amount);
    int after = monitor.getLoad(target);

    if (assigned) {
      assertEquals(before - amount, after, "assignment did not take the load it was given");
    } else {
      assertEquals(before, after, "a refused assignment still changed the node");
    }
  }

  /** An assignment is refused exactly when the job does not fit. */
  @HegelTest
  void anAssignmentIsRefusedExactlyWhenItDoesNotFit(TestCase tc) throws Exception {
    List<ResourceNode> nodes = drawCluster(tc);
    AssignmentMonitor monitor = new AssignmentMonitor(nodes);
    ResourceNode target = tc.draw(sampledFrom(nodes), "node");
    int amount = tc.draw(integers().min(0).max(25), "amount");

    int free = monitor.getLoad(target);

    assertEquals(amount <= free, monitor.assignLoad(target, amount), "fit and answer disagree");
  }

  /**
   * Giving a node's load back restores it. A job that finishes has its load
   * reduced by the amount that was assigned, and the node must end up exactly
   * where it started or the cluster leaks capacity job by job.
   */
  @HegelTest
  void assigningThenReducingRestoresTheNode(TestCase tc) throws Exception {
    List<ResourceNode> nodes = drawCluster(tc);
    AssignmentMonitor monitor = new AssignmentMonitor(nodes);
    ResourceNode target = tc.draw(sampledFrom(nodes), "node");
    int amount = tc.draw(integers().min(0).max(25), "amount");

    int before = monitor.getLoad(target);
    tc.assume(monitor.assignLoad(target, amount));
    monitor.reduceLoad(target, amount);

    assertEquals(before, monitor.getLoad(target));
  }

  /** Every node the monitor was given can be looked up by its id, and nothing else can. */
  @HegelTest
  void onlyRegisteredNodesAreFoundById(TestCase tc) throws Exception {
    List<ResourceNode> nodes = drawCluster(tc);
    AssignmentMonitor monitor = new AssignmentMonitor(nodes);

    for (ResourceNode n : nodes) {
      assertEquals(n.getNodeId(), monitor.getNodeById(n.getNodeId()).getNodeId());
    }

    String absent = tc.draw(nodeId(), "absent");
    Set<String> known = new HashSet<>();
    for (ResourceNode n : nodes) {
      known.add(n.getNodeId());
    }
    tc.assume(!known.contains(absent));
    assertNull(monitor.getNodeById(absent), "found a node that was never registered");
  }

  /** The node list is exactly the nodes the monitor holds, with no duplicates. */
  @HegelTest
  void theNodeListIsTheRegisteredNodes(TestCase tc) throws Exception {
    List<ResourceNode> nodes = drawCluster(tc);
    AssignmentMonitor monitor = new AssignmentMonitor(nodes);

    Set<String> reported = new HashSet<>();
    for (ResourceNode n : monitor.getNodes()) {
      assertTrue(reported.add(n.getNodeId()), "node " + n.getNodeId() + " reported twice");
    }

    Set<String> expected = new HashSet<>();
    for (ResourceNode n : nodes) {
      expected.add(n.getNodeId());
    }
    assertEquals(expected, reported);
  }

  /** Adding a node makes it available and idle; removing it makes it unknown again. */
  @HegelTest
  void addingAndRemovingANodeIsSymmetric(TestCase tc) throws Exception {
    List<ResourceNode> nodes = drawCluster(tc);
    AssignmentMonitor monitor = new AssignmentMonitor(nodes);

    String id = tc.draw(nodeId(), "newNodeId");
    Set<String> known = new HashSet<>();
    for (ResourceNode n : nodes) {
      known.add(n.getNodeId());
    }
    tc.assume(!known.contains(id));
    ResourceNode added = node(id, tc.draw(integers().min(1).max(20), "newNodeCapacity"));

    monitor.addNode(added);
    assertNotNull(monitor.getNodeById(id), "added node not found");
    assertEquals(added.getCapacity(), monitor.getLoad(added), "added node not idle");

    monitor.removeNodeById(id);
    assertNull(monitor.getNodeById(id), "removed node still found");
    assertEquals(known.size(), monitor.getNodes().size(), "removal changed the rest of the cluster");
  }

  /**
   * A node can be found by its address. The batch manager knows a worker by
   * the URL it called in on and asks the monitor to turn that back into a
   * node; if the lookup only succeeds for the very {@link URL} object the
   * monitor happens to be holding, no caller that built its own URL — which is
   * every caller that read one off the wire — can ever find a node.
   */
  @HegelTest
  void aNodeCanBeFoundByItsAddress(TestCase tc) throws Exception {
    List<ResourceNode> nodes = drawCluster(tc);
    AssignmentMonitor monitor = new AssignmentMonitor(nodes);
    ResourceNode target = tc.draw(sampledFrom(nodes), "node");

    URL sameAddress = new URL(target.getIpAddr().toExternalForm());
    ResourceNode found = monitor.getNodeByURL(sameAddress);

    assertNotNull(found, "no node at " + sameAddress);
    assertEquals(target.getNodeId(), found.getNodeId());
  }

  /** Reducing below zero is clamped rather than wrapping the node's accounting. */
  @HegelTest
  void reducingMoreThanWasAssignedLeavesTheNodeIdle(TestCase tc) throws Exception {
    List<ResourceNode> nodes = drawCluster(tc);
    AssignmentMonitor monitor = new AssignmentMonitor(nodes);
    ResourceNode target = tc.draw(sampledFrom(nodes), "node");

    assertTrue(monitor.reduceLoad(target, tc.draw(integers().min(0).max(1000), "amount")));
    assertEquals(target.getCapacity(), monitor.getLoad(target));
    assertFalse(monitor.getLoad(target) < 0);
  }
}
