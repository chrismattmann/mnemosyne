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

package org.apache.oodt.pcs.pedigree;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.Product;

/**
 * Properties of the depth arithmetic in {@link PedigreeTree}.
 *
 * <p>The opsui pedigree browser indents one level per generation and sizes its
 * panel from {@code getNumLevels}, so the number it reports has to be the
 * number of generations the tree actually holds.
 */
class PedigreeTreePropertyTest {

  private static PedigreeTreeNode node(PedigreeTreeNode parent, String name) {
    Product p = new Product();
    p.setProductName(name);
    return PedigreeTreeNode.getPedigreeTreeNodeFromProduct(p, parent);
  }

  /** Depth of {@code node} below the root, computed independently of the class. */
  private static int depthOf(PedigreeTreeNode node, int level) {
    int deepest = level;
    for (int i = 0; i < node.getNumChildren(); i++) {
      deepest = Math.max(deepest, depthOf(node.getChildAt(i), level + 1));
    }
    return deepest;
  }

  /**
   * A pedigree with no product at its root has no levels to draw at all.
   */
  @HegelTest
  void anEmptyTreeHasNoLevels(TestCase tc) {
    tc.note("no draws: the empty tree is a single case, stated here so it is not forgotten");
    assertEquals(0, new PedigreeTree(null).getNumLevels());
  }

  /**
   * A straight chain of ancestors — each product produced from exactly one
   * other — has one level per product. This is the shape a plain
   * upstream trace produces.
   */
  @HegelTest
  void aChainHasOneLevelPerProduct(TestCase tc) {
    int length = tc.draw(integers().min(1).max(60), "length");

    PedigreeTreeNode root = node(null, "p0");
    PedigreeTreeNode current = root;
    for (int i = 1; i < length; i++) {
      current = node(current, "p" + i);
    }

    assertEquals(length, new PedigreeTree(root).getNumLevels());
  }

  /**
   * For any shape of pedigree, the level count is the depth of the deepest
   * product plus one for the root itself.
   */
  @HegelTest
  void numLevelsIsTheDeepestGenerationPlusTheRoot(TestCase tc) {
    List<Integer> parentChoices = tc.draw(lists(integers().min(0).max(1000)).minSize(0).maxSize(40),
        "parentChoices");

    List<PedigreeTreeNode> nodes = new ArrayList<>();
    nodes.add(node(null, "p0"));
    for (int i = 0; i < parentChoices.size(); i++) {
      PedigreeTreeNode parent = nodes.get(parentChoices.get(i) % nodes.size());
      nodes.add(node(parent, "p" + (i + 1)));
    }

    PedigreeTree tree = new PedigreeTree(nodes.get(0));

    assertEquals(depthOf(nodes.get(0), 0) + 1, tree.getNumLevels());
  }

  /**
   * Adding a product to a pedigree never shrinks it, and never grows it by
   * more than the one generation the new product can introduce.
   */
  @HegelTest
  void attachingAProductGrowsTheTreeByAtMostOneLevel(TestCase tc) {
    List<Integer> parentChoices = tc.draw(lists(integers().min(0).max(1000)).minSize(0).maxSize(20),
        "parentChoices");
    int attachTo = tc.draw(integers().min(0).max(1000), "attachTo");

    List<PedigreeTreeNode> nodes = new ArrayList<>();
    nodes.add(node(null, "p0"));
    for (int i = 0; i < parentChoices.size(); i++) {
      nodes.add(node(nodes.get(parentChoices.get(i) % nodes.size()), "p" + (i + 1)));
    }

    PedigreeTree tree = new PedigreeTree(nodes.get(0));
    int before = tree.getNumLevels();

    node(nodes.get(attachTo % nodes.size()), "extra");
    int after = tree.getNumLevels();

    assertTrue(after >= before, "the tree lost a level when a product was added");
    assertTrue(after <= before + 1, "one product added " + (after - before) + " levels");
  }
}
