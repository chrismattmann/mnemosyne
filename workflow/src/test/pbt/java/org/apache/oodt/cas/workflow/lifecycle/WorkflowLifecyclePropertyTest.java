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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

/**
 * Properties of the stage and state lookups in {@link WorkflowLifecycle}.
 *
 * <p>Everything the workflow manager reports about how far along an instance is
 * — which stage it is in, how many stages there are, what percent complete that
 * makes it — is read back out of a lifecycle through these lookups, so what
 * they answer for a lifecycle nobody wrote by hand is worth stating.
 *
 * <p>The existing lifecycle suites all build their subject by parsing an XML
 * file from the source tree. A {@link WorkflowLifecycle} is an ordinary object
 * with no such dependency, so every lifecycle here is assembled in memory,
 * which is also the only way to reach shapes a hand-written file would not
 * contain.
 */
class WorkflowLifecyclePropertyTest {

  /** Stage names are drawn from a small alphabet so that names collide. */
  private static final List<String> STAGE_NAMES = List.of("queued", "running", "done");

  /** Likewise for state names, so a state can appear in more than one stage. */
  private static final List<String> STATE_NAMES = List.of("Null", "Queued", "Executing", "Finished");

  /** A stage name in neither alphabet, standing in for a lookup that must miss. */
  private static final String UNDECLARED = "notAStageName";

  private static String stage(int i) {
    return "stage" + i;
  }

  /**
   * A lifecycle of {@code stageCount} distinctly named stages, each holding the
   * states the caller assigned to it. Stage {@code i} is named {@code stage(i)}
   * and is ordered {@code i}, which is the shape a lifecycle file produces.
   */
  private static WorkflowLifecycle lifecycleOf(int stageCount, List<String> stateNames,
      List<Integer> stateStages) {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("aLifecycle", "aWorkflowId");
    List<WorkflowLifecycleStage> stages = new ArrayList<>();
    for (int i = 0; i < stageCount; i++) {
      stages.add(new WorkflowLifecycleStage(stage(i), new ArrayList<WorkflowState>(), i));
    }
    for (int i = 0; i < stateNames.size(); i++) {
      WorkflowLifecycleStage owner = stages.get(stateStages.get(i));
      WorkflowState state = new WorkflowState();
      state.setName(stateNames.get(i));
      state.setCategory(owner);
      state.setMessage("declared " + stateNames.get(i));
      owner.getStates().add(state);
    }
    for (WorkflowLifecycleStage each : stages) {
      lifecycle.addStage(each);
    }
    return lifecycle;
  }

  /** Draws a set of states and the stage each one is declared in. */
  private static List<String> drawStates(TestCase tc, int stageCount, List<Integer> stagesOut) {
    int stateCount = tc.draw(integers().min(0).max(5), "stateCount");
    List<String> names = new ArrayList<>(stateCount);
    for (int i = 0; i < stateCount; i++) {
      names.add(tc.draw(sampledFrom(STATE_NAMES), "state" + i));
      stagesOut.add(tc.draw(integers().min(0).max(stageCount - 1), "stageOfState" + i));
    }
    return names;
  }

  /**
   * A lifecycle holds one stage per name. {@link WorkflowLifecycleStage} is
   * identified by its name and nothing else — that is what its {@code equals}
   * and {@code hashCode} say — and the stages are held in a {@link SortedSet},
   * so declaring the same stage twice must leave the lifecycle with one of it.
   * Two stages sharing a name are not a harmless duplicate: they are both
   * counted by {@code getNumStages}, which is the denominator of the percent
   * complete the workflow manager reports.
   */
  @HegelTest
  void aLifecycleHoldsOneStagePerName(TestCase tc) {
    int stageCount = tc.draw(integers().min(1).max(4), "stageCount");
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("aLifecycle", "aWorkflowId");
    Set<String> distinctNames = new HashSet<>();
    for (int i = 0; i < stageCount; i++) {
      String name = tc.draw(sampledFrom(STAGE_NAMES), "name" + i);
      int order = tc.draw(integers().min(0).max(2), "order" + i);
      distinctNames.add(name);
      lifecycle.addStage(
          new WorkflowLifecycleStage(name, new ArrayList<WorkflowState>(), order));
    }

    assertEquals(distinctNames.size(), lifecycle.getStages().size(),
        "lifecycle holds " + lifecycle.getStages() + " for names " + distinctNames);
  }

  /**
   * Stages come back out in the order they declared, because a caller reading
   * them is reading a progression: stage one happens before stage two, and
   * {@code getStageNum} hands that number straight to a progress report.
   */
  @HegelTest
  void stagesComeOutInDeclaredOrder(TestCase tc) {
    int stageCount = tc.draw(integers().min(1).max(4), "stageCount");
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("aLifecycle", "aWorkflowId");
    for (int i = 0; i < stageCount; i++) {
      int order = tc.draw(integers().min(0).max(9), "order" + i);
      lifecycle.addStage(
          new WorkflowLifecycleStage(stage(i), new ArrayList<WorkflowState>(), order));
    }

    int previous = Integer.MIN_VALUE;
    for (Object each : lifecycle.getStages()) {
      int order = ((WorkflowLifecycleStage) each).getOrder();
      assertTrue(order >= previous, "stage ordered " + order + " came out after " + previous);
      previous = order;
    }
  }

