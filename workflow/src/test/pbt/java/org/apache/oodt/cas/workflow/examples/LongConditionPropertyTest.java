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

package org.apache.oodt.cas.workflow.examples;

import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;

/**
 * Properties of {@link LongCondition}, the condition the module's own tests use
 * to stand in for one that takes a while to come true.
 *
 * <p>Unlike the other example conditions it remembers how often it has been
 * asked, which is what makes it useful: a workflow guarded by it waits for a
 * stated number of polls and then proceeds. How many times it says no, and that
 * it stays saying yes afterwards, is the whole of what a test using it relies
 * on.
 */
class LongConditionPropertyTest {

  /** What the condition falls back to when the metadata does not say. */
  private static final int DEFAULT_REFUSALS = 5;

  private static final WorkflowConditionConfiguration NO_CONFIG =
      new WorkflowConditionConfiguration();

  private static Metadata refusing(Integer times) {
    Metadata metadata = new Metadata();
    if (times != null) {
      metadata.addMetadata("numFalse", String.valueOf(times));
    }
    return metadata;
  }

  /**
   * The condition refuses exactly as many times as it was asked to, and then
   * holds. A workflow polling it counts on both halves: on the refusals, to
   * exercise waiting, and on the answer staying yes, so that the workflow
   * finishes rather than oscillating.
   */
  @HegelTest
  void itRefusesTheStatedNumberOfTimesAndThenHolds(TestCase tc) {
    int refusals = tc.draw(integers().min(0).max(4), "refusals");
    int extraPolls = tc.draw(integers().min(1).max(3), "extraPolls");
    Metadata metadata = refusing(refusals);
    LongCondition condition = new LongCondition();

    for (int i = 0; i < refusals; i++) {
      assertFalse(condition.evaluate(metadata, NO_CONFIG),
          "poll " + i + " of " + refusals + " was already met");
    }
    for (int i = 0; i < extraPolls; i++) {
      assertTrue(condition.evaluate(metadata, NO_CONFIG),
          "poll " + (refusals + i) + " was not met after " + refusals
              + " refusals");
    }
  }

  /**
   * Each condition object counts on its own. The engine builds one per
   * condition declaration and polls it; two workflows sharing a class must not
   * share a countdown.
   */
  @HegelTest
  void twoConditionsCountSeparately(TestCase tc) {
    int refusals = tc.draw(integers().min(1).max(4), "refusals");
    Metadata metadata = refusing(refusals);
    LongCondition exhausted = new LongCondition();
    for (int i = 0; i < refusals; i++) {
      exhausted.evaluate(metadata, NO_CONFIG);
    }

    LongCondition fresh = new LongCondition();

    assertTrue(exhausted.evaluate(metadata, NO_CONFIG),
        "the exhausted condition is still refusing");
    assertFalse(fresh.evaluate(metadata, NO_CONFIG),
        "a fresh condition inherited another one's countdown");
  }

  /**
   * A metadata context that does not say how long to wait gets the documented
   * default. The condition is written to be usable with no configuration at
   * all.
   */
  @HegelTest
  void anUnstatedWaitFallsBackToTheDefault(TestCase tc) {
    int polls = tc.draw(integers().min(0).max(DEFAULT_REFUSALS + 2), "polls");
    Metadata metadata = refusing(null);
    LongCondition condition = new LongCondition();

    boolean met = false;
    for (int i = 0; i < polls; i++) {
      met = condition.evaluate(metadata, NO_CONFIG);
    }

    assertEquals(polls > DEFAULT_REFUSALS, met,
        "after " + polls + " polls the condition answered " + met);
  }
}
