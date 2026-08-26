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

package org.apache.oodt.cas.workflow.structs;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;

/**
 * Properties of {@link WorkflowInstance}, the record of one run of a workflow.
 *
 * <p>An instance is what the workflow manager stores, pages over and reports
 * on. It keeps a state, a priority, a current task and the model it is running,
 * and offers each of them through both a current accessor and a deprecated one
 * kept for callers written before the type changed. The two views of the same
 * thing have to agree, since a repository writes through one and the monitor
 * reads through the other.
 */
class WorkflowInstancePropertyTest {

  private static String word(TestCase tc, String label) {
    return tc.draw(text().minSize(1).maxSize(6).categories("Lu", "Ll"), label);
  }

  /** A workflow whose tasks are identified {@code taskN}. */
  private static Workflow workflowWithTasks(int taskCount) {
    List<WorkflowTask> tasks = new ArrayList<>(taskCount);
    for (int i = 0; i < taskCount; i++) {
      WorkflowTask task = new WorkflowTask();
      task.setTaskId("task" + i);
      task.setTaskName("task" + i);
      tasks.add(task);
    }
    return new Workflow("aWorkflow", "aWorkflowId", tasks,
        new Vector<WorkflowCondition>(), new Vector<WorkflowCondition>());
  }

  /**
   * An instance nobody has given a state to reports the status "Null" rather
   * than throwing or answering null. The repositories filter and count
   * instances by comparing this string, so an instance without a state still
   * has to answer the question.
   */
  @HegelTest
  void anInstanceWithoutAStateStillReportsAStatus(TestCase tc) {
    tc.note("no input: this is what a freshly created instance must answer");

    WorkflowInstance instance = new WorkflowInstance();

    assertEquals("Null", instance.getStatus(),
        "a stateless instance reported " + instance.getStatus());
    assertNull(instance.getState(), "a fresh instance already has a state");
    assertNotNull(instance.getWorkflow(),
        "a fresh instance has no workflow to run");
    assertNotNull(instance.getSharedContext(),
        "a fresh instance has no shared context");
    assertEquals(Priority.getDefault(), instance.getPriority(),
        "a fresh instance did not start at the default priority");
  }

  /**
   * Setting a status and reading it back gives the same string, through either
   * view. The deprecated setter is how every status update from a running task
   * arrives, and the state it builds is what the lifecycle then reasons about.
   */
  @HegelTest
  void aStatusSetThroughTheDeprecatedViewIsTheStatesName(TestCase tc) {
    String status = word(tc, "status");
    WorkflowInstance instance = new WorkflowInstance();

    instance.setStatus(status);

    assertEquals(status, instance.getStatus(), "the status changed");
    assertNotNull(instance.getState(),
        "setting a status left the instance with no state");
    assertEquals(status, instance.getState().getName(),
        "the state built from the status is named differently");
  }

  /**
   * Setting a state is visible through the deprecated status view. The engine
   * sets states; the monitor and the instance repositories read statuses.
   */
  @HegelTest
  void aStateSetDirectlyIsVisibleAsAStatus(TestCase tc) {
    String name = word(tc, "name");
    WorkflowState state = new WorkflowState();
    state.setName(name);
    WorkflowInstance instance = new WorkflowInstance();

    instance.setState(state);

    assertSame(state, instance.getState(), "the state was swapped");
    assertEquals(name, instance.getStatus(),
        "the status does not report the state's name");
  }

  /**
   * The current task is the one in the model carrying the current task id, and
   * there is no current task when the id names nothing. The deprecated task
   * timing accessors are all built on this lookup, and the monitor calls them
   * for whatever instance it is showing.
   */
  @HegelTest
  void theCurrentTaskIsTheOneNamedByTheCurrentTaskId(TestCase tc) {
    int taskCount = tc.draw(integers().min(0).max(4), "taskCount");
    int pick = tc.draw(integers().min(-1).max(4), "pick");
    WorkflowInstance instance = new WorkflowInstance();
    instance.setWorkflow(workflowWithTasks(taskCount));
    String currentTaskId = "task" + pick;
    instance.setCurrentTaskId(currentTaskId);

    WorkflowTask current = instance.getCurrentTask();

    if (pick >= 0 && pick < taskCount) {
      assertNotNull(current, currentTaskId + " is in the model but was not found");
      assertEquals(currentTaskId, current.getTaskId(),
          "the lookup returned a different task");
    } else {
      assertNull(current,
          currentTaskId + " is not in a model of " + taskCount + " tasks");
    }
  }

