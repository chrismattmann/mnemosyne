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

package org.apache.oodt.cas.resource.util;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.oodt.cas.resource.structs.ResourceNode;

/**
 * The {@link java.util.Comparator} contract for {@link ResourceNodeComparator}.
 *
 * <p>The class had no unit tests. It is handed to {@code Collections.sort} on
 * lists of nodes, and a comparator that breaks the contract makes that sort
 * throw "Comparison method violates its general contract" on a large enough
 * list, so these are properties a caller genuinely depends on.
 */
class ResourceNodeComparatorPropertyTest {

  private static final ResourceNodeComparator COMPARATOR = new ResourceNodeComparator();

  /**
   * Node ids drawn from a deliberately small alphabet so that ties — the case
   * that actually distinguishes a correct comparator from a broken one — turn
   * up often.
   */
  private static Generator<String> nodeId() {
    return text().minSize(1).maxSize(3).categories("Lu");
  }

  private static ResourceNode node(String id) {
    ResourceNode node = new ResourceNode();
    node.setId(id);
    return node;
  }

  private static int sgn(int n) {
    return Integer.compare(n, 0);
  }

  /** Comparing a node with itself must report equality. */
  @HegelTest
  void isReflexive(TestCase tc) {
    ResourceNode a = node(tc.draw(nodeId(), "a"));

    assertEquals(0, COMPARATOR.compare(a, a));
  }

  /** Reversing the arguments must reverse the sign, never repeat it. */
  @HegelTest
  void isAntisymmetric(TestCase tc) {
    ResourceNode a = node(tc.draw(nodeId(), "a"));
    ResourceNode b = node(tc.draw(nodeId(), "b"));

    assertEquals(sgn(COMPARATOR.compare(a, b)), -sgn(COMPARATOR.compare(b, a)));
  }

  /** If a sorts before b and b before c, then a must sort before c. */
  @HegelTest
  void isTransitive(TestCase tc) {
    ResourceNode a = node(tc.draw(nodeId(), "a"));
    ResourceNode b = node(tc.draw(nodeId(), "b"));
    ResourceNode c = node(tc.draw(nodeId(), "c"));

    tc.assume(COMPARATOR.compare(a, b) < 0 && COMPARATOR.compare(b, c) < 0);

    assertTrue(COMPARATOR.compare(a, c) < 0, "a < b < c but not a < c");
  }

  /**
   * Nodes that compare equal must compare the same way against any third node,
   * which is the part of the contract {@code Collections.sort} relies on when
   * it merges runs.
   */
  @HegelTest
  void tiesAreSubstitutable(TestCase tc) {
    ResourceNode a = node(tc.draw(nodeId(), "a"));
    ResourceNode b = node(tc.draw(nodeId(), "b"));
    ResourceNode c = node(tc.draw(nodeId(), "c"));

    tc.assume(COMPARATOR.compare(a, b) == 0);

    assertEquals(sgn(COMPARATOR.compare(a, c)), sgn(COMPARATOR.compare(b, c)));
  }

  /**
   * Sorting a list of nodes keeps every node and orders them by id. A node
   * report is built this way, so losing or duplicating a node here means an
   * operator is shown a node list that does not match the cluster.
   */
  @HegelTest
  void sortingIsAnOrderedPermutation(TestCase tc) {
    List<String> ids = tc.draw(lists(nodeId()).minSize(0).maxSize(20), "ids");
    List<ResourceNode> nodes = new ArrayList<>();
    for (String id : ids) {
      nodes.add(node(id));
    }

    Collections.sort(nodes, COMPARATOR);

    List<String> sortedIds = new ArrayList<>();
    for (ResourceNode n : nodes) {
      sortedIds.add(n.getNodeId());
    }

    List<String> expected = new ArrayList<>(ids);
    Collections.sort(expected);
    assertEquals(expected, sortedIds);
  }

  /**
   * The comparison depends on the node id alone: capacity and address are not
   * part of the ordering, so two nodes that differ only in those must tie.
   */
  @HegelTest
  void onlyTheIdDecidesTheOrder(TestCase tc) {
    String id = tc.draw(nodeId(), "id");
    ResourceNode a = node(id);
    ResourceNode b = node(id);
    a.setCapacity(tc.draw(integers().min(0).max(100), "capacityA"));
    b.setCapacity(tc.draw(integers().min(0).max(100), "capacityB"));

    assertEquals(0, COMPARATOR.compare(a, b));
  }
}
