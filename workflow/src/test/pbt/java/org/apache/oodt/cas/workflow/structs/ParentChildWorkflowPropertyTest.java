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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * Properties of {@link ParentChildWorkflow}, the graph-shaped view of a
 * {@link Workflow}.
 *
 * <p>{@link WorkflowInstance} wraps whatever workflow it is given in one of
 * these whenever an engine asks for the graph view, and the wrapper is what
 * the packaged-workflow engine then runs. Anything the wrapper does not carry
 * across is invisible to that engine even though it was written in the
 * workflow file.
 */
class ParentChildWorkflowPropertyTest {

  private static String word(TestCase tc, String label) {
    return tc.draw(text().minSize(1).maxSize(6).categories("Lu", "Ll"), label);
  }

  private static WorkflowCondition condition(String id) {
    return new WorkflowCondition(id + "Name", id, id + "Class", 0);
  }

  private static List<WorkflowCondition> conditions(String label, int count) {
    List<WorkflowCondition> drawn = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      drawn.add(condition(label + i));
    }
    return drawn;
  }

  private static List<WorkflowTask> tasks(String label, int count) {
    List<WorkflowTask> drawn = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      WorkflowTask task = new WorkflowTask();
      task.setTaskId(label + i);
      task.setTaskName(label + i);
      drawn.add(task);
    }
    return drawn;
  }

  /** A workflow with the drawn number of tasks, pre- and post-conditions. */
  private static Workflow workflowOf(TestCase tc) {
    int taskCount = tc.draw(integers().min(0).max(3), "taskCount");
    int preCount = tc.draw(integers().min(0).max(3), "preCount");
    int postCount = tc.draw(integers().min(0).max(3), "postCount");
    return new Workflow(word(tc, "name"), word(tc, "id"),
        tasks("task", taskCount), conditions("pre", preCount),
        conditions("post", postCount));
  }

  /**
   * Wrapping a workflow keeps its identity and its tasks. The graph engine
   * reports progress against the wrapper, so a wrapper under a different name
   * or id is a workflow the monitor cannot match to the one that was
   * submitted.
   */
  @HegelTest
  void wrappingKeepsTheIdentityAndTheTasks(TestCase tc) {
    Workflow workflow = workflowOf(tc);

    ParentChildWorkflow wrapped = new ParentChildWorkflow(workflow);

    assertEquals(workflow.getName(), wrapped.getName(),
        "the wrapper is named differently");
    assertEquals(workflow.getId(), wrapped.getId(),
        "the wrapper has a different id");
    assertEquals(workflow.getTasks(), wrapped.getTasks(),
        "the wrapper does not run the same tasks");
    assertNotNull(wrapped.getGraph(), "the wrapper has no graph");
  }

  /**
   * Wrapping a workflow keeps the conditions it guards its execution with.
   * Both lists are part of the model a workflow file declares: pre-conditions
   * decide whether the workflow may start and post-conditions whether it
   * finished acceptably, and an engine handed a wrapper is the only thing that
   * will ever check them.
   */
  @HegelTest
  void wrappingKeepsBothSetsOfConditions(TestCase tc) {
    Workflow workflow = workflowOf(tc);

    ParentChildWorkflow wrapped = new ParentChildWorkflow(workflow);

    assertEquals(workflow.getPreConditions(), wrapped.getPreConditions(),
        "the wrapper lost pre-conditions");
    assertEquals(workflow.getPostConditions(), wrapped.getPostConditions(),
        "the wrapper lost post-conditions: " + workflow.getPostConditions()
            + " became " + wrapped.getPostConditions());
  }

  /**
   * A wrapper built around a graph starts empty but usable, and the graph it
   * was built with is the graph it reports. This is the constructor the
   * packaged-workflow repository uses while it is still assembling a model.
   */
  @HegelTest
  void aWrapperBuiltAroundAGraphReportsThatGraph(TestCase tc) {
    String modelId = word(tc, "modelId");
    Graph graph = new Graph();
    graph.setModelId(modelId);
    graph.setExecutionType("sequential");

    ParentChildWorkflow wrapped = new ParentChildWorkflow(graph);

    assertSame(graph, wrapped.getGraph(), "the wrapper swapped the graph");
    assertNotNull(wrapped.getTasks(), "the wrapper has null tasks");
    assertTrue(wrapped.getTasks().isEmpty(),
        "a wrapper around a bare graph already has tasks");
  }

  /**
   * Printing a wrapper names it, its graph and each of its tasks. This string
   * is what the engine logs when it picks a workflow up, and it is the only
   * record of which model an instance was actually run against.
   */
  @HegelTest
  void printingAWrapperNamesItsModelAndItsTasks(TestCase tc) {
    Workflow workflow = workflowOf(tc);
    for (WorkflowTask task : workflow.getTasks()) {
      task.setPreConditions(conditions("taskPre", 1));
      task.setPostConditions(new Vector<WorkflowCondition>());
    }
    ParentChildWorkflow wrapped = new ParentChildWorkflow(workflow);
    wrapped.getGraph().setExecutionType("parallel");

    String printed = wrapped.toString();

    assertTrue(printed.contains(String.valueOf(workflow.getId())),
        printed + " does not name the workflow id");
    assertTrue(printed.contains("parallel"),
        printed + " does not name the execution type");
    for (WorkflowTask task : workflow.getTasks()) {
      assertTrue(printed.contains(task.getTaskId()),
          printed + " does not mention task " + task.getTaskId());
    }
  }
}
