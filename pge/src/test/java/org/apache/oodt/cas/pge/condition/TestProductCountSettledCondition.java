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
  private static class Catalog extends ProductCountSettledCondition {
    private final int count;
    private long quietFor;
    /* How many polls before the producer goes quiet. */
    private int quietAfterPolls = 0;
    int polls = 0;
    private long clock = 0;

    Catalog(int count, long quietFor) {
      this.count = count;
      this.quietFor = quietFor;
    }

    Catalog goesQuietAfter(int polls, long quietFor) {
      this.quietAfterPolls = polls;
      this.quietFor = quietFor;
      return this;
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

    @Override
    protected int countProducts(String urlStr, String typeName) {
      return count;
    }

    @Override
    protected long secondsSinceNewest(String urlStr, String typeName) {
      // Before the producer settles, something landed a moment ago.
      return polls >= quietAfterPolls ? quietFor : 0;
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

  /** Still producing: the newest audit landed a moment ago. */
  public void testAproducerStillIngestingDoesNotPass() {
    assertFalse("something was ingested 2s ago, the producer is still going",
        evaluate(new Catalog(120, 2), config()));
  }

  /** Nothing has landed for long enough: the producer has finished. */
  public void testAquietCatalogPasses() {
    assertTrue("nothing new for 90s, well past the default 30",
        evaluate(new Catalog(120, 90), config()));
  }

  /**
   * An empty catalog is perfectly quiet, and that is not the same as
   * finished. Without the minimum, a reduce would run before the first
   * mapper had ingested anything.
   */
  public void testAnemptyCatalogNeverPasses() {
    assertFalse("nothing has been produced yet, however quiet",
        evaluate(new Catalog(0, 3600), config()));
  }

  /** Below the minimum, quiet is not enough either. */
  public void testBelowTheMinimumItHolds() {
    assertFalse("3 products is below the minimum of 10",
        evaluate(new Catalog(3, 3600),
            config(ProductCountSettledCondition.MIN_COUNT, "10")));
  }

  /** How long counts as quiet is the pipeline's to choose. */
  public void testThequietPeriodIsConfigurable() {
    WorkflowConditionConfiguration patient =
        config(ProductCountSettledCondition.QUIET_SECONDS, "300");

    assertFalse("60s is quiet by default but not when 300 was asked for",
        evaluate(new Catalog(120, 60), patient));
    assertTrue(evaluate(new Catalog(120, 301), patient));
  }

  /** A catalog that cannot be read is not a finished one. */
  public void testAcatalogThatCannotBeReadDoesNotPass() {
    assertFalse("count unreadable", evaluate(new Catalog(-1, 3600), config()));
    assertFalse("age unreadable", evaluate(new Catalog(120, -1), config()));
  }

  /**
   * The gate waits rather than reporting "not yet".
   *
   * <p>
   * A condition gets one evaluation: returning false fails the attempt and
   * leaves the instance in a state TaskProcessor will not offer again. So a
   * gate that needs to wait has to do the waiting itself.
   * </p>
   */
  public void testItWaitsForTheProducerRatherThanFailing() {
    Catalog catalog = new Catalog(120, 90).goesQuietAfter(3, 90);

    assertTrue("it should wait for the producer, not give up on it",
        evaluate(catalog, config()));
    assertEquals("and it should have looked more than once", 3, catalog.polls);
  }

  /** It does not wait for ever: a producer that never stops has not finished. */
  public void testItGivesUpAfterTheLimit() {
    Catalog neverQuiet = new Catalog(120, 0);

    assertFalse("nothing ever went quiet, so the producer has not finished",
        evaluate(neverQuiet,
            config(ProductCountSettledCondition.MAX_WAIT_SECONDS, "30",
                   ProductCountSettledCondition.POLL_SECONDS, "10")));
  }

  /** Without being told what to count, it cannot say yes. */
  public void testItWillNotPassWithoutConfiguration() {
    assertFalse(new Catalog(500, 3600).evaluate(new Metadata(),
        new WorkflowConditionConfiguration()));
  }

  /**
   * The decision must not depend on anything held between calls: a new
   * condition object is built for every evaluation, so a gate that needed to
   * see a trend would never open.
   */
  public void testAfreshObjectReachesTheSameAnswer() {
    WorkflowConditionConfiguration c = config();

    assertTrue(evaluate(new Catalog(120, 90), c));
    assertTrue("a second, unrelated object must decide identically",
        evaluate(new Catalog(120, 90), c));
  }
}
