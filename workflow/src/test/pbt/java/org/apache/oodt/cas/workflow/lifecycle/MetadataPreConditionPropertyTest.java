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

package org.apache.oodt.cas.workflow.lifecycle;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

/**
 * Properties of {@link MetadataPreCondition}, the guard that lets a lifecycle
 * branch on what a task already put in the shared context.
 *
 * <p>It is the one precondition OODT ships, so a lifecycle file that describes
 * a branch without anyone writing Java describes it with this. It is asked on
 * every state change, of a context other tasks are still writing to, which is
 * why what it does with a missing key or a multi-valued one is worth stating.
 */
class MetadataPreConditionPropertyTest {

  /** A small alphabet of metadata keys and values, so that they collide. */
  private static final List<String> WORDS = List.of("alpha", "beta", "gamma");

  private static WorkflowConditionConfiguration config(String key, String value,
      Boolean negate) {
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    if (key != null) {
      config.addConfigProperty(MetadataPreCondition.KEY, key);
    }
    if (value != null) {
      config.addConfigProperty(MetadataPreCondition.VALUE, value);
    }
    if (negate != null) {
      config.addConfigProperty(MetadataPreCondition.NEGATE,
          String.valueOf(negate));
    }
    return config;
  }

  /** An instance whose shared context holds the drawn keys and values. */
  private static WorkflowInstance instanceHolding(TestCase tc, String label) {
    Metadata context = new Metadata();
    int keys = tc.draw(integers().min(0).max(3), label + "KeyCount");
    for (int i = 0; i < keys; i++) {
      String key = tc.draw(sampledFrom(WORDS), label + "Key" + i);
      int values = tc.draw(integers().min(1).max(2), label + "ValueCount" + i);
      List<String> drawn = new ArrayList<>(values);
      for (int v = 0; v < values; v++) {
        drawn.add(tc.draw(sampledFrom(WORDS), label + "Value" + i + "x" + v));
      }
      context.addMetadata(key, drawn);
    }
    WorkflowInstance instance = new WorkflowInstance();
    instance.setSharedContext(context);
    return instance;
  }

  /**
   * A guard that names no key is never met. It does not know what it is
   * guarding, and a guard that cannot decide has to keep the workflow where it
   * is rather than wave it through.
   */
  @HegelTest
  void aGuardThatNamesNoKeyIsNeverMet(TestCase tc) {
    WorkflowInstance instance = instanceHolding(tc, "ctx");
    String value = tc.draw(sampledFrom(WORDS), "value");
    boolean blank = tc.draw(booleans(), "blank");
    WorkflowConditionConfiguration config =
        config(blank ? "   " : null, value, null);

    boolean met = new MetadataPreCondition().isMet(new WorkflowState(),
        instance, config);

    assertFalse(met, "a guard naming no key was met");
  }

  /**
   * A guard that names only a key is met exactly when the context carries that
   * key. This is how a lifecycle waits for a task to have produced something,
   * whatever it produced.
   */
  @HegelTest
  void aGuardOnAKeyAloneAsksWhetherTheKeyIsThere(TestCase tc) {
    WorkflowInstance instance = instanceHolding(tc, "ctx");
    String key = tc.draw(sampledFrom(WORDS), "key");

    boolean met = new MetadataPreCondition().isMet(new WorkflowState(),
        instance, config(key, null, null));

    assertEquals(instance.getSharedContext().containsKey(key), met,
        "a guard on " + key + " answered " + met + " for context "
            + instance.getSharedContext().getAllKeys());
  }

  /**
   * A guard that names a value is met when any of the key's values is that
   * one. A metadata key holding several values is the ordinary case in a
   * workflow context, and a branch on one of them should not depend on which
   * value happens to be first.
   */
  @HegelTest
  void aGuardOnAValueIsMetByAnyOfTheValuesUnderTheKey(TestCase tc) {
    WorkflowInstance instance = instanceHolding(tc, "ctx");
    String key = tc.draw(sampledFrom(WORDS), "key");
    String value = tc.draw(sampledFrom(WORDS), "value");

    boolean met = new MetadataPreCondition().isMet(new WorkflowState(),
        instance, config(key, value, null));

    List<String> values = instance.getSharedContext().getAllMetadata(key);
    assertEquals(values != null && values.contains(value), met,
        "a guard on " + key + "=" + value + " answered " + met + " for "
            + values);
  }

  /**
   * Negating a guard gives the opposite answer to the same guard unnegated.
   * That is what negation is for: a lifecycle declares one branch and its
   * complement, and exactly one of them has to be taken.
   */
  @HegelTest
  void negatingAGuardInvertsIt(TestCase tc) {
    WorkflowInstance instance = instanceHolding(tc, "ctx");
    String key = tc.draw(sampledFrom(WORDS), "key");
    boolean onValue = tc.draw(booleans(), "onValue");
    String value = onValue ? tc.draw(sampledFrom(WORDS), "value") : null;
    MetadataPreCondition guard = new MetadataPreCondition();

    boolean plain = guard.isMet(new WorkflowState(), instance,
        config(key, value, false));
    boolean negated = guard.isMet(new WorkflowState(), instance,
        config(key, value, true));

    assertEquals(plain, !negated,
        "the guard and its negation both answered " + plain);
  }

  /**
   * An instance with no shared context at all does not satisfy a guard about
   * its metadata. An instance is created before anything has been put in its
   * context, and the transitioner may be asked about it at any point.
   */
  @HegelTest
  void anInstanceWithNoContextDoesNotSatisfyAGuard(TestCase tc) {
    String key = tc.draw(sampledFrom(WORDS), "key");
    boolean onValue = tc.draw(booleans(), "onValue");
    String value = onValue ? tc.draw(sampledFrom(WORDS), "value") : null;
    WorkflowInstance instance = new WorkflowInstance();
    instance.setSharedContext(null);

    boolean met = new MetadataPreCondition().isMet(new WorkflowState(),
        instance, config(key, value, null));

    assertFalse(met, "a guard was met by an instance holding no metadata");
  }

  /**
   * The guard is stateless: asking it twice about the same instance gives the
   * same answer, and two guards agree. One instance of it is shared by every
   * workflow using the lifecycle and polled on every state change.
   */
  @HegelTest
  void theGuardIsStatelessAndRepeatable(TestCase tc) {
    WorkflowInstance instance = instanceHolding(tc, "ctx");
    String key = tc.draw(sampledFrom(WORDS), "key");
    String value = tc.draw(sampledFrom(WORDS), "value");
    WorkflowConditionConfiguration config = config(key, value, null);
    MetadataPreCondition guard = new MetadataPreCondition();

    boolean first = guard.isMet(new WorkflowState(), instance, config);
    boolean second = guard.isMet(new WorkflowState(), instance, config);
    boolean other = new MetadataPreCondition().isMet(new WorkflowState(),
        instance, config);

    assertEquals(first, second, "the guard changed its mind");
    assertEquals(first, other, "two guards disagreed about the same instance");
  }
}
