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

package org.apache.oodt.cas.resource.scheduler;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.resource.structs.exceptions.QueueManagerException;

/**
 * Properties of the queue-to-node mapping in {@link QueueManager}.
 *
 * <p>The class had no unit tests. It is the map the scheduler walks to decide
 * where a job may run, so the invariant that matters is that a queue only ever
 * reports nodes that were actually put in it, and that the two directions of
 * the mapping — nodes of a queue, queues of a node — never disagree.
 */
class QueueManagerPropertyTest {

  private static Generator<String> name() {
    return text().minSize(1).maxSize(6).categories("Lu", "Ll", "Nd");
  }

  /** A manager holding at least one queue, plus the list of queue names. */
  private static List<String> drawQueues(TestCase tc, QueueManager manager) {
    int count = tc.draw(integers().min(1).max(5), "queueCount");
    Set<String> queues = new HashSet<>();
    for (int i = 0; i < count; i++) {
      String queue = tc.draw(name(), "queue[" + i + "]");
      queues.add(queue);
      manager.addQueue(queue);
    }
    return new ArrayList<>(queues);
  }

  /** A queue that was added exists and shows up in the queue list exactly once. */
  @HegelTest
  void anAddedQueueExistsExactlyOnce(TestCase tc) {
    QueueManager manager = new QueueManager();
    List<String> queues = drawQueues(tc, manager);

    List<String> reported = manager.getQueues();
    assertEquals(new HashSet<>(queues), new HashSet<>(reported), "queue set differs");
    assertEquals(queues.size(), reported.size(), "a queue was listed twice");
    for (String queue : queues) {
      assertTrue(manager.containsQueue(queue), "queue " + queue + " missing");
    }
  }

  /** Adding the same queue again does not disturb the nodes already in it. */
  @HegelTest
  void addingAQueueTwiceKeepsItsNodes(TestCase tc) throws Exception {
    QueueManager manager = new QueueManager();
    List<String> queues = drawQueues(tc, manager);
    String queue = tc.draw(sampledFrom(queues), "queue");
    String node = tc.draw(name(), "node");
    manager.addNodeToQueue(node, queue);

    manager.addQueue(queue);

    assertTrue(manager.getNodes(queue).contains(node), "re-adding the queue emptied it");
  }

  /**
   * A queue reports exactly the nodes put into it, each once. The scheduler
   * places a job on one of these, so a node that appears here but was never
   * added is a job sent to a machine that is not in the queue.
   */
  @HegelTest
  void aQueueHoldsExactlyTheNodesAddedToIt(TestCase tc) throws Exception {
    QueueManager manager = new QueueManager();
    List<String> queues = drawQueues(tc, manager);
    String queue = tc.draw(sampledFrom(queues), "queue");

    int count = tc.draw(integers().min(0).max(6), "nodeCount");
    Set<String> added = new HashSet<>();
    for (int i = 0; i < count; i++) {
      String node = tc.draw(name(), "node[" + i + "]");
      added.add(node);
      manager.addNodeToQueue(node, queue);
    }

    List<String> reported = manager.getNodes(queue);
    assertEquals(added, new HashSet<>(reported));
    assertEquals(added.size(), reported.size(), "a node was listed twice in one queue");
  }

  /**
   * The two directions of the mapping agree: a node is in a queue's node list
   * if and only if that queue is in the node's queue list.
   */
  @HegelTest
  void theMappingAgreesInBothDirections(TestCase tc) throws Exception {
    QueueManager manager = new QueueManager();
    List<String> queues = drawQueues(tc, manager);

    int count = tc.draw(integers().min(0).max(8), "assignmentCount");
    Set<String> nodes = new HashSet<>();
    for (int i = 0; i < count; i++) {
      String node = tc.draw(name(), "node[" + i + "]");
      nodes.add(node);
      manager.addNodeToQueue(node, tc.draw(sampledFrom(queues), "node[" + i + "].queue"));
    }

    for (String node : nodes) {
      Set<String> queuesOfNode = new HashSet<>(manager.getQueues(node));
      for (String queue : queues) {
        assertEquals(
            manager.getNodes(queue).contains(node),
            queuesOfNode.contains(queue),
            "queue " + queue + " and node " + node + " disagree");
      }
    }
  }

