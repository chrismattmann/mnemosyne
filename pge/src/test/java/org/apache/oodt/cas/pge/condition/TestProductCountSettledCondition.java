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

  /** Returns the counts it was given, one per evaluation. */
  private static class Counts extends ProductCountSettledCondition {
    private final int[] counts;
    private int next = 0;

    Counts(int... counts) {
      this.counts = counts;
    }

    @Override
    protected int countProducts(String urlStr, String typeName) {
      return next < counts.length ? counts[next++] : counts[counts.length - 1];
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

  private boolean[] run(Counts condition, WorkflowConditionConfiguration config,
      int evaluations) {
    boolean[] results = new boolean[evaluations];
    for (int i = 0; i < evaluations; i++) {
      results[i] = condition.evaluate(new Metadata(), config);
    }
    return results;
  }

  /** While the mappers are producing, the count climbs and the gate holds. */
  public void testAgrowingCountDoesNotPass() {
    boolean[] r = run(new Counts(10, 25, 40, 60), config(), 4);

    for (int i = 0; i < r.length; i++) {
      assertFalse("a climbing count means work is still arriving (check "
          + (i + 1) + ")", r[i]);
    }
  }

  /**
   * Once it stops climbing for the required checks, the gate opens.
   *
   * <p>
   * What is counted is unchanged <em>transitions</em>, not sightings: seeing
   * 25 for the first time says nothing, seeing it again is one unchanged
   * observation, and a second is what the default asks for.
   * </p>
   */
  public void testAsettledCountPasses() {
    boolean[] r = run(new Counts(10, 25, 25, 25, 25), config(), 5);

    assertFalse("still climbing", r[0]);
    assertFalse("25 seen for the first time is not evidence of anything",
        r[1]);
    assertFalse("one unchanged observation is not a trend", r[2]);
    assertTrue("two unchanged observations is the default for settled", r[3]);
  }

  /**
   * An empty catalog is not a finished one. Zero is also "not growing", which
   * is exactly how a countdown gate lets a reduce run against nothing.
   */
  public void testAnemptyCatalogNeverPasses() {
    boolean[] r = run(new Counts(0, 0, 0, 0, 0), config(), 5);

    for (int i = 0; i < r.length; i++) {
      assertFalse("nothing has been produced yet (check " + (i + 1) + ")",
          r[i]);
    }
  }

  /** Below the minimum, holding still is not enough. */
  public void testBelowTheMinimumItHolds() {
    boolean[] r = run(new Counts(3, 3, 3, 3),
        config(ProductCountSettledCondition.MIN_COUNT, "10"), 4);

    for (int i = 0; i < r.length; i++) {
      assertFalse("3 is stable but below the minimum of 10", r[i]);
    }
  }

  /** Growth after a pause resets the count: it was not finished after all. */
  public void testGrowthAfterApauseResetsTheGate() {
    Counts condition = new Counts(20, 20, 35, 35, 35);
    boolean[] r = run(condition, config(), 5);

    assertFalse(r[0]);
    assertFalse("one unchanged observation", r[1]);
    assertFalse("it grew again, so it was not settled", r[2]);
    assertFalse("counting from the new value", r[3]);
    assertTrue("settled at the higher count", r[4]);
  }

  /** How long to wait for is the caller's to decide. */
  public void testTherequiredNumberOfStableChecksIsConfigurable() {
    boolean[] r = run(new Counts(5, 5, 5, 5, 5),
        config(ProductCountSettledCondition.STABLE_EVALUATIONS, "4"), 5);

    assertFalse(r[2]);
    assertFalse("three unchanged, four were asked for", r[3]);
    assertTrue(r[4]);
  }

  /** A catalog that cannot be counted is not a finished one either. */
  public void testAcountThatCannotBeReadDoesNotPass() {
    boolean[] r = run(new Counts(-1, -1, -1), config(), 3);

    for (int i = 0; i < r.length; i++) {
      assertFalse("waiting on a count we cannot read", r[i]);
    }
  }

  /** Without being told what to count, it cannot say yes. */
  public void testItWillNotPassWithoutConfiguration() {
    WorkflowConditionConfiguration empty =
        new WorkflowConditionConfiguration();

    assertFalse(new Counts(100, 100, 100).evaluate(new Metadata(), empty));
  }
}
