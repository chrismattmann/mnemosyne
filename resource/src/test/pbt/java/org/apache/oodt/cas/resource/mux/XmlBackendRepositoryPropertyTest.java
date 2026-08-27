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

package org.apache.oodt.cas.resource.mux;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.apache.oodt.cas.resource.structs.exceptions.QueueManagerException;
import org.apache.oodt.cas.resource.structs.exceptions.RepositoryException;

/**
 * Properties of {@link XmlBackendRepository}, which reads the queue-to-backend
 * mapping that a multiplexing resource manager runs on.
 *
 * <p>Loading this file has a side effect: for each queue the repository sets
 * two system properties, builds that queue's scheduler while they are in force,
 * and puts them back. The properties here cover both the result — one backend
 * set per declared queue — and that handover, which is otherwise invisible.
 * {@link StubScheduler} is named in the generated files so that no real
 * monitor, batch manager or socket is involved, and every system property this
 * test can see is saved and restored in a {@code finally} block.
 */
class XmlBackendRepositoryPropertyTest {

  private static final String MONITOR_PROPERTY = "resource.monitor.factory";
  private static final String BATCHMGR_PROPERTY = "resource.batchmgr.factory";
  private static final String SCHEDULER_FACTORY =
      "org.apache.oodt.cas.resource.mux.StubSchedulerFactory";

  /** A queue name, or a factory class name written into the file. */
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

