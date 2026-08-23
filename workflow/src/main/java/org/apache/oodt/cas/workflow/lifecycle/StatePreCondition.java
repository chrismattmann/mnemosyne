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
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

/**
 *
 * A guard on entry to a {@link WorkflowState}.
 *
 * A state that declares which states may follow it says what transitions are
 * structurally legal. A precondition says whether a legal transition should be
 * taken right now. When a processor changes state, each state reachable from
 * the current one is asked whether its preconditions are met; those that answer
 * yes are candidates, and the highest-priority stage among them wins.
 *
 * The shape deliberately mirrors
 * {@link org.apache.oodt.cas.workflow.structs.WorkflowConditionInstance}, which
 * is the interface OODT users already write when they need a task to wait on
 * something. The difference is what is being guarded: that one gates a task,
 * this one gates a state.
 *
 * Implementations must be safe to call repeatedly and from several threads,
 * since one instance is shared by every workflow using the lifecycle, and the
 * engine polls on every state change. They are constructed once, by no-argument
 * constructor, when the lifecycle file is read.
 *
 * @author mattmann
 * @author bfoster
 */
public interface StatePreCondition {

  /**
   * Decides whether the workflow may enter the given state now.
   *
   * @param candidateState
   *          The state being considered as the next state. This is the state
   *          the precondition is attached to.
   * @param instance
   *          The workflow instance being transitioned, from which the shared
   *          metadata context, current task and current state are reachable.
   * @param config
   *          The properties declared alongside this precondition in the
   *          lifecycle file. Never null; empty when none were declared.
   * @return True if the workflow may enter the state, false to leave it out of
   *         consideration on this pass.
   */
  boolean isMet(WorkflowState candidateState, WorkflowInstance instance,
      WorkflowConditionConfiguration config);

}