  /**
   * Asking for the current task of a model that contains an unidentified task
   * answers rather than throwing. A task built by
   * {@link WorkflowTask#WorkflowTask()} and not yet filled in has no id, which
   * is the state every reader in this module leaves a task in part-way
   * through, and the monitor may ask about the instance at any moment.
   */
  @HegelTest
  void anUnidentifiedTaskInTheModelDoesNotBreakTheLookup(TestCase tc) {
    int taskCount = tc.draw(integers().min(0).max(3), "taskCount");
    Workflow workflow = workflowWithTasks(taskCount);
    workflow.getTasks().add(new WorkflowTask());
    WorkflowInstance instance = new WorkflowInstance();
    instance.setWorkflow(workflow);
    instance.setCurrentTaskId("task0");

    WorkflowTask current = instance.getCurrentTask();

    if (taskCount > 0) {
      assertNotNull(current, "task0 is in the model but was not found");
      assertEquals("task0", current.getTaskId(),
          "the lookup returned a different task");
    } else {
      assertNull(current, "a task with no id answered to task0");
    }
  }

  /**
   * The graph view describes the workflow the instance is actually running,
   * and is the same object each time it is asked for. Engines hold on to it
   * and read through it repeatedly; a fresh wrapper per call would mean a
   * graph edited through one view is invisible through the next.
   */
  @HegelTest
  void theGraphViewDescribesTheStoredWorkflowAndIsStable(TestCase tc) {
    int taskCount = tc.draw(integers().min(0).max(3), "taskCount");
    Workflow workflow = workflowWithTasks(taskCount);
    WorkflowInstance instance = new WorkflowInstance();

    instance.setWorkflow(workflow);

    ParentChildWorkflow view = instance.getParentChildWorkflow();
    assertNotNull(view, "the instance offers no graph view");
    assertEquals(workflow.getId(), view.getId(),
        "the graph view has a different id");
    assertEquals(workflow.getName(), view.getName(),
        "the graph view has a different name");
    assertEquals(workflow.getTasks(), view.getTasks(),
        "the graph view runs different tasks");
    assertSame(view, instance.getParentChildWorkflow(),
        "the graph view is rebuilt on every call");
  }

  /**
   * A graph-shaped workflow set on an instance is the workflow it reports
   * through both views. Storing a copy would let the engine's edits to the
   * graph and the repository's reads of the workflow drift apart.
   */
  @HegelTest
  void aGraphShapedWorkflowIsReportedThroughBothViews(TestCase tc) {
    int taskCount = tc.draw(integers().min(0).max(3), "taskCount");
    ParentChildWorkflow workflow =
        new ParentChildWorkflow(workflowWithTasks(taskCount));
    WorkflowInstance instance = new WorkflowInstance();

    instance.setParentChildWorkflow(workflow);

    assertSame(workflow, instance.getParentChildWorkflow(),
        "the graph view is not the workflow that was set");
    assertSame(workflow, instance.getWorkflow(),
        "the stored workflow is not the one that was set");
  }

  /**
   * Everything else set on an instance reads back unchanged, and the
   * convenience names for the dates report the dates themselves. The FIFO and
   * priority sorters read the creation date and the priority off the instance
   * on every pass of the engine.
   */
  @HegelTest
  void whateverIsSetOnAnInstanceReadsBack(TestCase tc) {
    String id = word(tc, "id");
    String currentTaskId = word(tc, "currentTaskId");
    int timesBlocked = tc.draw(integers().min(0).max(50), "timesBlocked");
    long startMillis = tc.draw(integers().min(0).max(100000), "start");
    Priority priority =
        Priority.getPriority(tc.draw(integers().min(0).max(10), "priority"));
    WorkflowInstance instance = new WorkflowInstance();

    instance.setId(id);
    instance.setCurrentTaskId(currentTaskId);
    instance.setTimesBlocked(timesBlocked);
    instance.setStartDate(new Date(startMillis));
    instance.setEndDate(new Date(startMillis + 1));
    instance.setPriority(priority);

    assertEquals(id, instance.getId(), "the id changed");
    assertEquals(currentTaskId, instance.getCurrentTaskId(),
        "the current task id changed");
    assertEquals(timesBlocked, instance.getTimesBlocked(),
        "the blocked count changed");
    assertEquals(new Date(startMillis), instance.getStartDate(),
        "the start date changed");
    assertEquals(instance.getStartDate(), instance.getCreationDate(),
        "the creation date is not the start date");
    assertEquals(instance.getEndDate(), instance.getFinishDate(),
        "the finish date is not the end date");
    assertEquals(priority, instance.getPriority(), "the priority changed");
  }
}
