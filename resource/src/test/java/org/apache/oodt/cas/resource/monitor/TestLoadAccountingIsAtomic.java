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

import org.apache.oodt.cas.resource.structs.ResourceNode;

import junit.framework.TestCase;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Capacity that is given back has to arrive.
 *
 * <p>
 * A concurrent map makes each get and each put safe on its own and says
 * nothing about the sequence between them. Read the load, subtract, write it
 * back, and a decrement that lands in the middle of another is simply lost.
 * Nobody notices, because a load that is too high looks like a busy node.
 * </p>
 *
 * <p>
 * Measured on a two node run: the conditions node sat at a load of five for
 * four and a half hours with nothing running on it and no condition evaluated
 * since the first three minutes, when four hundred of them went through that
 * one node id. The translate nodes were fine -- sixteen jobs over five hours
 * are never two at once. The pool holds twenty, so a few runs of that leak
 * fill it, and then every task gated by a condition waits forever.
 * </p>
 */
public class TestLoadAccountingIsAtomic extends TestCase {

  private static final int CAPACITY = 500;
  private static final int WORKERS = 16;
  private static final int EACH = 200;

  private ResourceNode node;
  private AssignmentMonitor monitor;

  @Override
  protected void setUp() throws Exception {
    node = new ResourceNode("conditions", new URL("http://localhost:2001"),
        CAPACITY);
    List<ResourceNode> nodes = new ArrayList<ResourceNode>();
    nodes.add(node);
    monitor = new AssignmentMonitor(nodes);
  }

  private int loadOf(ResourceNode n) throws Exception {
    // getLoad reports what is left, not what is used.
    return n.getCapacity() - monitor.getLoad(n);
  }

  /**
   * The leak, at the scale it happened: many short jobs on one node id,
   * each taking a slot and giving it back.
   */
  public void testEveryReleaseArrives() throws Exception {
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(WORKERS);
    final List<Throwable> failures = new ArrayList<Throwable>();

    for (int i = 0; i < WORKERS; i++) {
      new Thread(new Runnable() {
        public void run() {
          try {
            start.await();
            for (int n = 0; n < EACH; n++) {
              if (monitor.assignLoad(node, 1)) {
                monitor.reduceLoad(node, 1);
              }
            }
          } catch (Throwable t) {
            synchronized (failures) {
              failures.add(t);
            }
          } finally {
            done.countDown();
          }
        }
      }).start();
    }

    start.countDown();
    done.await();
    assertTrue("workers threw: " + failures, failures.isEmpty());

    assertEquals("every job took a slot and gave it back, so the node must "
        + "be idle. A leftover load is a slot no job can ever use again, "
        + "and it is never recovered short of a restart.",
        0, loadOf(node));
  }

  /** And capacity is never handed out beyond what the node has. */
  public void testCapacityIsNotOversubscribed() throws Exception {
    final int cap = 8;
    ResourceNode small = new ResourceNode("small",
        new URL("http://localhost:2002"), cap);
    List<ResourceNode> nodes = new ArrayList<ResourceNode>();
    nodes.add(small);
    final AssignmentMonitor tight = new AssignmentMonitor(nodes);

    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(WORKERS);
    final java.util.concurrent.atomic.AtomicInteger granted =
        new java.util.concurrent.atomic.AtomicInteger();

    for (int i = 0; i < WORKERS; i++) {
      final ResourceNode target = small;
      new Thread(new Runnable() {
        public void run() {
          try {
            start.await();
            for (int n = 0; n < EACH; n++) {
              if (tight.assignLoad(target, 1)) {
                granted.incrementAndGet();
              }
            }
          } catch (Exception ignored) {
          } finally {
            done.countDown();
          }
        }
      }).start();
    }

    start.countDown();
    done.await();

    assertEquals("a node of capacity " + cap + " must not hand out more, or "
        + "the scheduler places work on top of work that is already running",
        cap, granted.get());
  }

  // ------------------------------------------------------- plain cases ---

  public void testAssignAndReduceStillWorkOnTheirOwn() throws Exception {
    assertTrue(monitor.assignLoad(node, 3));
    assertEquals(3, loadOf(node));
    monitor.reduceLoad(node, 3);
    assertEquals(0, loadOf(node));
  }

  public void testAFullNodeRefuses() throws Exception {
    assertTrue(monitor.assignLoad(node, CAPACITY));
    assertFalse("no room left", monitor.assignLoad(node, 1));
    monitor.reduceLoad(node, 1);
    assertTrue("and room reappears when a job ends",
        monitor.assignLoad(node, 1));
  }

  public void testReducingBelowZeroIsHeldAtZero() throws Exception {
    monitor.reduceLoad(node, 5);
    assertEquals(0, loadOf(node));
  }

  /**
   * A node id the map has not seen carries no load rather than throwing.
   * Unboxing a null threw from inside the scheduler's dispatch loop.
   */
  public void testAnUnknownNodeIsNotAnException() throws Exception {
    ResourceNode stranger = new ResourceNode("stranger",
        new URL("http://localhost:2003"), 4);
    assertTrue(monitor.assignLoad(stranger, 1));
    monitor.reduceLoad(stranger, 1);
  }
}