  /**
   * A state the lifecycle declares can be looked up by name, and a state it
   * does not declare cannot. Everything downstream keys off this: the
   * transitioner asks the lifecycle what a state's declaration says before it
   * will move an instance anywhere.
   */
  @HegelTest
  void aDeclaredStateIsFoundByNameAndAnUndeclaredOneIsNot(TestCase tc) {
    int stageCount = tc.draw(integers().min(1).max(3), "stageCount");
    List<Integer> stateStages = new ArrayList<>();
    List<String> stateNames = drawStates(tc, stageCount, stateStages);
    WorkflowLifecycle lifecycle = lifecycleOf(stageCount, stateNames, stateStages);

    for (String name : STATE_NAMES) {
      WorkflowState found = lifecycle.getStateByName(name);
      if (stateNames.contains(name)) {
        assertNotNull(found, name + " was declared but was not found");
        assertEquals(name, found.getName(), "lookup returned a different state");
      } else {
        assertNull(found, name + " was never declared but was found");
      }
    }
  }

  /**
   * Asking for a state within a category answers about that category alone. The
   * transitioner narrows by category first and only widens when the narrow
   * lookup misses, so a lookup that quietly reached into another category would
   * make that fallback meaningless.
   */
  @HegelTest
  void aCategorisedLookupAnswersAboutThatCategoryOnly(TestCase tc) {
    int stageCount = tc.draw(integers().min(1).max(3), "stageCount");
    List<Integer> stateStages = new ArrayList<>();
    List<String> stateNames = drawStates(tc, stageCount, stateStages);
    WorkflowLifecycle lifecycle = lifecycleOf(stageCount, stateNames, stateStages);

    for (int i = 0; i < stageCount; i++) {
      Set<String> declaredHere = new HashSet<>();
      for (int s = 0; s < stateNames.size(); s++) {
        if (stateStages.get(s) == i) {
          declaredHere.add(stateNames.get(s));
        }
      }
      for (String name : STATE_NAMES) {
        WorkflowState found = lifecycle.getStateByNameAndCategory(name, stage(i));
        if (declaredHere.contains(name)) {
          assertNotNull(found, name + " was declared in " + stage(i) + " but was not found");
          assertEquals(stage(i), found.getCategory().getName(),
              "state came back filed under the wrong category");
        } else {
          assertNull(found, name + " is not in " + stage(i) + " but was found there");
        }
      }
    }

    for (String name : STATE_NAMES) {
      assertNull(lifecycle.getStateByNameAndCategory(name, UNDECLARED),
          "a category that does not exist answered for " + name);
    }
  }

  /**
   * A looked-up state is the caller's to change. The engine sets a message and
   * a start time on the state it is handed, so if the lookup handed back the
   * lifecycle's own object, one running instance would rewrite the declaration
   * every other instance is about to read.
   */
  @HegelTest
  void aLookedUpStateIsACopyTheCallerMayChange(TestCase tc) {
    int stageCount = tc.draw(integers().min(1).max(3), "stageCount");
    List<Integer> stateStages = new ArrayList<>();
    List<String> stateNames = drawStates(tc, stageCount, stateStages);
    tc.assume(!stateNames.isEmpty());
    WorkflowLifecycle lifecycle = lifecycleOf(stageCount, stateNames, stateStages);
    String name = stateNames.get(tc.draw(integers().min(0).max(stateNames.size() - 1), "pick"));

    WorkflowState first = lifecycle.getStateByName(name);
    first.setMessage("rewritten by an instance");
    first.addNextStateName("someStateAddedByTheCaller");

    WorkflowState second = lifecycle.getStateByName(name);
    assertEquals("declared " + name, second.getMessage(),
        "the lifecycle's own declaration was rewritten");
    assertTrue(second.getNextStateNames().isEmpty(),
        "a transition added to a copy leaked into the declaration");
  }

  /**
   * The deprecated status-to-stage lookup and the current state lookup are two
   * routes to the same declaration, so they agree on whether a status is known
   * at all. Callers of the workflow manager still reach the deprecated one
   * through {@code getStage}, which is what decides an instance's reported
   * stage.
   */
  @HegelTest
  void theDeprecatedStageLookupAgreesWithTheStateLookup(TestCase tc) {
    int stageCount = tc.draw(integers().min(1).max(3), "stageCount");
    List<Integer> stateStages = new ArrayList<>();
    List<String> stateNames = drawStates(tc, stageCount, stateStages);
    WorkflowLifecycle lifecycle = lifecycleOf(stageCount, stateNames, stateStages);

    for (String name : STATE_NAMES) {
      WorkflowLifecycleStage stage = lifecycle.getStageForWorkflow(name);
      WorkflowState state = lifecycle.getStateByName(name);

      assertEquals(state == null, stage == null,
          "the two lookups disagree about whether " + name + " exists");
      if (state != null) {
        assertEquals(stage.getName(), state.getCategory().getName(),
            "the two lookups found " + name + " in different stages");
      }
    }
  }
}