  /** Removing a node from a queue removes it from that queue and no other. */
  @HegelTest
  void removingANodeAffectsOnlyThatQueue(TestCase tc) throws Exception {
    QueueManager manager = new QueueManager();
    List<String> queues = drawQueues(tc, manager);
    String node = tc.draw(name(), "node");
    for (String queue : queues) {
      manager.addNodeToQueue(node, queue);
    }
    String removeFrom = tc.draw(sampledFrom(queues), "removeFrom");

    manager.removeNodeFromQueue(node, removeFrom);

    for (String queue : queues) {
      assertEquals(
          !queue.equals(removeFrom),
          manager.getNodes(queue).contains(node),
          "wrong membership for queue " + queue);
    }
  }

  /** Removing a node that is not in the queue leaves the queue alone. */
  @HegelTest
  void removingAnAbsentNodeChangesNothing(TestCase tc) throws Exception {
    QueueManager manager = new QueueManager();
    List<String> queues = drawQueues(tc, manager);
    String queue = tc.draw(sampledFrom(queues), "queue");
    String present = tc.draw(name(), "present");
    String absent = tc.draw(name(), "absent");
    tc.assume(!present.equals(absent));
    manager.addNodeToQueue(present, queue);

    manager.removeNodeFromQueue(absent, queue);

    assertEquals(List.of(present), manager.getNodes(queue));
  }

  /** A removed queue no longer exists, and asking for its nodes is an error. */
  @HegelTest
  void aRemovedQueueIsGone(TestCase tc) {
    QueueManager manager = new QueueManager();
    List<String> queues = drawQueues(tc, manager);
    String queue = tc.draw(sampledFrom(queues), "queue");

    manager.removeQueue(queue);

    assertFalse(manager.containsQueue(queue));
    assertFalse(manager.getQueues().contains(queue));
    assertThrows(QueueManagerException.class, () -> manager.getNodes(queue));
  }

  /**
   * A queue that does not exist cannot be quietly created by putting a node in
   * it: the operator is told, rather than the job being routed into a queue
   * nobody configured.
   */
  @HegelTest
  void addingANodeToAnUnknownQueueIsAnError(TestCase tc) {
    QueueManager manager = new QueueManager();
    List<String> queues = drawQueues(tc, manager);
    String unknown = tc.draw(name(), "unknown");
    tc.assume(!queues.contains(unknown));

    assertThrows(
        QueueManagerException.class, () -> manager.addNodeToQueue(tc.draw(name(), "node"), unknown));
    assertFalse(manager.containsQueue(unknown), "the failed call created the queue anyway");
  }

  /**
   * The node list a caller is handed is a copy: the scheduler iterates it
   * while the manager is being reconfigured, and must not be able to corrupt
   * the mapping through it.
   */
  @HegelTest
  void theNodeListIsACopy(TestCase tc) throws Exception {
    QueueManager manager = new QueueManager();
    List<String> queues = drawQueues(tc, manager);
    String queue = tc.draw(sampledFrom(queues), "queue");
    String node = tc.draw(name(), "node");
    manager.addNodeToQueue(node, queue);

    manager.getNodes(queue).clear();

    assertEquals(List.of(node), manager.getNodes(queue), "the manager shares its internal list");
  }

  /** A queue with no name is not a queue, and is never created. */
  @HegelTest
  void aQueueWithNoNameIsNeverCreated(TestCase tc) {
    QueueManager manager = new QueueManager();
    drawQueues(tc, manager);
    int before = manager.getQueues().size();

    manager.addQueue(null);

    assertEquals(before, manager.getQueues().size());
    assertFalse(manager.containsQueue(null));
    assertThrows(QueueManagerException.class, () -> manager.getNodes(null));
    assertThrows(QueueManagerException.class, () -> manager.addNodeToQueue("someNode", null));
  }
}
