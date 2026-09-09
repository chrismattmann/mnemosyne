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
package org.apache.oodt.cas.workflow.engine.processor;

import java.util.List;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.LogManager;

import junit.framework.TestCase;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * A condition that answers "no" is asked again.
 *
 * <p>
 * A condition is a task, and a task that fails is finished: it lands in
 * Failure, the lifecycle files Failure under "done", a done child is skipped
 * by the sequential parent and excluded from the querier's repository query,
 * and {@code passedPreConditions} requires Success. So the first "no" ended
 * the matter and the task it guarded waited forever.
 * </p>
 *
 * <p>
 * That made every condition a one-shot gate, which is the opposite of the
 * point. A gate holds work until something becomes true, and it is precisely
 * not true when first asked. ProductCountSettledCondition waits for a count to
 * stop rising and is asked before anything has been produced, so it could
 * never once have returned true; the join it guards has always been started by
 * hand.
 * </p>
 */
public class TestFailedConditionIsRequeued extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private WorkflowLifecycleManager manager;

  public TestFailedConditionIsRequeued() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  @Override
  protected void setUp() throws Exception {
    manager = new WorkflowLifecycleManager(LIFECYCLE);
  }

  /** The bug: asked once, refused, and never asked again. */
  public void testAConditionThatFailedIsAskedAgain() throws Exception {
    TaskProcessor gated = gatedTask("Failure");
    gated.getRunnableWorkflowProcessors();

    assertEquals("a condition that said no must go back in the queue",
        "Queued", conditionState(gated));
  }

  /** And is handed back as work, not merely relabelled. */
  public void testTheRequeuedConditionIsOfferedToTheRunner() throws Exception {
    TaskProcessor gated = gatedTask("Failure");
    List<TaskProcessor> runnable = gated.getRunnableWorkflowProcessors();

    assertEquals("the requeued condition is the work to be done",
        1, runnable.size());
    assertEquals("urn:oodt:condition",
        runnable.get(0).getWorkflowInstance().getId());
  }

  /**
   * A sequential parent rolls a child's Failure into its own state, and skips
   * children while it is itself done. Requeueing only the child would leave
   * the container done and the child unreachable.
   */
  public void testTheConditionContainerIsRequeuedWithIt() throws Exception {
    TaskProcessor gated = gatedTask("Failure");
    gated.getRunnableWorkflowProcessors();

    assertEquals("the container must not stay done over ready children",
        "Queued", gated.getPreConditions().getWorkflowInstance().getState()
            .getName());
  }

  /** A condition that passed is left alone. */
  public void testAPassedConditionIsNotDisturbed() throws Exception {
    TaskProcessor gated = gatedTask("Success");
    gated.getRunnableWorkflowProcessors();

    assertEquals("Success", conditionState(gated));
  }

  /**
   * The limit of the change. A task that fails is a real failure and stays
   * failed; retrying a PGE forever because it threw would be a worse bug than
   * the one being fixed.
   */
  public void testAFailedTaskIsNotRequeued() throws Exception {
    TaskProcessor gated = gatedTask("Failure", false);
    gated.getRunnableWorkflowProcessors();

    assertEquals("only conditions are requeued", "Failure",
        conditionState(gated));
  }

  // --------------------------------------------------------------- setup ---

  private String conditionState(TaskProcessor gated) {
    return gated.getPreConditions().getSubProcessors().get(0)
        .getWorkflowInstance().getState().getName();
  }

  private TaskProcessor gatedTask(String conditionState) throws Exception {
    return gatedTask(conditionState, true);
  }

  /**
   * A task whose single precondition is in {@code conditionState}, wrapped in
   * the sequential container the engine builds for conditions.
   *
   * @param isCondition whether the child is a ConditionProcessor or an
   *        ordinary task, which is what separates the two behaviours
   */
  private TaskProcessor gatedTask(String conditionState, boolean isCondition)
      throws Exception {
    TaskProcessor gated = taskProcessor("urn:oodt:gated");

    TaskProcessor condition = isCondition
        ? new ConditionProcessor(manager, instance("urn:oodt:condition"))
        : taskProcessor("urn:oodt:condition");
    condition.setState(state(conditionState,
        "Failure".equals(conditionState) ? "done" : "done"));

    SequentialProcessor container =
        new SequentialProcessor(manager, instance("urn:oodt:conditions"));
    List<WorkflowProcessor> children = new Vector<WorkflowProcessor>();
    children.add(condition);
    container.setSubProcessors(children);
    // The container carries its child's verdict, which is what the engine
    // does when it rolls a sub-processor's state up.
    container.setState(state(conditionState, "done"));

    gated.setPreConditions(container);
    return gated;
  }

  private TaskProcessor taskProcessor(String id) throws Exception {
    return new TaskProcessor(manager, instance(id));
  }

  private WorkflowInstance instance(String id) {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId(id);
    WorkflowTask task = new WorkflowTask();
    task.setTaskId(id + ":task");
    task.setTaskConfig(new WorkflowTaskConfiguration());
    Graph graph = new Graph();
    graph.setTask(task);
    ParentChildWorkflow workflow = new ParentChildWorkflow(graph);
    workflow.getTasks().add(task);
    inst.setParentChildWorkflow(workflow);
    inst.setCurrentTaskId(task.getTaskId());
    inst.setSharedContext(new Metadata());
    return inst;
  }

  private WorkflowState state(String name, String category) {
    return manager.getDefaultLifecycle().createState(name, category, "");
  }
}
