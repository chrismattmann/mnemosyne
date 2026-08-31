/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.oodt.cas.pge.condition;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;

import junit.framework.TestCase;

/**
 * What the gate decides, given a sequence of counts.
 */
public class TestProductCountSettledCondition extends TestCase {

  /**
   * A catalog holding a given number of products, the newest of them a given
   * age. Both are what the condition reads, and neither is remembered
   * between calls -- the condition is rebuilt for every evaluation, so a
   * stub that behaved like a sequence would be testing something the real
   * one cannot do.
   */
  /**
   * A catalog whose count climbs while the producer works and then holds.
   * Time only moves when the condition pauses, so the test does not wait.
   */
  private static class Catalog extends ProductCountSettledCondition {
    private final int[] counts;
    private int next = 0;
    int polls = 0;
    private long clock = 0;

    Catalog(int... counts) {
      this.counts = counts;
    }

    @Override
    protected int countProducts(String urlStr, String typeName) {
      int value = next < counts.length ? counts[next] : counts[counts.length - 1];
      if (next < counts.length) {
        next++;
      }
      return value;
    }

    @Override
    protected boolean pause(long seconds) {
      polls++;
      clock += seconds * 1000L;
      return true;
    }

    @Override
    protected long now() {
      return clock;
    }
  }

  private WorkflowConditionConfiguration config(String... pairs) {
    WorkflowConditionConfiguration c = new WorkflowConditionConfiguration();
    c.addConfigProperty(ProductCountSettledCondition.FILE_MANAGER_URL,
        "http://localhost:9000");
    c.addConfigProperty(ProductCountSettledCondition.PRODUCT_TYPE_NAME,
        "MapOutput");
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      c.addConfigProperty(pairs[i], pairs[i + 1]);
    }
    return c;
  }

  private boolean evaluate(ProductCountSettledCondition condition,
      WorkflowConditionConfiguration config) {
    return condition.evaluate(new Metadata(), config);
  }

  /** A count that is still climbing has not settled. */
  public void testAgrowingCountDoesNotPass() {
    Catalog climbing = new Catalog(10, 25, 40, 60, 85, 110);

    assertFalse("a climbing count means work is still arriving",
        evaluate(climbing, config(
            ProductCountSettledCondition.QUIET_SECONDS, "10",
            ProductCountSettledCondition.POLL_SECONDS, "5",
            ProductCountSettledCondition.MAX_WAIT_SECONDS, "25")));
  }

  /** Once it stops changing for long enough, the gate opens. */
  public void testAcountThatStopsChangingPasses() {
    Catalog settles = new Catalog(10, 25, 25, 25, 25, 25);

    assertTrue("the count held still, so the producer has finished",
        evaluate(settles, config(
            ProductCountSettledCondition.QUIET_SECONDS, "10",
            ProductCountSettledCondition.POLL_SECONDS, "5")));
  }

  /**
   * An empty catalog is perfectly still, and that is not the same as
   * finished. Without the minimum a reduce would run before the first
   * mapper had ingested anything.
   */
  public void testAnemptyCatalogNeverPasses() {
    Catalog empty = new Catalog(0, 0, 0, 0, 0, 0);

    assertFalse("nothing has been produced yet, however still",
        evaluate(empty, config(
            ProductCountSettledCondition.QUIET_SECONDS, "10",
            ProductCountSettledCondition.POLL_SECONDS, "5",
            ProductCountSettledCondition.MAX_WAIT_SECONDS, "30")));
  }

  /** Below the minimum, holding still is not enough either. */
  public void testBelowTheMinimumItHolds() {
    Catalog few = new Catalog(3, 3, 3, 3, 3, 3);

    assertFalse("3 is stable but below the minimum of 10",
        evaluate(few, config(
            ProductCountSettledCondition.MIN_COUNT, "10",
            ProductCountSettledCondition.QUIET_SECONDS, "10",
            ProductCountSettledCondition.POLL_SECONDS, "5",
            ProductCountSettledCondition.MAX_WAIT_SECONDS, "30")));
  }

  /** It waits, because the contract has no room for "not yet". */
  public void testItWaitsForTheProducer() {
    Catalog settles = new Catalog(10, 25, 40, 40, 40, 40);

    assertTrue(evaluate(settles, config(
        ProductCountSettledCondition.QUIET_SECONDS, "10",
        ProductCountSettledCondition.POLL_SECONDS, "5")));
    assertTrue("it should have looked more than once, not decided immediately",
        settles.polls > 1);
  }

  /** A count that cannot be read is not a finished producer. */
  public void testAcountThatCannotBeReadDoesNotPass() {
    Catalog unreadable = new Catalog(-1, -1, -1, -1);

    assertFalse("waiting on a catalog we cannot read",
        evaluate(unreadable, config(
            ProductCountSettledCondition.QUIET_SECONDS, "10",
            ProductCountSettledCondition.POLL_SECONDS, "5",
            ProductCountSettledCondition.MAX_WAIT_SECONDS, "20")));
  }

  /** Without being told what to count, it cannot say yes. */
  public void testItWillNotPassWithoutConfiguration() {
    assertFalse(new Catalog(500, 500, 500).evaluate(new Metadata(),
        new WorkflowConditionConfiguration()));
  }

  /**
   * Nothing is remembered between calls: a new condition object is built for
   * every evaluation, so a gate needing to see a trend across them never
   * opens. The trend is observed inside one call instead.
   */
  public void testAfreshObjectReachesTheSameAnswer() {
    WorkflowConditionConfiguration c = config(
        ProductCountSettledCondition.QUIET_SECONDS, "10",
        ProductCountSettledCondition.POLL_SECONDS, "5");

    assertTrue(evaluate(new Catalog(40, 40, 40, 40), c));
    assertTrue("a second, unrelated object must decide identically",
        evaluate(new Catalog(40, 40, 40, 40), c));
  }
}
