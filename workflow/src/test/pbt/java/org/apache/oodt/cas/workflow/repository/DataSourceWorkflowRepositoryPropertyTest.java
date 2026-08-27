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

package org.apache.oodt.cas.workflow.repository;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import org.apache.oodt.cas.workflow.HsqlWorkflowDatabase;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

/**
 * Properties of {@link DataSourceWorkflowRepository}, the definition store the
 * workflow manager uses when it is configured against a database.
 *
 * <p>Where the instance repository holds what is running, this holds what can
 * be run: the workflows, their tasks and the conditions guarding them. The
 * manager reads it on every event it receives, and the {@code addWorkflow} and
 * {@code addTask} calls on its RPC interface write to it, so the properties
 * here are about the two things a caller relies on across that boundary —
 * that reading is a read, and that what was written can be found again.
 *
 * <p>The database is HSQLDB loaded from {@code src/test/resources/workflow.sql},
 * the same schema and seed rows {@code TestWorkflowDataSourceRepository} uses,
 * one throwaway database per test case.
 */
class DataSourceWorkflowRepositoryPropertyTest {

  /** The workflow the seed data declares, and the one condition it carries. */
  private static final String SEEDED_WORKFLOW_ID = "1";

  /** A task the seed data declares, and which is mapped to a workflow. */
  private static final String SEEDED_TASK_ID = "1";

  /** Characters SQL built by string concatenation might read as syntax. */
  private static final List<String> SIGNIFICANT =
      List.of("'", "\"", "%", "_", "\\", "--", ";", "é", "日");

  private static Workflow workflowOf(String id, String name) {
    Workflow workflow = new Workflow();
    workflow.setId(id);
    workflow.setName(name);
    WorkflowTask task = new WorkflowTask();
    task.setTaskId(SEEDED_TASK_ID);
    List<WorkflowTask> tasks = new Vector<WorkflowTask>();
    tasks.add(task);
    workflow.setTasks(tasks);
    return workflow;
  }

  private static WorkflowTask taskOf(String name) {
    WorkflowTask task = new WorkflowTask();
    task.setTaskName(name);
    task.setTaskInstanceClassName(
        "org.apache.oodt.cas.workflow.examples.NoOpTask");
    task.setPreConditions(new Vector());
    task.setPostConditions(new Vector());
    return task;
  }

  private static String drawSignificantText(TestCase tc, String label) {
    String around = tc.draw(
        text().minSize(0).maxSize(3).categories("Lu", "Ll", "Nd"),
        label + "Around");
    String significant = tc.draw(sampledFrom(SIGNIFICANT), label + "Char");
    return around + significant + around;
  }

  /** Draws {@code count} distinct workflow identifiers, as the column wants. */
  private static List<String> drawDistinctIds(TestCase tc, int count) {
    Set<String> ids = new LinkedHashSet<>();
    for (int i = 0; ids.size() < count && i < count * 8; i++) {
      ids.add(String.valueOf(
          tc.draw(integers().min(2).max(60), "workflowId" + i)));
    }
    List<String> drawn = new ArrayList<>(ids);
    tc.assume(drawn.size() == count);
    return drawn;
  }

  private static List<String> taskIdsOf(Workflow workflow) {
    List<String> ids = new ArrayList<>();
    if (workflow != null && workflow.getTasks() != null) {
      for (Object each : workflow.getTasks()) {
        ids.add(((WorkflowTask) each).getTaskId());
      }
    }
    return ids;
  }

