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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import org.apache.oodt.cas.workflow.HsqlWorkflowDatabase;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.exceptions.RepositoryException;

/**
 * Properties of the lookup surface of {@link DataSourceWorkflowRepository}:
 * the by-id, by-name, by-event and by-task reads the workflow manager makes on
 * every event it handles, and the configuration reads a task instance is
 * constructed from.
 *
 * <p>The existing {@code DataSourceWorkflowRepositoryPropertyTest} is about
 * definition: what happens when a workflow or a task is added. This one is
 * about retrieval, which is the far larger half of the class and the half the
 * manager spends its time in. Several of these lookups exist in pairs — by
 * identifier and by name — reaching the same rows down different joins, so
 * each pair is required to agree.
 *
 * <p>Reads that ask for a workflow's conditions are avoided throughout.
 * Fetching a workflow with its conditions inserts a task row on every call,
 * which is already recorded; a property that went through that path would be
 * asserting against a database that changes underneath it and would say
 * nothing about the lookup it was meant to be about. Where a workflow is
 * needed, the three-argument form is used with conditions switched off.
 *
 * <p>The database is HSQLDB loaded from {@code src/test/resources/workflow.sql},
 * one throwaway database per case, holding the workflow, two tasks, four
 * conditions, one event and the two configuration rows the seed data declares.
 */
class DataSourceWorkflowRepositoryReadPropertyTest {

  /** The workflow the seed data declares. */
  private static final String SEEDED_WORKFLOW_ID = "1";
  private static final String SEEDED_WORKFLOW_NAME = "Test Workflow";

  /** The event the seed data maps to that workflow. */
  private static final String SEEDED_EVENT = "event";

  /** The tasks the seed data maps to that workflow, and their names. */
  private static final String SEEDED_TASK_ID = "1";
  private static final String SEEDED_TASK_NAME = "Test Task";
  private static final String SEEDED_OTHER_TASK_ID = "2";

  /** The condition the seed data attaches to task 1 and to workflow 1. */
  private static final String SEEDED_CONDITION_ID = "3";

  /** The conditions the seed data declares at all. */
  private static final List<String> SEEDED_CONDITION_IDS =
      List.of("1", "2", "3", "4");

  /** The one task configuration property the seed data declares. */
  private static final String SEEDED_TASK_PROPERTY = "TestProp";
  private static final String SEEDED_TASK_PROPERTY_VALUE = "TestVal";

  /** The one condition configuration property the seed data declares. */
  private static final String SEEDED_CONDITION_PROPERTY = "reqMetKeys";

  private static WorkflowTask taskOf(String name) {
    WorkflowTask task = new WorkflowTask();
    task.setTaskName(name);
    task.setTaskInstanceClassName(
        "org.apache.oodt.cas.workflow.examples.NoOpTask");
    task.setPreConditions(new Vector<WorkflowCondition>());
    task.setPostConditions(new Vector<WorkflowCondition>());
    return task;
  }

  private static Workflow workflowOf(String id, String name, String taskId) {
    Workflow workflow = new Workflow();
    workflow.setId(id);
    workflow.setName(name);
    WorkflowTask task = new WorkflowTask();
    task.setTaskId(taskId);
    List<WorkflowTask> tasks = new Vector<WorkflowTask>();
    tasks.add(task);
    workflow.setTasks(tasks);
    return workflow;
  }

  private static Set<String> conditionIdsOf(List<WorkflowCondition> conditions) {
    Set<String> ids = new LinkedHashSet<String>();
    if (conditions != null) {
      for (WorkflowCondition condition : conditions) {
        ids.add(condition.getConditionId());
      }
    }
    return ids;
  }

  private static Set<String> taskIdsOf(List<?> tasks) {
    Set<String> ids = new LinkedHashSet<String>();
    if (tasks != null) {
      for (Object each : tasks) {
        ids.add(((WorkflowTask) each).getTaskId());
      }
    }
    return ids;
  }

