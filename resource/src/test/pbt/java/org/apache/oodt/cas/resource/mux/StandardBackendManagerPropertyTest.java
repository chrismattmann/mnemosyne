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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.oodt.cas.resource.batchmgr.Batchmgr;
import org.apache.oodt.cas.resource.monitor.Monitor;
import org.apache.oodt.cas.resource.mux.mocks.MockBatchManager;
import org.apache.oodt.cas.resource.mux.mocks.MockMonitor;
import org.apache.oodt.cas.resource.schedule.MockScheduler;
import org.apache.oodt.cas.resource.scheduler.Scheduler;
import org.apache.oodt.cas.resource.structs.exceptions.QueueManagerException;

/**
 * Routing properties of {@link StandardBackendManager}.
 *
 * <p>The class had no unit tests of its own. It decides which monitor, batch
 * manager and scheduler a job's queue is served by, so the property that
 * matters is that a queue is always routed to the backend that was registered
 * for it — never to another queue's backend, and never to a backend that was
 * never registered.
 */
class StandardBackendManagerPropertyTest {

  private static Generator<String> queueName() {
    return text().minSize(1).maxSize(6).categories("Lu", "Ll", "Nd");
  }

  /** One distinct backend per queue, so a mis-route is visible by identity. */
  private static Map<String, Object[]> drawBackends(TestCase tc, StandardBackendManager manager) {
    int count = tc.draw(integers().min(1).max(5), "queueCount");
    Map<String, Object[]> registered = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      String queue = tc.draw(queueName(), "queue[" + i + "]");
      Monitor monitor = new MockMonitor(i, null, null, null, null);
      Batchmgr batchmgr = new MockBatchManager();
      Scheduler scheduler = new MockScheduler();
      registered.put(queue, new Object[] {monitor, batchmgr, scheduler});
      manager.addSet(queue, monitor, batchmgr, scheduler);
    }
    return registered;
  }

  /** Each queue is served by the backend that was registered for it. */
  @HegelTest
  void aQueueIsServedByItsOwnBackend(TestCase tc) throws Exception {
    StandardBackendManager manager = new StandardBackendManager();
    Map<String, Object[]> registered = drawBackends(tc, manager);

    for (Map.Entry<String, Object[]> e : registered.entrySet()) {
      assertSame(e.getValue()[0], manager.getMonitor(e.getKey()), "monitor for " + e.getKey());
      assertSame(e.getValue()[1], manager.getBatchmgr(e.getKey()), "batchmgr for " + e.getKey());
      assertSame(e.getValue()[2], manager.getScheduler(e.getKey()), "scheduler for " + e.getKey());
    }
  }

  /**
   * A queue nobody registered is reported, rather than silently answered with
   * a null backend that the caller would then dereference.
   */
  @HegelTest
  void anUnregisteredQueueIsReported(TestCase tc) {
    StandardBackendManager manager = new StandardBackendManager();
    Map<String, Object[]> registered = drawBackends(tc, manager);
    String unknown = tc.draw(queueName(), "unknown");
    tc.assume(!registered.containsKey(unknown));

    assertThrows(QueueManagerException.class, () -> manager.getMonitor(unknown));
    assertThrows(QueueManagerException.class, () -> manager.getBatchmgr(unknown));
    assertThrows(QueueManagerException.class, () -> manager.getScheduler(unknown));
  }

  /**
   * Registering a queue again replaces its backend outright: reconfiguring a
   * queue must not leave jobs being routed to the old backend.
   */
  @HegelTest
  void registeringAQueueAgainReplacesItsBackend(TestCase tc) throws Exception {
    StandardBackendManager manager = new StandardBackendManager();
    Map<String, Object[]> registered = drawBackends(tc, manager);
    String queue = tc.draw(sampledFrom(new ArrayList<>(registered.keySet())), "queue");

    Monitor monitor = new MockMonitor(99, null, null, null, null);
    Batchmgr batchmgr = new MockBatchManager();
    Scheduler scheduler = new MockScheduler();
    manager.addSet(queue, monitor, batchmgr, scheduler);

    assertSame(monitor, manager.getMonitor(queue));
    assertSame(batchmgr, manager.getBatchmgr(queue));
    assertSame(scheduler, manager.getScheduler(queue));
    assertTrue(
        manager.getMonitors().size() <= registered.size(),
        "replacing a backend added a queue instead");
  }

  /**
   * The monitor list is exactly the monitors currently registered, one per
   * queue. A monitor missing from it is a set of nodes no one polls.
   */
  @HegelTest
  void theMonitorListIsTheRegisteredMonitors(TestCase tc) {
    StandardBackendManager manager = new StandardBackendManager();
    Map<String, Object[]> registered = drawBackends(tc, manager);

    List<Monitor> monitors = manager.getMonitors();

    assertEquals(registered.size(), monitors.size(), "monitor count differs from queue count");
    Set<Monitor> expected = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (Object[] backend : registered.values()) {
      expected.add((Monitor) backend[0]);
    }
    Set<Monitor> reported = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    reported.addAll(monitors);
    assertEquals(expected, reported);
  }
}
