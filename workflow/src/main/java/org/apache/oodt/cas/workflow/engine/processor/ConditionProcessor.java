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
package org.apache.oodt.cas.workflow.engine.processor;

//OODT import
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

/**
 * 
 * WorkflowProcessor which handles Workflow Pre/Post Conditions.
 * 
 * @author bfoster
 * @author mattmann
 * @version $Revision$
 * 
 */
public class ConditionProcessor extends TaskProcessor {

  public static final double DOUBLE = 0.1;

  public ConditionProcessor(WorkflowLifecycleManager lifecycleManager, WorkflowInstance inst) {
    super(lifecycleManager, inst);
  }

  /**
   * A condition that answered no has not failed; it has said "not yet".
   *
   * <p>
   * It used to land in Failure like any other task, and the lifecycle files
   * Failure under "done". Three things follow from that, and all three are
   * wrong for a gate: the parent workflow aggregates its children and goes
   * done with it, a done instance is excluded from the querier's repository
   * query, and requeueAnsweredConditions -- which exists to ask again -- only
   * runs when the querier asks the parent. So the gate's survival depended on
   * the parent staying alive, and the parent died of the gate.
   * </p>
   *
   * <p>
   * Live: a join gated on "every chunk translated" was asked once, when the
   * extract that fires its event finished and nought of 458 chunks were done.
   * Never asked again. Five hours later the answer had been yes for minutes
   * and the join had to be started by hand, which is how every run of that
   * pipeline had ever been finished.
   * </p>
   *
   * <p>
   * Going back in the queue rather than into a terminal state is the same
   * answer requeueAnsweredConditions already gives; saying it here means it
   * no longer depends on anything else being alive to say it. A condition
   * that will never pass now holds its task forever instead of failing it,
   * which is what a gate is: BlockTimeElapse and timesBlocked are the tools
   * for a deployment that wants a gate to give up.
   * </p>
   */
  @Override
  public WorkflowState failureOrRetry(String msg) {
    return this.helper.getLifecycleForProcessor(this).createState(
        "Queued", "waiting", "condition answered no; asking again: " + msg);
  }

  @Override
  public void setPreConditions(WorkflowProcessor preConditions) {
    // not allowed
  }

  @Override
  public void setPostConditions(WorkflowProcessor postConditions) {
    // not allowed
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.processor.TaskProcessor#setWorkflowInstance
   * (org.apache.oodt.cas.workflow.structs.WorkflowInstance)
   */
  @Override
  public void setWorkflowInstance(WorkflowInstance instance) {
    instance.setPriority(Priority
        .getPriority(instance.getPriority().getValue() - DOUBLE));
    super.setWorkflowInstance(instance);
  }

}
