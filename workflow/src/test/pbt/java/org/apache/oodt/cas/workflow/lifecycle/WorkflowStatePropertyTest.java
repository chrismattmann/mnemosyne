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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState.AttachedPreCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

/**
 * Properties of {@link WorkflowState}, the state an instance is in.
 *
 * <p>A state is identified by its name and by nothing else: the lifecycle
 * hands out copies carrying whatever message the engine set at the time, and
 * the engine compares those copies against declarations read from a file. That
 * makes the equals and hashCode agreement load-bearing rather than decorative.
 *
 * <p>The transitions and guards a state declares are read by
 * {@link WorkflowStateTransitioner} on every state change, so what the state
 * accepts into those lists is stated here too.
 */
class WorkflowStatePropertyTest {

  /** A small alphabet of state names, so that names collide. */
  private static final List<String> NAMES =
      List.of("Null", "Queued", "Executing", "Finished");

  private static WorkflowState named(String name) {
    WorkflowState state = new WorkflowState();
    state.setName(name);
    return state;
  }

  /** A precondition that answers as it was told to. */
  private static final class StubPreCondition implements StatePreCondition {

    private final boolean answer;

    private StubPreCondition(boolean answer) {
      this.answer = answer;
    }

    public boolean isMet(WorkflowState candidateState, WorkflowInstance instance,
        WorkflowConditionConfiguration config) {
      return this.answer;
    }
  }

  /**
   * Two states are equal exactly when they carry the same name, whatever else
   * has been set on them, and equal states hash alike. The engine sets a
   * message and a start time on the copy it is handed; a state used as a map
   * key that hashed on those would be lost the moment it started running.
   */
  @HegelTest
  void statesAreEqualByNameAndHashByNameToo(TestCase tc) {
    String leftName = tc.draw(sampledFrom(NAMES), "leftName");
    String rightName = tc.draw(sampledFrom(NAMES), "rightName");
    WorkflowState left = named(leftName);
    WorkflowState right = named(rightName);
    right.setMessage(tc.draw(sampledFrom(NAMES), "rightMessage"));
    right.setCategory(new WorkflowLifecycleStage("aStage",
        new ArrayList<WorkflowState>(), 0));

    assertEquals(leftName.equals(rightName), left.equals(right),
        left + " and " + right + " compare wrongly");
    if (left.equals(right)) {
      assertEquals(left.hashCode(), right.hashCode(),
          "equal states hash differently");
    }
    assertTrue(left.equals(left), "a state did not equal itself");
    assertFalse(left.equals(leftName),
        "a state equalled something that is not a state");
  }

  /**
   * A state with no name is equal only to another with no name. Every reader
   * builds a state and then names it, so the unnamed state exists in between.
   */
  @HegelTest
  void anUnnamedStateEqualsOnlyAnotherUnnamedOne(TestCase tc) {
    String name = tc.draw(sampledFrom(NAMES), "name");
    WorkflowState unnamed = new WorkflowState();

    assertTrue(unnamed.equals(new WorkflowState()),
        "two unnamed states are not equal");
    assertFalse(unnamed.equals(named(name)),
        "an unnamed state equals one named " + name);
    assertFalse(named(name).equals(unnamed),
        "a state named " + name + " equals an unnamed one");
    assertEquals(0, unnamed.hashCode(),
        "an unnamed state hashes to something other than zero");
  }

  /**
   * The states reachable from a state are the ones that were declared, in the
   * order they were declared, with nothing repeated and nothing empty. The
   * transitioner walks this list in order and takes the first eligible winner,
   * so a duplicate would weight a transition and a blank would be looked up as
   * a state that cannot exist.
   */
  @HegelTest
  void declaredTransitionsAreKeptInOrderWithoutRepeats(TestCase tc) {
    int count = tc.draw(integers().min(0).max(6), "count");
    WorkflowState state = new WorkflowState();
    Set<String> expected = new LinkedHashSet<>();

    for (int i = 0; i < count; i++) {
      boolean degenerate = tc.draw(booleans(), "degenerate" + i);
      String name = degenerate ? "" : tc.draw(sampledFrom(NAMES), "name" + i);
      state.addNextStateName(name);
      if (!name.isEmpty()) {
        expected.add(name);
      }
    }
    state.addNextStateName(null);

    assertEquals(new ArrayList<>(expected), state.getNextStateNames(),
        "the declared transitions are not the ones that were added");
  }