  private static void restore(String key, String original) {
    if (original == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, original);
    }
  }

  private static List<String> distinct(List<String> values) {
    Set<String> set = new LinkedHashSet<>(values);
    return new ArrayList<>(set);
  }

  /** Writes a queue-to-backend file naming a monitor and batchmgr per queue. */
  private static File writeMapping(File dir, Map<String, String[]> queueToFactories)
      throws IOException {
    StringBuilder xml = new StringBuilder("<queues>");
    for (Map.Entry<String, String[]> entry : queueToFactories.entrySet()) {
      xml.append("<queue name=\"").append(entry.getKey()).append("\">")
          .append("<scheduler factory=\"").append(SCHEDULER_FACTORY).append("\"/>")
          .append("<monitor factory=\"").append(entry.getValue()[0]).append("\"/>")
          .append("<batchmgr factory=\"").append(entry.getValue()[1]).append("\"/>")
          .append("</queue>");
    }
    xml.append("</queues>");
    File file = new File(dir, "queue-to-backend.xml");
    Files.write(file.toPath(), xml.toString().getBytes(StandardCharsets.UTF_8));
    return file;
  }

  /**
   * Every queue declared in the file has a backend afterwards, and no queue
   * that was not declared does. A queue without a backend cannot run a job, and
   * a backend for a queue nobody configured is a queue jobs can vanish into.
   */
  @HegelTest(testCases = 25)
  void everyDeclaredQueueGetsABackend(TestCase tc) throws Exception {
    List<String> queues = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "queues"));
    String other = tc.draw(WORD, "other");

    Map<String, String[]> mapping = new LinkedHashMap<>();
    for (String queue : queues) {
      mapping.put(queue, new String[] {"MonitorFor" + queue, "BatchmgrFor" + queue});
    }

    String originalMonitor = System.getProperty(MONITOR_PROPERTY);
    String originalBatchmgr = System.getProperty(BATCHMGR_PROPERTY);
    File dir = freshDir();
    try {
      File file = writeMapping(dir, mapping);

      BackendManager manager = new XmlBackendRepository(file.toURI().toString()).load();

      assertNotNull(manager, "no backend manager was built");
      for (String queue : queues) {
        assertNotNull(manager.getScheduler(queue),
            "queue [" + queue + "] has no scheduler");
      }
      if (!queues.contains(other)) {
        assertThrows(QueueManagerException.class, () -> manager.getScheduler(other),
            "a backend appeared for queue [" + other + "], which nobody declared");
      }
    } finally {
      restore(MONITOR_PROPERTY, originalMonitor);
      restore(BATCHMGR_PROPERTY, originalBatchmgr);
      delete(dir);
    }
  }

  /**
   * Each queue's scheduler is built while that queue's own monitor and batch
   * manager factories are the ones in force. This is the entire mechanism by
   * which a per-queue backend differs from the system default; if the wrong
   * queue's factories are in force, every queue ends up on the same backend.
   */
  @HegelTest(testCases = 25)
  void eachSchedulerSeesItsOwnQueuesFactories(TestCase tc) throws Exception {
    List<String> queues = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "queues"));

    Map<String, String[]> mapping = new LinkedHashMap<>();
    for (String queue : queues) {
      mapping.put(queue, new String[] {"MonitorFor" + queue, "BatchmgrFor" + queue});
    }

    String originalMonitor = System.getProperty(MONITOR_PROPERTY);
    String originalBatchmgr = System.getProperty(BATCHMGR_PROPERTY);
    File dir = freshDir();
    try {
      File file = writeMapping(dir, mapping);

      BackendManager manager = new XmlBackendRepository(file.toURI().toString()).load();

      for (String queue : queues) {
        StubScheduler scheduler = (StubScheduler) manager.getScheduler(queue);
        assertEquals("MonitorFor" + queue, scheduler.getObservedMonitorFactory(),
            "queue [" + queue + "] was built against another queue's monitor factory");
        assertEquals("BatchmgrFor" + queue, scheduler.getObservedBatchmgrFactory(),
            "queue [" + queue + "] was built against another queue's batchmgr factory");
      }
    } finally {
      restore(MONITOR_PROPERTY, originalMonitor);
      restore(BATCHMGR_PROPERTY, originalBatchmgr);
      delete(dir);
    }
  }

  /**
   * Loading leaves the JVM's factory properties exactly as it found them. These
   * are global settings that everything else in the resource manager reads;
   * loading a policy file must not repoint the rest of the process at the last
   * queue's backend.
   */
  @HegelTest(testCases = 25)
  void loadingRestoresTheFactoryProperties(TestCase tc) throws Exception {
    List<String> queues = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "queues"));
    String presetMonitor = tc.draw(WORD, "presetMonitor");
    String presetBatchmgr = tc.draw(WORD, "presetBatchmgr");

    Map<String, String[]> mapping = new LinkedHashMap<>();
    for (String queue : queues) {
      mapping.put(queue, new String[] {"MonitorFor" + queue, "BatchmgrFor" + queue});
    }

    String originalMonitor = System.getProperty(MONITOR_PROPERTY);
    String originalBatchmgr = System.getProperty(BATCHMGR_PROPERTY);
    File dir = freshDir();
    try {
      System.setProperty(MONITOR_PROPERTY, presetMonitor);
      System.setProperty(BATCHMGR_PROPERTY, presetBatchmgr);

      File file = writeMapping(dir, mapping);
      new XmlBackendRepository(file.toURI().toString()).load();

      assertEquals(presetMonitor, System.getProperty(MONITOR_PROPERTY),
          "loading left the monitor factory property pointing somewhere else");
      assertEquals(presetBatchmgr, System.getProperty(BATCHMGR_PROPERTY),
          "loading left the batchmgr factory property pointing somewhere else");
    } finally {
      restore(MONITOR_PROPERTY, originalMonitor);
      restore(BATCHMGR_PROPERTY, originalBatchmgr);
      delete(dir);
    }
  }

  /**
   * A file that is not there is reported as a repository failure, which is what
   * the class documents and what the resource manager's start-up catches.
   */
  @HegelTest(testCases = 20)
  void aMissingFileIsReportedAsARepositoryFailure(TestCase tc) throws Exception {
    String name = tc.draw(WORD, "name");

    String originalMonitor = System.getProperty(MONITOR_PROPERTY);
    String originalBatchmgr = System.getProperty(BATCHMGR_PROPERTY);
    File dir = freshDir();
    try {
      String missing = new File(dir, name + "-absent.xml").toURI().toString();
      assertThrows(RepositoryException.class,
          () -> new XmlBackendRepository(missing).load());
    } finally {
      restore(MONITOR_PROPERTY, originalMonitor);
      restore(BATCHMGR_PROPERTY, originalBatchmgr);
      delete(dir);
    }
  }

  /**
   * A queue that names no scheduler is reported as a repository failure rather
   * than being quietly given a backend with no scheduler in it. The class's own
   * error message — "could not find exactly one scheduler, with factory set" —
   * says this is the intent.
   */
  @HegelTest(testCases = 20)
  void aQueueWithNoSchedulerIsReportedAsARepositoryFailure(TestCase tc) throws Exception {
    String queue = tc.draw(WORD, "queue");

    String originalMonitor = System.getProperty(MONITOR_PROPERTY);
    String originalBatchmgr = System.getProperty(BATCHMGR_PROPERTY);
    File dir = freshDir();
    try {
      File file = new File(dir, "queue-to-backend.xml");
      Files.write(file.toPath(),
          ("<queues><queue name=\"" + queue + "\"><monitor factory=\"M\"/></queue></queues>")
              .getBytes(StandardCharsets.UTF_8));

      assertThrows(RepositoryException.class,
          () -> new XmlBackendRepository(file.toURI().toString()).load());
    } finally {
      restore(MONITOR_PROPERTY, originalMonitor);
      restore(BATCHMGR_PROPERTY, originalBatchmgr);
      delete(dir);
    }
  }
}