  /**
   * Reading a workflow leaves the repository holding what it held before.
   *
   * <p>The manager reads a workflow definition every time an event arrives,
   * and a caller reading twice expects the same answer twice. Nothing about
   * {@code getWorkflowById} announces itself as a write, and a repository that
   * changed under a read would drift for as long as the manager stays up.
   */
  @HegelTest(testCases = 20)
  void readingAWorkflowLeavesTheRepositoryAsItWas(TestCase tc)
      throws Exception {
    int reads = tc.draw(integers().min(2).max(4), "reads");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      Workflow first = repository.getWorkflowById(SEEDED_WORKFLOW_ID);
      assertNotNull(first, "the seeded workflow could not be read at all");
      List<String> firstTasks = taskIdsOf(first);

      for (int i = 1; i < reads; i++) {
        Workflow again = repository.getWorkflowById(SEEDED_WORKFLOW_ID);
        assertEquals(firstTasks, taskIdsOf(again),
            "read " + (i + 1) + " of the same workflow found different tasks "
                + "than the first read");
      }
    } finally {
      database.close();
    }
  }

  /**
   * A workflow that was added can be found again under the identifier that
   * adding it reported.
   *
   * <p>{@code addWorkflow} returns an identifier and the caller has nothing
   * else to go on: the RPC interface hands it straight back to whoever asked
   * to define the workflow, and that is what they will use to start it. An
   * identifier that names a different workflow is worse than no identifier.
   */
  @HegelTest(testCases = 25)
  void anAddedWorkflowIsFoundUnderTheIdentifierAddingItReported(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(1).max(3), "count");
    List<String> ids = drawDistinctIds(tc, count);
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      Map<String, String> reportedToName = new LinkedHashMap<>();
      for (int i = 0; i < count; i++) {
        String name = "Workflow " + ids.get(i);
        String reported =
            repository.addWorkflow(workflowOf(ids.get(i), name));
        assertNotNull(reported, "adding a workflow reported no identifier");
        reportedToName.put(reported, name);
      }

      for (Map.Entry<String, String> entry : reportedToName.entrySet()) {
        Workflow found = repository.getWorkflowById(entry.getKey(), false, false);
        assertNotNull(found, "nothing is filed under " + entry.getKey()
            + ", which is what adding a workflow reported");
        assertEquals(entry.getValue(), found.getName(),
            "the workflow filed under " + entry.getKey()
                + " is not the one that was added under it");
      }
    } finally {
      database.close();
    }
  }

  /**
   * A workflow that was added keeps the tasks it was defined with.
   *
   * <p>A workflow is a list of tasks; that is all it is. {@code addWorkflow}
   * refuses a workflow whose tasks it cannot find, which says plainly that the
   * tasks are part of what is being defined, so a workflow that comes back
   * without them is not the workflow that was added and cannot be run.
   */
  @HegelTest(testCases = 25)
  void anAddedWorkflowKeepsTheTasksItWasDefinedWith(TestCase tc)
      throws Exception {
    String id = String.valueOf(tc.draw(integers().min(2).max(60), "workflowId"));
    String name = "Workflow " + id;
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      String reported = repository.addWorkflow(workflowOf(id, name));
      Workflow found = repository.getWorkflowById(reported, true, false);

      assertNotNull(found, "the workflow just added cannot be read back");
      assertEquals(List.of(SEEDED_TASK_ID), taskIdsOf(found),
          "the workflow came back with tasks other than the one it "
              + "was defined with");
    } finally {
      database.close();
    }
  }

  /**
   * A task that was added can be found again under the identifier that adding
   * it reported, and reports the name and class it was given. The workflow
   * manager's task-definition call is the only route by which a task reaches
   * the repository at run time.
   */
  @HegelTest(testCases = 25)
  void anAddedTaskIsFoundUnderTheIdentifierAddingItReported(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(1).max(3), "count");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      Map<String, String> reportedToName = new LinkedHashMap<>();
      for (int i = 0; i < count; i++) {
        String name = "Task" + tc.draw(integers().min(0).max(9), "taskName" + i)
            + "-" + i;
        String reported = repository.addTask(taskOf(name));
        assertNotNull(reported, "adding a task reported no identifier");
        reportedToName.put(reported, name);
      }

      for (Map.Entry<String, String> entry : reportedToName.entrySet()) {
        WorkflowTask found = repository.getTaskById(entry.getKey());
        assertNotNull(found, "nothing is filed under " + entry.getKey()
            + ", which is what adding a task reported");
        assertEquals(entry.getValue(), found.getTaskName(),
            "the task filed under " + entry.getKey()
                + " is not the one that was added under it");
      }
    } finally {
      database.close();
    }
  }

  /**
   * A sequence of definitions leaves the repository listing exactly the
   * workflows that were defined, alongside the ones the seed data declares.
   *
   * <p>This says nothing about which identifier a workflow ends up under, only
   * that defining one adds one and that {@code getWorkflows} lists it. A
   * manager that accepted a definition and then did not list it would leave a
   * workflow nobody can find in the monitor.
   */
  @HegelTest(testCases = 25)
  void definingWorkflowsLeavesThemAllListed(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(1).max(4), "count");
    List<String> ids = drawDistinctIds(tc, count);
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      Set<String> expectedNames = new HashSet<>();
      for (Object each : repository.getWorkflows(false, false)) {
        expectedNames.add(((Workflow) each).getName());
      }

      for (int i = 0; i < count; i++) {
        String name = "Workflow " + ids.get(i);
        repository.addWorkflow(workflowOf(ids.get(i), name));
        expectedNames.add(name);

        Set<String> listed = new HashSet<>();
        for (Object each : repository.getWorkflows(false, false)) {
          listed.add(((Workflow) each).getName());
        }
        assertEquals(expectedNames, listed,
            "after defining " + (i + 1) + " workflows the repository lists "
                + "a different set than was defined");
      }
    } finally {
      database.close();
    }
  }

  /**
   * A workflow whose name holds text a database might read as syntax can still
   * be defined and found by that name.
   *
   * <p>Workflow names come from whoever defines the workflow; nothing
   * constrains them, and an apostrophe in a name is ordinary English. This
   * repository builds every statement by concatenating them into a string.
   */
  @HegelTest(testCases = 25)
  void aWorkflowNameHoldingSqlSignificantTextSurvivesBeingDefined(TestCase tc)
      throws Exception {
    String id = String.valueOf(tc.draw(integers().min(2).max(60), "workflowId"));
    String name = drawSignificantText(tc, "name");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      String reported = repository.addWorkflow(workflowOf(id, name));

      Workflow byName = repository.getWorkflowByName(name, false, false);
      assertNotNull(byName,
          "a workflow named " + name + " was defined but cannot be found "
              + "by that name");
      assertEquals(name, byName.getName(), "the name changed in storage");
      assertTrue(reported != null && !reported.isEmpty(),
          "adding the workflow reported no identifier");
    } finally {
      database.close();
    }
  }
}
