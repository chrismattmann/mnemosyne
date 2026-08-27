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

package org.apache.oodt.cas.resource.noderepo;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.resource.structs.ResourceNode;

/**
 * Properties of {@link XmlNodeRepository}, which reads the resource manager's
 * pool of compute nodes off disk.
 *
 * <p>Every node in these files is a machine the resource manager will schedule
 * work onto. A node dropped by the reader is a machine that sits idle; a
 * capacity read wrongly is a machine that gets overloaded or underused. Each
 * property writes {@code nodes.xml} files into fresh temporary directories and
 * deletes them in a {@code finally} block.
 */
class XmlNodeRepositoryPropertyTest {

  /** A node id. */
  private static final Generator<String> NODE_ID =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  private static File freshDir() throws IOException {
    return Files.createTempDirectory("resource-pbt").toFile();
  }

  private static void delete(File dir) {
    File[] children = dir.listFiles();
    if (children != null) {
      for (File child : children) {
        delete(child);
      }
    }
    if (!dir.delete()) {
      dir.deleteOnExit();
    }
  }

  private static List<String> distinct(List<String> values) {
    Set<String> set = new LinkedHashSet<>(values);
    return new ArrayList<>(set);
  }

  private static void writeNodes(File dir, String name, List<String> ids, List<Integer> capacities)
      throws IOException {
    StringBuilder xml = new StringBuilder(
        "<cas:resourcenodes xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\">");
    for (int i = 0; i < ids.size(); i++) {
      xml.append("<node nodeId=\"").append(ids.get(i))
          .append("\" ip=\"http://localhost:").append(20000 + i)
          .append("\" capacity=\"").append(capacities.get(i % capacities.size()))
          .append("\"/>");
    }
    xml.append("</cas:resourcenodes>");
    Files.write(new File(dir, name).toPath(), xml.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Every node declared in a {@code nodes.xml} is loaded, in file order, with
   * the id, address and capacity it was given.
   */
  @HegelTest(testCases = 30)
  void everyDeclaredNodeIsLoaded(TestCase tc) throws Exception {
    List<String> ids = distinct(tc.draw(lists(NODE_ID).minSize(1).maxSize(6), "ids"));
    List<Integer> capacities =
        tc.draw(lists(integers().min(0).max(128)).minSize(1).maxSize(6), "capacities");

    File dir = freshDir();
    try {
      writeNodes(dir, "nodes.xml", ids, capacities);

      List<ResourceNode> nodes =
          new XmlNodeRepository(List.of(dir.toURI().toString())).loadNodes();

      assertNotNull(nodes, "no node list at all");
      assertEquals(ids.size(), nodes.size(), "a node was gained or lost");
      for (int i = 0; i < ids.size(); i++) {
        assertEquals(ids.get(i), nodes.get(i).getNodeId(), "node " + i + " changed identity");
        assertEquals(capacities.get(i % capacities.size()).intValue(),
            nodes.get(i).getCapacity(), "node [" + ids.get(i) + "] changed capacity");
        assertEquals("http://localhost:" + (20000 + i), nodes.get(i).getIpAddr().toString(),
            "node [" + ids.get(i) + "] changed address");
      }
    } finally {
      delete(dir);
    }
  }

  /**
   * The pool is the union of every directory the repository was pointed at.
   * Installations split their node declarations across policy directories, so
   * a repository that reads only the first one silently halves the cluster.
   */
  @HegelTest(testCases = 25)
  void thePoolIsTheUnionOfEveryDirectory(TestCase tc) throws Exception {
    List<String> firstIds = distinct(tc.draw(lists(NODE_ID).minSize(1).maxSize(3), "firstIds"));
    List<String> secondIds = distinct(tc.draw(lists(NODE_ID).minSize(1).maxSize(3), "secondIds"));

    File first = freshDir();
    File second = freshDir();
    try {
      writeNodes(first, "nodes.xml", firstIds, List.of(1));
      writeNodes(second, "nodes.xml", secondIds, List.of(1));

      List<ResourceNode> nodes = new XmlNodeRepository(
          List.of(first.toURI().toString(), second.toURI().toString())).loadNodes();

      assertEquals(firstIds.size() + secondIds.size(), nodes.size(),
          "the pool is not the union of both directories");
      List<String> loaded = new ArrayList<>();
      for (ResourceNode node : nodes) {
        loaded.add(node.getNodeId());
      }
      for (String id : firstIds) {
        assertTrue(loaded.contains(id), "node [" + id + "] from the first directory is missing");
      }
      for (String id : secondIds) {
        assertTrue(loaded.contains(id), "node [" + id + "] from the second directory is missing");
      }
    } finally {
      delete(first);
      delete(second);
    }
  }

  /**
   * Only files named {@code nodes.xml} contribute nodes. A policy directory
   * holds several different XML files side by side, and picking up the wrong
   * one would either fail or invent nodes.
   */
  @HegelTest(testCases = 25)
  void onlyNodesXmlContributesNodes(TestCase tc) throws Exception {
    List<String> ids = distinct(tc.draw(lists(NODE_ID).minSize(1).maxSize(4), "ids"));
    List<String> decoyIds = distinct(tc.draw(lists(NODE_ID).minSize(1).maxSize(4), "decoyIds"));

    File dir = freshDir();
    try {
      writeNodes(dir, "nodes.xml", ids, List.of(4));
      writeNodes(dir, "other-policy.xml", decoyIds, List.of(4));

      List<ResourceNode> nodes =
          new XmlNodeRepository(List.of(dir.toURI().toString())).loadNodes();

      assertEquals(ids.size(), nodes.size(),
          "a file that is not nodes.xml contributed nodes");
    } finally {
      delete(dir);
    }
  }

  /**
   * A directory URI that names nothing yields an empty pool rather than an
   * exception. The resource manager is handed a list of policy directories from
   * configuration, and one that has not been created yet must not stop it
   * starting.
   */
  @HegelTest(testCases = 20)
  void aDirectoryThatIsNotThereYieldsAnEmptyPool(TestCase tc) throws Exception {
    String name = tc.draw(NODE_ID, "name");

    File dir = freshDir();
    try {
      String missing = new File(dir, name + "-absent").toURI().toString();

      List<ResourceNode> nodes = new XmlNodeRepository(List.of(missing)).loadNodes();

      assertNotNull(nodes, "a missing policy directory produced no node list at all");
      assertTrue(nodes.isEmpty(), "nodes appeared from a directory that does not exist");
    } finally {
      delete(dir);
    }
  }
}