  /**
   * A state told it has no transitions or no guards reports empty lists rather
   * than null ones. The transitioner asks every state it meets for both.
   */
  @HegelTest
  void aStateNeverReportsNullTransitionsOrGuards(TestCase tc) {
    String name = tc.draw(sampledFrom(NAMES), "name");
    WorkflowState state = named(name);
    state.addNextStateName(name);
    state.addPreCondition(new AttachedPreCondition("aClass",
        new StubPreCondition(true), null));

    state.setNextStateNames(null);
    state.setPreConditions(null);

    assertNotNull(state.getNextStateNames(), "the transitions went null");
    assertNotNull(state.getPreConditions(), "the guards went null");
    assertTrue(state.getNextStateNames().isEmpty(),
        "clearing the transitions left some behind");
    assertTrue(state.getPreConditions().isEmpty(),
        "clearing the guards left some behind");
  }

  /**
   * A state may be entered when every guard on it is met, and a state with no
   * guards may always be entered. All of them have to pass, which is how
   * preconditions read everywhere else in OODT.
   */
  @HegelTest
  void everyGuardHasToPassForAStateToBeEnterable(TestCase tc) {
    int count = tc.draw(integers().min(0).max(4), "count");
    WorkflowState state = named(tc.draw(sampledFrom(NAMES), "name"));
    boolean allMet = true;
    for (int i = 0; i < count; i++) {
      boolean answer = tc.draw(booleans(), "answer" + i);
      allMet = allMet && answer;
      state.addPreCondition(new AttachedPreCondition("guard" + i,
          new StubPreCondition(answer), new WorkflowConditionConfiguration()));
    }

    boolean enterable = state.preConditionsMet(new WorkflowInstance());

    assertEquals(allMet, enterable,
        count + " guards answered " + allMet + " but the state said "
            + enterable);
  }

  /**
   * A guard that could not be built is not satisfied. The lifecycle reader
   * leaves a null precondition behind when the class it was told about cannot
   * be loaded, and a guard that cannot run must not be read as one that passed.
   */
  @HegelTest
  void aGuardThatCouldNotBeBuiltIsNotSatisfied(TestCase tc) {
    int metCount = tc.draw(integers().min(0).max(3), "metCount");
    WorkflowState state = named(tc.draw(sampledFrom(NAMES), "name"));
    for (int i = 0; i < metCount; i++) {
      state.addPreCondition(new AttachedPreCondition("guard" + i,
          new StubPreCondition(true), null));
    }
    state.addPreCondition(new AttachedPreCondition("missing", null, null));

    assertFalse(state.preConditionsMet(new WorkflowInstance()),
        "a state guarded by a precondition that does not exist was enterable");
    for (AttachedPreCondition attached : state.getPreConditions()) {
      assertNotNull(attached.getConfiguration(),
          attached + " was left with no configuration");
    }
  }

  /**
   * Adding nothing adds nothing. The reader calls through for every guard it
   * finds, including ones it could not turn into an attachment at all.
   */
  @HegelTest
  void addingNoGuardAddsNoGuard(TestCase tc) {
    int count = tc.draw(integers().min(0).max(3), "count");
    WorkflowState state = named(tc.draw(sampledFrom(NAMES), "name"));
    for (int i = 0; i < count; i++) {
      state.addPreCondition(new AttachedPreCondition("guard" + i,
          new StubPreCondition(true), null));
    }

    state.addPreCondition(null);

    assertEquals(count, state.getPreConditions().size(),
        "adding nothing changed the guards");
  }
}
