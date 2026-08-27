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

package org.apache.oodt.cas.workflow.util;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * Round-trip properties of {@link XmlRpcStructFactory}.
 *
 * <p>Every call a client makes to the XML-RPC workflow manager passes through
 * this class twice: once to flatten a struct into maps and vectors, and once
 * at the far end to build it back. A field that does not survive the trip is
 * a field the client silently never receives, which is why these are stated
 * as round trips rather than as assertions about the map shape.
 *
 * <p>Names and identifiers are drawn non-empty throughout, because the maps
 * are {@link java.util.Hashtable}s and a null value cannot be put into one:
 * what a struct with missing fields does is a separate question from whether a
 * complete struct survives.
 */
class XmlRpcStructFactoryPropertyTest {

  /** Chosen to avoid a daylight-saving boundary in any zone the test may run in. */
  private static final long FEBRUARY_2021 = 1612137600000L;

  private static String word(TestCase tc, String label) {
    return tc.draw(text().minSize(1).maxSize(6).categories("Lu", "Ll"), label);
  }

  private static Date date(TestCase tc, String label) {
    return new Date(FEBRUARY_2021
        + tc.draw(integers().min(0).max(1000000), label));
  }

  private static WorkflowCondition condition(TestCase tc, String label) {
    WorkflowCondition condition = new WorkflowCondition(word(tc, label + "Name"),
        word(tc, label + "Id"), word(tc, label + "Class"),
        tc.draw(integers().min(-1).max(9), label + "Order"));
    condition.setTimeoutSeconds(tc.draw(longs().min(-1).max(600),
        label + "Timeout"));
    condition.setOptional(tc.draw(booleans(), label + "Optional"));
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    int properties = tc.draw(integers().min(0).max(3), label + "PropCount");
    for (int i = 0; i < properties; i++) {
      config.addConfigProperty(word(tc, label + "PropName" + i),
          word(tc, label + "PropValue" + i));
    }
    condition.setCondConfig(config);
    return condition;
  }

