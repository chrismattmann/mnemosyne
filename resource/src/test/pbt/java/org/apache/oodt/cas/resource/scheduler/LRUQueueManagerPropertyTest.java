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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Least-recently-used ordering properties of {@link LRUQueueManager}.
 *
 * <p>The class had no unit tests. {@code LRUScheduler.nodeAvailable} walks a
 * queue's nodes in the order this class reports them and takes the first that
 * has room, then tells the manager the node was used. The whole scheduling
 * policy is therefore just this ordering, and these properties state it:
 * a used node goes to the back, everything else keeps its place, and no node
 * is ever gained or lost in the process.
 */
class LRUQueueManagerPropertyTest {

  private static Generator<String> name() {
    return text().minSize(1).maxSize(6).categories("Lu", "Ll", "Nd");
  }

  /** Distinct node ids, since a queue is a set of nodes. */
  private static List<String> drawNodes(TestCase tc) {
    int count = tc.draw(integers().min(1).max(6), "nodeCount");
    Set<String> nodes = new LinkedHashSet<>();
    for (int i = 0; i < count; i++) {
      nodes.add(tc.draw(name(), "node[" + i + "]"));
    }
    return new ArrayList<>(nodes);
  }

  private static LRUQueueManager managerOf(String queue, List<String> nodes) throws Exception {
    QueueManager plain = new QueueManager();
    plain.addQueue(queue);
    for (String node : nodes) {
      plain.addNodeToQueue(node, queue);
    }
    return new LRUQueueManager(plain);
  }

  /**
   * The LRU manager starts out holding exactly the queues and nodes it was
   * built from, in the order they were configured. The first job scheduled
   * after a restart goes to the first node in nodes.xml because of this.
   */
  @HegelTest
  void itStartsAsACopyOfTheManagerItWrapped(TestCase tc) throws Exception {
    String queue = tc.draw(name(), "queue");
    List<String> nodes = drawNodes(tc);

    LRUQueueManager lru = managerOf(queue, nodes);

    assertEquals(List.of(queue), lru.getQueues());
    assertEquals(nodes, lru.getNodes(queue));
  }

  /**
   * Using a node moves it to the back of the queue and leaves the relative
   * order of the others alone. That is what makes the next job go to a
   * different machine.
   */
  @HegelTest
  void usingANodeSendsItToTheBack(TestCase tc) throws Exception {
    String queue = tc.draw(name(), "queue");
    List<String> nodes = drawNodes(tc);
    LRUQueueManager lru = managerOf(queue, nodes);
    String used = tc.draw(sampledFrom(nodes), "used");

    lru.usedNode(queue, used);

    List<String> after = lru.getNodes(queue);
    assertEquals(used, after.get(after.size() - 1), "the used node is not last");

    List<String> expectedRest = new ArrayList<>(nodes);
    expectedRest.remove(used);
    assertEquals(expectedRest, after.subList(0, after.size() - 1), "the other nodes were reordered");
  }

  /** Using nodes never adds or drops one: the queue keeps its membership. */
  @HegelTest
  void usingNodesPreservesTheQueueMembership(TestCase tc) throws Exception {
    String queue = tc.draw(name(), "queue");
    List<String> nodes = drawNodes(tc);
    LRUQueueManager lru = managerOf(queue, nodes);

    int steps = tc.draw(integers().min(0).max(15), "steps");
    for (int i = 0; i < steps; i++) {
      lru.usedNode(queue, tc.draw(sampledFrom(nodes), "step[" + i + "]"));
    }

    List<String> after = lru.getNodes(queue);
    assertEquals(new HashSet<>(nodes), new HashSet<>(after), "the queue gained or lost a node");
    assertEquals(nodes.size(), after.size(), "a node appears twice in the queue");
  }

  /**
   * After a run of uses, the queue is ordered by how long ago each node was
   * last used: never-used nodes first in their original order, then the used
   * ones in the order they were used. This is the property the scheduler's
   * fairness rests on.
   */
  @HegelTest
  void theQueueIsOrderedByHowLongAgoEachNodeWasUsed(TestCase tc) throws Exception {
    String queue = tc.draw(name(), "queue");
    List<String> nodes = drawNodes(tc);
    LRUQueueManager lru = managerOf(queue, nodes);

    int steps = tc.draw(integers().min(0).max(15), "steps");
    List<String> uses = new ArrayList<>();
    for (int i = 0; i < steps; i++) {
      String used = tc.draw(sampledFrom(nodes), "step[" + i + "]");
      uses.add(used);
      lru.usedNode(queue, used);
    }

    List<String> expected = new ArrayList<>(nodes);
    for (String used : uses) {
      expected.remove(used);
      expected.add(used);
    }

    assertEquals(expected, lru.getNodes(queue));
  }

  /** Using a node twice in a row is the same as using it once. */
  @HegelTest
  void usingTheSameNodeTwiceIsIdempotent(TestCase tc) throws Exception {
    String queue = tc.draw(name(), "queue");
    List<String> nodes = drawNodes(tc);
    LRUQueueManager lru = managerOf(queue, nodes);
    String used = tc.draw(sampledFrom(nodes), "used");

    lru.usedNode(queue, used);
    List<String> once = new ArrayList<>(lru.getNodes(queue));
    lru.usedNode(queue, used);

    assertEquals(once, lru.getNodes(queue));
  }

  /** Using a node in one queue does not reorder any other queue. */
  @HegelTest
  void usingANodeDoesNotDisturbOtherQueues(TestCase tc) throws Exception {
    String queueA = tc.draw(name(), "queueA");
    String queueB = tc.draw(name(), "queueB");
    tc.assume(!queueA.equals(queueB));
    List<String> nodes = drawNodes(tc);

    QueueManager plain = new QueueManager();
    plain.addQueue(queueA);
    plain.addQueue(queueB);
    for (String node : nodes) {
      plain.addNodeToQueue(node, queueA);
      plain.addNodeToQueue(node, queueB);
    }
    LRUQueueManager lru = new LRUQueueManager(plain);

    lru.usedNode(queueA, tc.draw(sampledFrom(nodes), "used"));

    assertEquals(nodes, lru.getNodes(queueB));
    assertTrue(lru.containsQueue(queueA) && lru.containsQueue(queueB));
  }
}
