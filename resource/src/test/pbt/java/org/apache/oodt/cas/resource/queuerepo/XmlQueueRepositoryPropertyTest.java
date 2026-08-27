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

package org.apache.oodt.cas.resource.queuerepo;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.oodt.cas.resource.scheduler.QueueManager;

/**
 * Properties of {@link XmlQueueRepository}, which reads the node-to-queue
 * mapping off disk.
 *
 * <p>This mapping decides which machines a queue's jobs may run on. A node
 * missing from a queue narrows where that queue's work can go; a queue that
 * never gets created makes every job submitted to it unschedulable. Each
 * property writes a mapping file into a fresh temporary directory and deletes
 * it in a {@code finally} block.
 */
class XmlQueueRepositoryPropertyTest {

  private static final String MAPPING_FILE = "node-to-queue-mapping.xml";

  /** A node id or queue name. */
  private static final Generator<String> WORD =
      text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");

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

  private static void writeMapping(File dir, Map<String, List<String>> nodeToQueues)
      throws IOException {
    StringBuilder xml = new StringBuilder(
        "<cas:node-to-queue-mapping xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\">");
    for (Map.Entry<String, List<String>> entry : nodeToQueues.entrySet()) {
      xml.append("<node id=\"").append(entry.getKey()).append("\"><queues>");
      for (String queue : entry.getValue()) {
        xml.append("<queue name=\"").append(queue).append("\"/>");
      }
      xml.append("</queues></node>");
    }
    xml.append("</cas:node-to-queue-mapping>");
    Files.write(new File(dir, MAPPING_FILE).toPath(),
        xml.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Every queue named anywhere in the mapping exists afterwards, and holds
   * exactly the nodes assigned to it. This is the whole content of the file: a
   * node listed under a queue and not found there is a machine the scheduler
   * will never pick for that queue's work.
   */
  @HegelTest(testCases = 30)
  void everyAssignmentInTheFileIsInTheQueueManager(TestCase tc) throws Exception {
    List<String> nodeIds = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "nodeIds"));
    List<String> queueNames = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "queueNames"));

    Map<String, List<String>> nodeToQueues = new LinkedHashMap<>();
    Map<String, Set<String>> expectedQueueToNodes = new LinkedHashMap<>();
    for (int i = 0; i < nodeIds.size(); i++) {
      // Give each node a rotating slice of the queues so the assignment is not
      // uniform: every queue gets at least one node, and nodes differ.
      List<String> queues = new ArrayList<>();
      for (int j = 0; j <= i % queueNames.size(); j++) {
        queues.add(queueNames.get(j));
      }
      nodeToQueues.put(nodeIds.get(i), queues);
      for (String queue : queues) {
        expectedQueueToNodes.computeIfAbsent(queue, key -> new LinkedHashSet<>())
            .add(nodeIds.get(i));
      }
    }

    File dir = freshDir();
    try {
      writeMapping(dir, nodeToQueues);

      QueueManager manager =
          new XmlQueueRepository(List.of(dir.toURI().toString())).loadQueues();

      assertNotNull(manager, "no queue manager was built");
      for (Map.Entry<String, Set<String>> entry : expectedQueueToNodes.entrySet()) {
        assertTrue(manager.containsQueue(entry.getKey()),
            "queue [" + entry.getKey() + "] was never created");
        assertEquals(entry.getValue(), new LinkedHashSet<>(manager.getNodes(entry.getKey())),
            "queue [" + entry.getKey() + "] holds the wrong nodes");
      }
    } finally {
      delete(dir);
    }
  }

  /**
   * No queue is invented. A queue the scheduler believes in but nobody
   * configured accepts jobs that can never be run.
   */
  @HegelTest(testCases = 30)
  void noQueueIsInvented(TestCase tc) throws Exception {
    List<String> nodeIds = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "nodeIds"));
    List<String> queueNames = distinct(tc.draw(lists(WORD).minSize(1).maxSize(3), "queueNames"));

    Map<String, List<String>> nodeToQueues = new LinkedHashMap<>();
    for (String nodeId : nodeIds) {
      nodeToQueues.put(nodeId, queueNames);
    }

    File dir = freshDir();
    try {
      writeMapping(dir, nodeToQueues);

      QueueManager manager =
          new XmlQueueRepository(List.of(dir.toURI().toString())).loadQueues();

      assertEquals(new LinkedHashSet<>(queueNames), new LinkedHashSet<>(manager.getQueues()),
          "the queue set is not the set named in the file");
    } finally {
      delete(dir);
    }
  }

  /**
   * A directory URI that names nothing yields an empty queue manager rather
   * than an exception, so a policy directory that has not been created yet does
   * not stop the resource manager starting.
   */
  @HegelTest(testCases = 20)
  void aDirectoryThatIsNotThereYieldsAnEmptyQueueManager(TestCase tc) throws Exception {
    String name = tc.draw(WORD, "name");

    File dir = freshDir();
    try {
      String missing = new File(dir, name + "-absent").toURI().toString();

      QueueManager manager = new XmlQueueRepository(List.of(missing)).loadQueues();

      assertNotNull(manager, "a missing policy directory produced no queue manager");
      assertTrue(manager.getQueues().isEmpty(),
          "queues appeared from a directory that does not exist");
    } finally {
      delete(dir);
    }
  }
}