  private static List<WorkflowCondition> conditions(TestCase tc, String label) {
    int count = tc.draw(integers().min(0).max(2), label + "Count");
    List<WorkflowCondition> drawn = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      drawn.add(condition(tc, label + i));
    }
    return drawn;
  }

  private static WorkflowTask task(TestCase tc, String label) {
    WorkflowTask task = new WorkflowTask();
    task.setTaskId(word(tc, label + "Id"));
    task.setTaskName(word(tc, label + "Name"));
    task.setTaskInstanceClassName(word(tc, label + "Class"));
    task.setOrder(tc.draw(integers().min(-1).max(9), label + "Order"));
    task.setPreConditions(conditions(tc, label + "Pre"));
    task.setPostConditions(new Vector<WorkflowCondition>());
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    int properties = tc.draw(integers().min(0).max(3), label + "PropCount");
    for (int i = 0; i < properties; i++) {
      config.addConfigProperty(word(tc, label + "PropName" + i),
          word(tc, label + "PropValue" + i));
    }
    task.setTaskConfig(config);
    int metFields = tc.draw(integers().min(0).max(3), label + "MetCount");
    List<String> required = new ArrayList<>(metFields);
    for (int i = 0; i < metFields; i++) {
      required.add(word(tc, label + "Met" + i));
    }
    task.setRequiredMetFields(required);
    return task;
  }

  private static Workflow workflow(TestCase tc, String label) {
    int taskCount = tc.draw(integers().min(0).max(2), label + "TaskCount");
    List<WorkflowTask> tasks = new ArrayList<>(taskCount);
    for (int i = 0; i < taskCount; i++) {
      tasks.add(task(tc, label + "Task" + i));
    }
    return new Workflow(word(tc, label + "Name"), word(tc, label + "Id"), tasks,
        conditions(tc, label + "Pre"), new Vector<WorkflowCondition>());
  }

  private static WorkflowInstance instance(TestCase tc, String label) {
    WorkflowInstance instance = new WorkflowInstance();
    instance.setId(word(tc, label + "Id"));
    instance.setStatus(word(tc, label + "Status"));
    instance.setCurrentTaskId(word(tc, label + "CurrentTask"));
    instance.setWorkflow(workflow(tc, label + "Workflow"));
    instance.setStartDate(date(tc, label + "Start"));
    instance.setEndDate(date(tc, label + "End"));
    Metadata context = new Metadata();
    int keys = tc.draw(integers().min(0).max(3), label + "MetCount");
    for (int i = 0; i < keys; i++) {
      context.addMetadata(word(tc, label + "MetKey" + i),
          word(tc, label + "MetVal" + i));
    }
    instance.setSharedContext(context);
    instance.setPriority(Priority.getPriority(
        tc.draw(integers().min(0).max(10), label + "Priority")));
    return instance;
  }

  private static void assertSameConditions(List<WorkflowCondition> expected,
      List<?> actual, String what) {
    assertEquals(expected.size(), actual.size(),
        what + ": " + expected.size() + " conditions became " + actual.size());
    for (int i = 0; i < expected.size(); i++) {
      WorkflowCondition before = expected.get(i);
      WorkflowCondition after = (WorkflowCondition) actual.get(i);
      assertEquals(before.getConditionId(), after.getConditionId(),
          what + ": condition " + i + " came back with a different id");
      assertEquals(before.getConditionName(), after.getConditionName(),
          what + ": condition " + i + " came back with a different name");
      assertEquals(before.getConditionInstanceClassName(),
          after.getConditionInstanceClassName(),
          what + ": condition " + i + " would run a different class");
      assertEquals(before.getOrder(), after.getOrder(),
          what + ": condition " + i + " came back in a different order");
      assertEquals(before.getTimeoutSeconds(), after.getTimeoutSeconds(),
          what + ": condition " + i + " came back with a different timeout");
      assertEquals(before.isOptional(), after.isOptional(),
          what + ": condition " + i + " changed whether it is optional");
      assertEquals(before.getCondConfig().getProperties(),
          after.getCondConfig().getProperties(),
          what + ": condition " + i + " came back configured differently");
    }
  }

  /**
   * A task configuration survives the wire. It is the whole of what a task is
   * told to do, and it travels separately as part of a job's input as well as
   * inside the task itself.
   */
  @HegelTest
  void aTaskConfigurationSurvivesTheWire(TestCase tc) {
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    int properties = tc.draw(integers().min(0).max(5), "propCount");
    for (int i = 0; i < properties; i++) {
      config.addConfigProperty(word(tc, "name" + i), word(tc, "value" + i));
    }

    Map flattened = XmlRpcStructFactory.getXmlRpcWorkflowTaskConfiguration(
        config);
    WorkflowTaskConfiguration restored = XmlRpcStructFactory
        .getWorkflowTaskConfigurationFromXmlRpc(flattened);

    assertEquals(config.getProperties(), restored.getProperties(),
        "the configuration came back different");
  }

  /**
   * A condition configuration survives the wire, by the same argument: it is
   * what makes two uses of the same condition class behave differently.
   */
  @HegelTest
  void aConditionConfigurationSurvivesTheWire(TestCase tc) {
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    int properties = tc.draw(integers().min(0).max(5), "propCount");
    for (int i = 0; i < properties; i++) {
      config.addConfigProperty(word(tc, "name" + i), word(tc, "value" + i));
    }

    Map flattened = XmlRpcStructFactory.getXmlRpcWorkflowConditionConfig(config);
    WorkflowConditionConfiguration restored = XmlRpcStructFactory
        .getWorkflowConditionConfigurationFromXmlRpc(flattened);

    assertEquals(config.getProperties(), restored.getProperties(),
        "the configuration came back different");
  }

  /**
   * A condition survives the wire whole. Its id is how a workflow refers to it
   * from elsewhere, its timeout and optional flag decide what the engine does
   * when it will not pass, and its configuration decides what it checks.
   */
  @HegelTest
  void aConditionSurvivesTheWire(TestCase tc) {
    WorkflowCondition before = condition(tc, "cond");

    Map flattened = XmlRpcStructFactory.getXmlRpcWorkflowCondition(before);
    WorkflowCondition after = XmlRpcStructFactory
        .getWorkflowConditionFromXmlRpc(flattened);

    assertSameConditions(List.of(before), List.of(after), "a lone condition");
  }

  /**
   * A task survives the wire: its identity, the class that will run it, its
   * order, its configuration, the metadata it requires and the conditions
   * guarding it.
   */
  @HegelTest
  void aTaskSurvivesTheWire(TestCase tc) {
    WorkflowTask before = task(tc, "task");

    Map flattened = XmlRpcStructFactory.getXmlRpcWorkflowTask(before);
    WorkflowTask after = XmlRpcStructFactory.getWorkflowTaskFromXmlRpc(
        flattened);

    assertEquals(before.getTaskId(), after.getTaskId(), "the task id changed");
    assertEquals(before.getTaskName(), after.getTaskName(),
        "the task name changed");
    assertEquals(before.getTaskInstanceClassName(),
        after.getTaskInstanceClassName(), "the task would run a different class");
    assertEquals(before.getOrder(), after.getOrder(), "the order changed");
    assertEquals(before.getTaskConfig().getProperties(),
        after.getTaskConfig().getProperties(), "the configuration changed");
    assertEquals(before.getRequiredMetFields(), after.getRequiredMetFields(),
        "the required met fields changed");
    assertSameConditions(before.getPreConditions(), after.getPreConditions(),
        "a task's conditions");
  }

  /**
   * A workflow survives the wire, tasks and conditions and all. This is the
   * model a client receives when it asks the manager what a workflow is, and
   * the only description of it the client will ever see.
   */
  @HegelTest
  void aWorkflowSurvivesTheWire(TestCase tc) {
    Workflow before = workflow(tc, "workflow");

    Map flattened = XmlRpcStructFactory.getXmlRpcWorkflow(before);
    Workflow after = XmlRpcStructFactory.getWorkflowFromXmlRpc(flattened);

    assertEquals(before.getId(), after.getId(), "the workflow id changed");
    assertEquals(before.getName(), after.getName(), "the workflow name changed");
    assertEquals(before.getTasks().size(), after.getTasks().size(),
        "the workflow came back with a different number of tasks");
    for (int i = 0; i < before.getTasks().size(); i++) {
      assertEquals(before.getTasks().get(i).getTaskId(),
          after.getTasks().get(i).getTaskId(),
          "task " + i + " came back with a different id");
    }
    assertSameConditions(before.getPreConditions(), after.getPreConditions(),
        "a workflow's conditions");
  }

  /**
   * An instance survives the wire. Everything the monitor shows about a
   * running workflow — which one it is, what it is doing, when it started and
   * what it has accumulated in its shared context — arrives this way.
   */
  @HegelTest
  void anInstanceSurvivesTheWire(TestCase tc) {
    WorkflowInstance before = instance(tc, "inst");

    Map flattened = XmlRpcStructFactory.getXmlRpcWorkflowInstance(before);
    WorkflowInstance after = XmlRpcStructFactory.getWorkflowInstanceFromXmlRpc(
        flattened);

    assertEquals(before.getId(), after.getId(), "the instance id changed");
    assertEquals(before.getStatus(), after.getStatus(), "the status changed");
    assertEquals(before.getCurrentTaskId(), after.getCurrentTaskId(),
        "the current task id changed");
    assertEquals(before.getStartDate(), after.getStartDate(),
        "the start date changed");
    assertEquals(before.getEndDate(), after.getEndDate(),
        "the end date changed");
    assertEquals(before.getWorkflow().getId(), after.getWorkflow().getId(),
        "the instance came back running a different workflow");
    assertEquals(before.getSharedContext().getMap(),
        after.getSharedContext().getMap(), "the shared context changed");
  }

  /**
   * An instance's priority survives the wire. The priority is what decides
   * when the instance runs; the queue on the far side sorts by it, so an
   * instance that arrives at the default priority is one the sender's ordering
   * was thrown away for.
   */
  @HegelTest
  void anInstancesPrioritySurvivesTheWire(TestCase tc) {
    WorkflowInstance before = instance(tc, "inst");

    Map flattened = XmlRpcStructFactory.getXmlRpcWorkflowInstance(before);
    WorkflowInstance after = XmlRpcStructFactory.getWorkflowInstanceFromXmlRpc(
        flattened);

    assertNotNull(after.getPriority(), "the instance came back with no priority");
    assertEquals(before.getPriority(), after.getPriority(),
        "priority " + before.getPriority() + " came back as "
            + after.getPriority());
  }

  /**
   * A page of instances survives the wire, including where in the set it sits.
   * A client pages through instances by asking for the next page number, so a
   * page that forgets its own number or how many there are cannot be paged on
   * from.
   */
  @HegelTest
  void aPageOfInstancesSurvivesTheWire(TestCase tc) {
    int pageNum = tc.draw(integers().min(1).max(20), "pageNum");
    int totalPages = tc.draw(integers().min(1).max(20), "totalPages");
    int pageSize = tc.draw(integers().min(1).max(20), "pageSize");
    int instanceCount = tc.draw(integers().min(0).max(2), "instanceCount");
    List<WorkflowInstance> instances = new ArrayList<>(instanceCount);
    for (int i = 0; i < instanceCount; i++) {
      instances.add(instance(tc, "inst" + i));
    }
    WorkflowInstancePage before =
        new WorkflowInstancePage(pageNum, totalPages, pageSize, instances);

    Map flattened = XmlRpcStructFactory.getXmlRpcWorkflowInstancePage(before);
    WorkflowInstancePage after = XmlRpcStructFactory
        .getWorkflowInstancePageFromXmlRpc(flattened);

    assertEquals(pageNum, after.getPageNum(), "the page number changed");
    assertEquals(totalPages, after.getTotalPages(),
        "the total page count changed");
    assertEquals(pageSize, after.getPageSize(), "the page size changed");
    assertEquals(instanceCount, after.getPageWorkflows().size(),
        "the page came back holding a different number of instances");
    for (int i = 0; i < instanceCount; i++) {
      assertEquals(instances.get(i).getId(),
          ((WorkflowInstance) after.getPageWorkflows().get(i)).getId(),
          "instance " + i + " came back with a different id");
    }
    assertTrue(after.isFirstPage() == (pageNum == 1),
        "the page disagrees about being the first");
    assertTrue(after.isLastPage() == (pageNum == totalPages),
        "the page disagrees about being the last");
  }
}