  /**
   * A task must be the same task however it is asked for, and must carry the
   * configuration it was declared with.
   *
   * <p>{@code getTaskById} and {@code getWorkflowTaskById} reach the same row
   * by different routes, and the workflow manager uses both. A task instance
   * is constructed from the configuration, so a property that goes missing
   * here is a task that runs with a default it was never given.
   */
  @HegelTest(testCases = 25)
  void aTaskIsTheSameTaskHoweverItIsAskedForAndKeepsItsConfiguration(
      TestCase tc) throws Exception {
    int reads = tc.draw(integers().min(1).max(3), "reads");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      for (int i = 0; i < reads; i++) {
        WorkflowTask byId = repository.getTaskById(SEEDED_TASK_ID);
        WorkflowTask plain = repository.getWorkflowTaskById(SEEDED_TASK_ID);

        assertNotNull(byId, "the seeded task could not be read by id");
        assertNotNull(plain,
            "the seeded task could not be read by the plain lookup");
        assertEquals(plain.getTaskId(), byId.getTaskId(),
            "the two lookups disagree about which task this is");
        assertEquals(plain.getTaskName(), byId.getTaskName(),
            "the two lookups disagree about the task's name");
        assertEquals(plain.getTaskInstanceClassName(),
            byId.getTaskInstanceClassName(),
            "the two lookups disagree about what class runs the task");

        WorkflowTaskConfiguration config =
            repository.getConfigurationByTaskId(SEEDED_TASK_ID);
        assertNotNull(config, "the seeded task reported no configuration");
        assertEquals(SEEDED_TASK_PROPERTY_VALUE,
            config.getProperty(SEEDED_TASK_PROPERTY),
            "the task's declared configuration property came back wrong");
        assertEquals(SEEDED_TASK_PROPERTY_VALUE,
            byId.getTaskConfig().getProperty(SEEDED_TASK_PROPERTY),
            "the task fetched by id does not carry the configuration the "
                + "configuration lookup reports for it");
      }
    } finally {
      database.close();
    }
  }

  /**
   * A condition must be readable by its identifier and must carry the
   * configuration it was declared with.
   *
   * <p>A condition instance is constructed from its configuration exactly as a
   * task is — {@code reqMetKeys} is the list of keys a metadata condition
   * checks for — so a missing property is a condition that passes everything.
   */
  @HegelTest(testCases = 25)
  void aConditionIsReadableByIdAndKeepsItsConfiguration(TestCase tc)
      throws Exception {
    String conditionId =
        tc.draw(sampledFrom(SEEDED_CONDITION_IDS), "conditionId");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      WorkflowCondition condition =
          repository.getWorkflowConditionById(conditionId);
      assertNotNull(condition,
          "the seeded condition " + conditionId + " could not be read by id");
      assertEquals(conditionId, condition.getConditionId(),
          "the condition filed under " + conditionId + " reports another id");
      assertNotNull(condition.getConditionName(),
          "the condition came back with no name");
      assertNotNull(condition.getConditionInstanceClassName(),
          "the condition came back with no class to run");

      WorkflowConditionConfiguration config =
          repository.getConfigurationByConditionId("1");
      assertNotNull(config,
          "the condition that declares a configuration reported none");
      assertNotNull(config.getProperty(SEEDED_CONDITION_PROPERTY),
          "the condition's declared configuration property is missing");
    } finally {
      database.close();
    }
  }

  /**
   * The conditions attached to a task must be the same set whether the task is
   * named by identifier or by name.
   *
   * <p>The two lookups join through different tables to reach the same rows. A
   * caller that has a name and a caller that has an identifier are asking the
   * same question about the same task.
   */
  @HegelTest(testCases = 25)
  void aTasksConditionsAreTheSameByNameAsById(TestCase tc) throws Exception {
    int reads = tc.draw(integers().min(1).max(3), "reads");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      for (int i = 0; i < reads; i++) {
        List<WorkflowCondition> byId =
            repository.getConditionsByTaskId(SEEDED_TASK_ID);
        List<WorkflowCondition> byName =
            repository.getConditionsByTaskName(SEEDED_TASK_NAME);

        assertEquals(Set.of(SEEDED_CONDITION_ID), conditionIdsOf(byId),
            "the conditions of the seeded task are not the ones it was "
                + "declared with");
        assertEquals(conditionIdsOf(byId), conditionIdsOf(byName),
            "asking for a task's conditions by name found a different set "
                + "than asking by id");
      }
    } finally {
      database.close();
    }
  }

  /**
   * The tasks of a workflow must be the same set whether the workflow is named
   * by identifier or by name.
   *
   * <p>Same argument as for a task's conditions: two joins, one answer.
   */
  @HegelTest(testCases = 25)
  void aWorkflowsTasksAreTheSameByNameAsById(TestCase tc) throws Exception {
    int reads = tc.draw(integers().min(1).max(3), "reads");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      for (int i = 0; i < reads; i++) {
        List<?> byId = repository.getTasksByWorkflowId(SEEDED_WORKFLOW_ID);
        List<?> byName =
            repository.getTasksByWorkflowName(SEEDED_WORKFLOW_NAME);

        assertEquals(Set.of(SEEDED_TASK_ID, SEEDED_OTHER_TASK_ID),
            taskIdsOf(byId),
            "the tasks of the seeded workflow are not the ones it was "
                + "declared with");
        assertEquals(taskIdsOf(byId), taskIdsOf(byName),
            "asking for a workflow's tasks by name found a different set "
                + "than asking by id");
      }
    } finally {
      database.close();
    }
  }

  /**
   * Every registered event must name at least one workflow that can be found
   * by it, and the workflow found must be one the repository lists.
   *
   * <p>An event is the only thing that starts a workflow. An event registered
   * against a workflow nobody can find by it is a workflow that will never
   * run.
   */
  @HegelTest(testCases = 25)
  void everyRegisteredEventFindsAWorkflowTheRepositoryLists(TestCase tc)
      throws Exception {
    int added = tc.draw(integers().min(0).max(3), "added");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      for (int i = 0; i < added; i++) {
        repository.addWorkflow(workflowOf(String.valueOf(20 + i),
            "Added Workflow " + i, SEEDED_TASK_ID));
      }

      List<String> events = repository.getRegisteredEvents();
      assertNotNull(events, "the repository reported no registered events");
      assertTrue(events.contains(SEEDED_EVENT),
          "the event the seed data declares is not registered");

      Set<String> listed = new LinkedHashSet<String>();
      for (Object each : repository.getWorkflows(false, false)) {
        listed.add(((Workflow) each).getId());
      }

      for (String event : events) {
        List<Workflow> forEvent =
            repository.getWorkflowsForEvent(event, false, false);
        assertNotNull(forEvent,
            "the registered event '" + event + "' finds no workflow at all");
        assertTrue(!forEvent.isEmpty(),
            "the registered event '" + event + "' finds no workflow at all");
        for (Workflow workflow : forEvent) {
          assertTrue(listed.contains(workflow.getId()),
              "event '" + event + "' finds workflow " + workflow.getId()
                  + ", which the repository does not list");
        }
      }
    } finally {
      database.close();
    }
  }

  /**
   * Defining a workflow registers an event that finds it.
   *
   * <p>{@code addWorkflow} maps the new workflow to an event named after it,
   * which is the only handle anything has on a workflow defined at run time
   * through the manager's RPC interface.
   */
  @HegelTest(testCases = 25)
  void definingAWorkflowRegistersAnEventThatFindsIt(TestCase tc)
      throws Exception {
    int id = tc.draw(integers().min(10).max(60), "workflowId");
    String name = "Workflow " + id;
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      String reported =
          repository.addWorkflow(workflowOf(String.valueOf(id), name,
              SEEDED_TASK_ID));
      assertNotNull(reported, "defining a workflow reported no identifier");

      List<String> events = repository.getRegisteredEvents();
      assertTrue(events.contains("workflow-" + reported),
          "defining workflow " + reported + " did not register the event "
              + "named after it; the registered events are " + events);

      List<Workflow> forEvent =
          repository.getWorkflowsForEvent("workflow-" + reported, false, false);
      assertNotNull(forEvent,
          "the event the definition registered finds no workflow");
      Set<String> found = new LinkedHashSet<String>();
      for (Workflow workflow : forEvent) {
        found.add(workflow.getId());
      }
      assertTrue(found.contains(reported),
          "the event registered for workflow " + reported + " finds "
              + found + " instead");
    } finally {
      database.close();
    }
  }

  /**
   * {@code getConditions} must list every condition the repository holds.
   *
   * <p>This is what {@code addTask} checks a new task's conditions against, so
   * a condition it fails to list is a condition no task can be defined
   * against.
   */
  @HegelTest(testCases = 20)
  void everyDeclaredConditionIsListed(TestCase tc) throws Exception {
    int reads = tc.draw(integers().min(1).max(3), "reads");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      for (int i = 0; i < reads; i++) {
        List<WorkflowCondition> conditions = repository.getConditions();
        assertNotNull(conditions, "the repository listed no conditions at all");
        assertEquals(new LinkedHashSet<String>(SEEDED_CONDITION_IDS),
            conditionIdsOf(conditions),
            "the repository does not list the conditions the seed data "
                + "declares");
      }
    } finally {
      database.close();
    }
  }

  /**
   * An identifier or a name that matches nothing must produce nothing, on
   * every lookup that takes one.
   *
   * <p>The manager receives identifiers from clients, from persisted instances
   * and from its own configuration, and any of them can name something that
   * has since been removed or was never there. Producing somebody else's
   * workflow would be worse than producing none.
   */
  @HegelTest(testCases = 25)
  void anUnknownIdentifierProducesNothingOnEveryLookup(TestCase tc)
      throws Exception {
    int absentId = tc.draw(integers().min(500).max(9999), "absentId");
    String absentName =
        tc.draw(text().minSize(1).maxSize(8).categories("Lu", "Ll"),
            "absentName");
    String absent = String.valueOf(absentId);

    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      assertNull(repository.getWorkflowById(absent, false, false),
          "a workflow identifier that names nothing produced a workflow");
      assertNull(
          repository.getWorkflowByName(absentName + "-missing", false, false),
          "a workflow name that names nothing produced a workflow");
      assertNull(repository.getWorkflowTaskById(absent),
          "a task identifier that names nothing produced a task");
      assertNull(repository.getWorkflowConditionById(absent),
          "a condition identifier that names nothing produced a condition");
      assertNull(repository.getTaskById(absent),
          "a task identifier that names nothing produced a task");
      assertNull(repository.getTasksByWorkflowId(absent),
          "a workflow identifier that names nothing produced tasks");
      assertNull(repository.getTasksByWorkflowName(absentName + "-missing"),
          "a workflow name that names nothing produced tasks");
      assertNull(repository.getConditionsByTaskId(absent),
          "a task identifier that names nothing produced conditions");
      assertNull(repository.getConditionsByTaskName(absentName + "-missing"),
          "a task name that names nothing produced conditions");
      assertNull(repository.getConditionsByWorkflowId(absent),
          "a workflow identifier that names nothing produced conditions");
      assertNull(repository.getConfigurationByTaskId(absent),
          "a task identifier that names nothing produced a configuration");
      assertNull(repository.getConfigurationByConditionId(absent),
          "a condition identifier that names nothing produced a "
              + "configuration");
      assertNull(
          repository.getWorkflowsForEvent(absentName + "-missing", false, false),
          "an event nothing is registered against produced workflows");
    } finally {
      database.close();
    }
  }

  /**
   * A definition that refers to something the repository does not hold must be
   * refused rather than half stored.
   *
   * <p>A workflow whose task does not exist cannot run, and a task whose
   * condition does not exist cannot be evaluated. The repository checks both
   * before it writes anything; accepting either would leave a definition in
   * the store that fails only when somebody tries to use it.
   */
  @HegelTest(testCases = 25)
  void aDefinitionReferringToSomethingUndefinedIsRefused(TestCase tc)
      throws Exception {
    int workflowId = tc.draw(integers().min(10).max(60), "workflowId");
    int absentTaskId = tc.draw(integers().min(500).max(9999), "absentTaskId");
    int absentConditionId =
        tc.draw(integers().min(500).max(9999), "absentConditionId");

    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      int before = repository.getWorkflows(false, false).size();

      Workflow noTasks = new Workflow();
      noTasks.setId(String.valueOf(workflowId));
      noTasks.setName("No Tasks");
      noTasks.setTasks(new Vector<WorkflowTask>());
      assertThrows(RepositoryException.class,
          () -> repository.addWorkflow(noTasks),
          "a workflow with no tasks at all was accepted");

      Workflow unknownTask = workflowOf(String.valueOf(workflowId),
          "Unknown Task", String.valueOf(absentTaskId));
      assertThrows(RepositoryException.class,
          () -> repository.addWorkflow(unknownTask),
          "a workflow referring to a task that does not exist was accepted");

      WorkflowTask task = taskOf("Task with a missing condition");
      WorkflowCondition missing = new WorkflowCondition();
      missing.setConditionId(String.valueOf(absentConditionId));
      missing.setConditionName("Missing");
      List<WorkflowCondition> pre = new Vector<WorkflowCondition>();
      pre.add(missing);
      task.setPreConditions(pre);
      assertThrows(RepositoryException.class, () -> repository.addTask(task),
          "a task referring to a condition that does not exist was accepted");

      assertEquals(before, repository.getWorkflows(false, false).size(),
          "a refused definition left a workflow behind anyway");
    } finally {
      database.close();
    }
  }

  /**
   * A task defined against conditions the repository already holds is accepted
   * and can then be found under the identifier that defining it reported.
   *
   * <p>This is the accepting half of the check the previous property states
   * the refusing half of. A repository that refused everything would satisfy
   * that one and be useless.
   */
  @HegelTest(testCases = 25)
  void aTaskDefinedAgainstExistingConditionsIsAcceptedAndFound(TestCase tc)
      throws Exception {
    String conditionId =
        tc.draw(sampledFrom(SEEDED_CONDITION_IDS), "conditionId");
    String name = "Task"
        + tc.draw(text().minSize(1).maxSize(5).categories("Lu", "Ll"), "name");

    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      WorkflowCondition condition =
          repository.getWorkflowConditionById(conditionId);
      assertNotNull(condition, "the seed data's condition could not be read");

      WorkflowTask task = taskOf(name);
      List<WorkflowCondition> pre = new Vector<WorkflowCondition>();
      pre.add(condition);
      task.setPreConditions(pre);

      String reported = repository.addTask(task);
      assertNotNull(reported, "defining a task reported no identifier");

      WorkflowTask found = repository.getWorkflowTaskById(reported);
      assertNotNull(found, "nothing is filed under " + reported
          + ", which is what defining the task reported");
      assertEquals(name, found.getTaskName(),
          "the task filed under " + reported + " is not the one that was "
              + "defined under it");

      List<String> allIds = new ArrayList<String>();
      for (Object each : repository.getWorkflows(false, false)) {
        allIds.add(((Workflow) each).getId());
      }
      assertTrue(!allIds.isEmpty(),
          "defining a task emptied the workflow listing");
    } finally {
      database.close();
    }
  }

  /**
   * The conditions attached to a workflow must be the ones it was declared
   * with.
   *
   * <p>A workflow-level condition gates the whole workflow, so one that is not
   * reported is a gate that never closes. This asks for them directly rather
   * than through {@code getWorkflowById}, which would take the path that
   * writes a task row on every read.
   */
  @HegelTest(testCases = 25)
  void aWorkflowsConditionsAreTheOnesItWasDeclaredWith(TestCase tc)
      throws Exception {
    int reads = tc.draw(integers().min(1).max(3), "reads");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      for (int i = 0; i < reads; i++) {
        assertEquals(Set.of(SEEDED_CONDITION_ID),
            conditionIdsOf(
                repository.getConditionsByWorkflowId(SEEDED_WORKFLOW_ID)),
            "the workflow's conditions are not the ones it was declared with");
      }
    } finally {
      database.close();
    }
  }

  /**
   * A workflow read by identifier and the same workflow read by name must be
   * the same workflow.
   *
   * <p>The manager resolves a workflow either way depending on what its caller
   * had, and a client that started a workflow by name and then monitored it by
   * identifier is relying on the two being one workflow.
   */
  @HegelTest(testCases = 25)
  void aWorkflowIsTheSameWorkflowByNameAsById(TestCase tc) throws Exception {
    int reads = tc.draw(integers().min(1).max(3), "reads");
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowRepository repository =
          new DataSourceWorkflowRepository(database.dataSource());

      for (int i = 0; i < reads; i++) {
        Workflow byId =
            repository.getWorkflowById(SEEDED_WORKFLOW_ID, true, false);
        Workflow byName =
            repository.getWorkflowByName(SEEDED_WORKFLOW_NAME, true, false);

        assertNotNull(byId, "the seeded workflow could not be read by id");
        assertNotNull(byName, "the seeded workflow could not be read by name");
        assertEquals(byId.getId(), byName.getId(),
            "the two lookups disagree about which workflow this is");
        assertEquals(byId.getName(), byName.getName(),
            "the two lookups disagree about the workflow's name");
        assertEquals(taskIdsOf(byId.getTasks()), taskIdsOf(byName.getTasks()),
            "the two lookups disagree about the workflow's tasks");
      }
    } finally {
      database.close();
    }
  }
}
