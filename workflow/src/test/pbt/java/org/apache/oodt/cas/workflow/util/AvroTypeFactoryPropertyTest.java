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

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflow;
import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflowCondition;
import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflowInstance;
import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflowInstancePage;
import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflowTask;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * Round-trip properties of {@link AvroTypeFactory}.
 *
 * <p>This is the newer of the two wire formats the workflow manager speaks,
 * and unlike the XML-RPC one it has a place for both a task's pre-conditions
 * and its post-conditions. What it carries is what an Avro client can be told,
 * so each property here converts a struct out and straight back and asks
 * whether the thing that returns would behave as the thing that was sent.
 */
class AvroTypeFactoryPropertyTest {

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
    task.setPostConditions(conditions(tc, label + "Post"));
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    int properties = tc.draw(integers().min(0).max(3), label + "PropCount");
    for (int i = 0; i < properties; i++) {
      config.addConfigProperty(word(tc, label + "PropName" + i),
          word(tc, label + "PropValue" + i));
    }
    task.setTaskConfig(config);
    List<String> required = new ArrayList<>();
    int metFields = tc.draw(integers().min(0).max(3), label + "MetCount");
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
        conditions(tc, label + "Pre"), conditions(tc, label + "Post"));
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
      List<WorkflowCondition> actual, String what) {
    assertEquals(expected.size(), actual.size(),
        what + ": " + expected.size() + " conditions became " + actual.size());
    for (int i = 0; i < expected.size(); i++) {
      WorkflowCondition before = expected.get(i);
      WorkflowCondition after = actual.get(i);
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
   * A condition survives the trip whole, configuration and all.
   */
  @HegelTest
  void aConditionSurvivesTheTrip(TestCase tc) {
    WorkflowCondition before = condition(tc, "cond");

    AvroWorkflowCondition sent = AvroTypeFactory.getAvroWorkflowCondition(
        before);
    WorkflowCondition after = AvroTypeFactory.getWorkflowCondition(sent);

    assertSameConditions(List.of(before), List.of(after), "a lone condition");
  }

  /**
   * A task survives the trip with both sets of conditions intact. This format
   * carries them separately, which is the reason it exists: a post-condition
   * that arrived as a pre-condition would be checked before the task ran
   * instead of after.
   */
  @HegelTest
  void aTaskSurvivesTheTripWithBothSetsOfConditions(TestCase tc) {
    WorkflowTask before = task(tc, "task");

    AvroWorkflowTask sent = AvroTypeFactory.getAvroWorkflowTask(before);
    WorkflowTask after = AvroTypeFactory.getWorkflowTask(sent);

    assertEquals(before.getTaskId(), after.getTaskId(), "the task id changed");
    assertEquals(before.getTaskName(), after.getTaskName(),
        "the task name changed");
    assertEquals(before.getTaskInstanceClassName(),
        after.getTaskInstanceClassName(),
        "the task would run a different class");
    assertEquals(before.getOrder(), after.getOrder(), "the order changed");
    assertEquals(before.getTaskConfig().getProperties(),
        after.getTaskConfig().getProperties(), "the configuration changed");
    assertEquals(before.getRequiredMetFields(), after.getRequiredMetFields(),
        "the required met fields changed");
    assertSameConditions(before.getPreConditions(), after.getPreConditions(),
        "a task's pre-conditions");
    assertSameConditions(before.getPostConditions(), after.getPostConditions(),
        "a task's post-conditions");
  }

  /**
   * A workflow survives the trip, including the conditions guarding the
   * workflow itself rather than any one of its tasks.
   */
  @HegelTest
  void aWorkflowSurvivesTheTrip(TestCase tc) {
    Workflow before = workflow(tc, "workflow");

    AvroWorkflow sent = AvroTypeFactory.getAvroWorkflow(before);
    Workflow after = AvroTypeFactory.getWorkflow(sent);

    assertEquals(before.getId(), after.getId(), "the workflow id changed");
    assertEquals(before.getName(), after.getName(),
        "the workflow name changed");
    assertEquals(before.getTasks().size(), after.getTasks().size(),
        "the workflow came back with a different number of tasks");
    for (int i = 0; i < before.getTasks().size(); i++) {
      assertEquals(before.getTasks().get(i).getTaskId(),
          after.getTasks().get(i).getTaskId(),
          "task " + i + " came back with a different id");
    }
    assertSameConditions(before.getPreConditions(), after.getPreConditions(),
        "a workflow's pre-conditions");
    assertSameConditions(before.getPostConditions(), after.getPostConditions(),
        "a workflow's post-conditions");
  }

  /**
   * An instance survives the trip: what it is, what it is doing, when it
   * started, what it has accumulated and how urgent it is.
   */
  @HegelTest
  void anInstanceSurvivesTheTrip(TestCase tc) {
    WorkflowInstance before = instance(tc, "inst");

    AvroWorkflowInstance sent = AvroTypeFactory.getAvroWorkflowInstance(before);
    WorkflowInstance after = AvroTypeFactory.getWorkflowInstance(sent);

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
    assertNotNull(after.getPriority(),
        "the instance came back with no priority");
    assertEquals(before.getPriority(), after.getPriority(),
        "the priority changed");
  }

  /**
   * A page of instances survives the trip, including how large a page it is.
   * A client reads the page size to know whether it has been given a full page
   * and to size the next request; a page that arrives claiming a size of -1
   * describes a page nobody asked for.
   */
  @HegelTest
  void aPageOfInstancesSurvivesTheTrip(TestCase tc) {
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

    AvroWorkflowInstancePage sent =
        AvroTypeFactory.getAvroWorkflowInstancePage(before);
    WorkflowInstancePage after = AvroTypeFactory.getWorkflowInstancePage(sent);

    assertEquals(pageNum, after.getPageNum(), "the page number changed");
    assertEquals(totalPages, after.getTotalPages(),
        "the total page count changed");
    assertEquals(instanceCount, after.getPageWorkflows().size(),
        "the page came back holding a different number of instances");
    assertEquals(pageSize, after.getPageSize(),
        "a page of " + pageSize + " came back claiming " + after.getPageSize());
  }

  /**
   * Metadata survives the trip both ways round, keys and every value under
   * them. A workflow's shared context is how one task tells the next what it
   * found, and a multi-valued key is the ordinary case, not a corner one.
   */
  @HegelTest
  void metadataSurvivesTheTrip(TestCase tc) {
    int keys = tc.draw(integers().min(0).max(4), "keyCount");
    Metadata before = new Metadata();
    for (int i = 0; i < keys; i++) {
      String key = word(tc, "key" + i);
      int values = tc.draw(integers().min(1).max(3), "valueCount" + i);
      List<String> drawn = new Vector<>(values);
      for (int v = 0; v < values; v++) {
        drawn.add(word(tc, "value" + i + "x" + v));
      }
      before.addMetadata(key, drawn);
    }

    Metadata after = AvroTypeFactory.getMetadata(
        AvroTypeFactory.getAvroMetadata(before));

    // As a set: the wire format is a map, so the order keys are listed in is
    // not something it carries and not something a caller can read anything
    // into.
    assertEquals(new java.util.HashSet<>(before.getAllKeys()),
        new java.util.HashSet<>(after.getAllKeys()),
        "the metadata came back under different keys");
    for (String key : before.getAllKeys()) {
      assertEquals(before.getAllMetadata(key), after.getAllMetadata(key),
          "the values under " + key + " changed");
    }
  }
}
