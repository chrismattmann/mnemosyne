/**
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

//OODT imports
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

//JDK imports
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * Works out the next {@link WorkflowState} for an instance from what its
 * lifecycle declares.
 *
 * Where a workflow can go next used to live in a chain of string comparisons
 * inside the processor, which meant that changing the state machine meant
 * changing Java. A lifecycle can now say it directly: each state names the
 * states that may follow it, and each of those may carry
 * {@link StatePreCondition}s deciding whether it can be entered yet. When more
 * than one is eligible at the same moment, the priority of the stage each
 * belongs to picks the winner.
 *
 * This never guesses. A lifecycle that declares no transitions produces no
 * answer here, and the caller is expected to fall back to whatever it did
 * before, which is how existing deployments keep behaving as they always have.
 *
 * @author mattmann
 * @author bfoster
 */
public class WorkflowStateTransitioner {

  private static final Logger LOG = Logger
      .getLogger(WorkflowStateTransitioner.class.getName());

  private final WorkflowLifecycleManager lifecycleManager;

  public WorkflowStateTransitioner(WorkflowLifecycleManager lifecycleManager) {
    this.lifecycleManager = lifecycleManager;
  }

  /**
   * The state the instance should move to, or null if its lifecycle does not
   * say.
   *
   * Null means three different things on purpose, and the caller treats them
   * alike: the lifecycle declares no transitions out of the current state, it
   * declares some but none are eligible right now, or the eligible winner is
   * the state the instance is already in. In each case the instance stays
   * where it is.
   *
   * @param instance
   *          The instance to advance.
   * @return The next state, already a copy safe to hand to the instance, or
   *         null to stay put.
   */
  public WorkflowState nextState(WorkflowInstance instance) {
    if (instance == null || instance.getState() == null
        || this.lifecycleManager == null) {
      return null;
    }

    WorkflowState currentState = instance.getState();
    WorkflowLifecycle lifecycle = lifecycleFor(instance);
    if (lifecycle == null) {
      return null;
    }

    WorkflowState declared = declarationOf(lifecycle, currentState);
    if (declared == null || declared.getNextStateNames().isEmpty()) {
      return null;
    }

    List<WorkflowState> eligible = new Vector<WorkflowState>();
    for (String nextName : declared.getNextStateNames()) {
      WorkflowState candidate = lifecycle.getStateByName(nextName);
      if (candidate == null) {
        LOG.log(Level.WARNING, "State: [" + currentState.getName()
            + "] declares a transition to unknown state: [" + nextName
            + "] in lifecycle: [" + lifecycle.getName() + "]");
        continue;
      }
      if (candidate.preConditionsMet(instance)) {
        eligible.add(candidate);
      }
    }

    WorkflowState winner = highestPriority(eligible);
    if (winner == null || winner.getName().equals(currentState.getName())) {
      return null;
    }

    winner.setPrevState(currentState);
    winner.setStartTime(new Date());
    winner.setMessage("Workflow lifecycle: [" + lifecycle.getName()
        + "] moved instance: [" + instance.getId() + "] from state: ["
        + currentState.getName() + "] to state: [" + winner.getName() + "]");
    return winner;
  }

  /**
   * Whether the lifecycle governing this instance describes its transitions,
   * which is what tells a caller whether falling back to hard-coded behaviour
   * is still needed.
   *
   * @param instance
   *          The instance to check.
   * @return True if the instance's current state declares where it can go.
   */
  public boolean declaresTransitions(WorkflowInstance instance) {
    if (instance == null || instance.getState() == null
        || this.lifecycleManager == null) {
      return false;
    }
    WorkflowLifecycle lifecycle = lifecycleFor(instance);
    if (lifecycle == null) {
      return false;
    }
    WorkflowState declared = declarationOf(lifecycle, instance.getState());
    return declared != null && !declared.getNextStateNames().isEmpty();
  }

  /**
   * The highest-priority state among those eligible.
   *
   * Ties fall to the order the lifecycle declared the transitions in, so a
   * lifecycle that sets no priorities at all still behaves predictably rather
   * than depending on how a sort happened to arrange things.
   */
  private WorkflowState highestPriority(List<WorkflowState> eligible) {
    WorkflowState winner = null;
    int winningPriority = Integer.MIN_VALUE;
    for (WorkflowState candidate : eligible) {
      int priority = priorityOf(candidate);
      if (winner == null || priority > winningPriority) {
        winner = candidate;
        winningPriority = priority;
      }
    }
    return winner;
  }

  private int priorityOf(WorkflowState state) {
    return state.getCategory() != null ? state.getCategory().getPriority() : 0;
  }

  /**
   * The state as the lifecycle declares it, rather than the copy the instance
   * is carrying.
   *
   * The instance's state is built by
   * {@link WorkflowLifecycle#createState(String, String, String)}, which
   * produces a fresh object holding a name, a category and a message and
   * nothing else. Transitions and preconditions have to be read from the
   * declaration.
   */
  private WorkflowState declarationOf(WorkflowLifecycle lifecycle,
      WorkflowState state) {
    String category = state.getCategory() != null ? state.getCategory()
        .getName() : null;
    WorkflowState declared = lifecycle.getStateByNameAndCategory(
        state.getName(), category);
    if (declared == null && category != null) {
      // The instance may name a category the lifecycle files under something
      // else; the name is what identifies a state.
      declared = lifecycle.getStateByName(state.getName());
    }
    return declared;
  }

  private WorkflowLifecycle lifecycleFor(WorkflowInstance instance) {
    WorkflowLifecycle lifecycle = instance.getWorkflow() != null
        ? this.lifecycleManager.getLifecycleForWorkflow(instance.getWorkflow())
        : null;
    return lifecycle != null ? lifecycle
        : this.lifecycleManager.getDefaultLifecycle();
  }
}
