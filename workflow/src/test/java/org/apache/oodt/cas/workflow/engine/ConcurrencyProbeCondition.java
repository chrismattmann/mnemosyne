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

package org.apache.oodt.cas.workflow.engine;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionInstance;

import org.junit.Ignore;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records how many evaluations were in flight at once, which is the only thing
 * that actually distinguishes a parallel conditions block from a sequential
 * one.
 *
 * <p>
 * Each evaluation counts down a latch and then waits on it. If the block really
 * is evaluated in parallel the last arrival releases everyone and they all
 * finish together; if it is serialised each one waits alone until its own
 * timeout and the count never exceeds one. Either way the workflow finishes, so
 * a regression shows up as a wrong number rather than as a hung build.
 * </p>
 */
@Ignore
public class ConcurrencyProbeCondition implements WorkflowConditionInstance {

  private static final long WAIT_MILLIS = 2000;

  private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

  private static final AtomicInteger MAX_IN_FLIGHT = new AtomicInteger();

  private static volatile CountDownLatch latch = new CountDownLatch(1);

  public static void reset(int expected) {
    IN_FLIGHT.set(0);
    MAX_IN_FLIGHT.set(0);
    latch = new CountDownLatch(expected);
  }

  /** The most evaluations observed running at the same time. */
  public static int maxInFlight() {
    return MAX_IN_FLIGHT.get();
  }

  @Override
  public boolean evaluate(Metadata metadata,
      WorkflowConditionConfiguration config) {
    int inFlight = IN_FLIGHT.incrementAndGet();
    int seen;
    do {
      seen = MAX_IN_FLIGHT.get();
    } while (inFlight > seen && !MAX_IN_FLIGHT.compareAndSet(seen, inFlight));

    try {
      latch.countDown();
      latch.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      IN_FLIGHT.decrementAndGet();
    }

    return true;
  }
}
