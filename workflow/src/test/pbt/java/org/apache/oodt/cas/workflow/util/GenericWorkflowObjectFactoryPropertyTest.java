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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import org.apache.oodt.cas.workflow.examples.FalseCondition;
import org.apache.oodt.cas.workflow.examples.NoOpTask;
import org.apache.oodt.cas.workflow.examples.TrueCondition;
import org.apache.oodt.cas.workflow.structs.PrioritySorter;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;

/**
 * Properties of {@link GenericWorkflowObjectFactory}.
 *
 * <p>Two jobs live in this class. One is turning the class names written in a
 * workflow file into the objects that do the work, which is how every task and
 * condition in OODT is reached. The other is copying a workflow model, which
 * the class documents as producing an exact copy — a model an engine can hand
 * to one run without the next run seeing its edits.
 */
class GenericWorkflowObjectFactoryPropertyTest {

  /** Classes that really are on the classpath and really do implement the interface. */
  private static final List<String> CONDITION_CLASSES = List.of(
      TrueCondition.class.getName(), FalseCondition.class.getName());

  /** Names of nothing at all, in the shapes a mistyped workflow file produces. */
  private static final List<String> ABSENT_CLASSES = List.of("", "NotAClass",
      "org.apache.oodt.cas.workflow.examples.NoSuchTask", "  ");

  private static String word(TestCase tc, String label) {
    return tc.draw(text().minSize(1).maxSize(6).categories("Lu", "Ll"), label);
  }

  private static WorkflowCondition condition(TestCase tc, String label) {
    WorkflowCondition condition = new WorkflowCondition(word(tc, label + "Name"),
        word(tc, label + "Id"), word(tc, label + "Class"),
        tc.draw(integers().min(-1).max(9), label + "Order"));
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    config.addConfigProperty(word(tc, label + "PropName"),
        word(tc, label + "PropValue"));
    condition.setCondConfig(config);
    return condition;
  }

  private static WorkflowTask task(TestCase tc, String label) {
    WorkflowTask task = new WorkflowTask();
    task.setTaskId(word(tc, label + "Id"));
    task.setTaskName(word(tc, label + "Name"));
    task.setTaskInstanceClassName(word(tc, label + "Class"));
    task.setOrder(tc.draw(integers().min(-1).max(9), label + "Order"));
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty(word(tc, label + "PropName"),
        word(tc, label + "PropValue"));
    task.setTaskConfig(config);
    List<WorkflowCondition> pre = new ArrayList<>();
    int conditions = tc.draw(integers().min(0).max(2), label + "CondCount");
    for (int i = 0; i < conditions; i++) {
      pre.add(condition(tc, label + "Cond" + i));
    }
    task.setPreConditions(pre);
    task.setPostConditions(new Vector<WorkflowCondition>());
    List<String> required = new ArrayList<>();
    int metFields = tc.draw(integers().min(0).max(2), label + "MetCount");
    for (int i = 0; i < metFields; i++) {
      required.add(word(tc, label + "Met" + i));
    }
    task.setRequiredMetFields(required);
    return task;
  }

  private static Workflow workflow(TestCase tc) {
    int taskCount = tc.draw(integers().min(0).max(3), "taskCount");
    List<WorkflowTask> tasks = new ArrayList<>(taskCount);
    for (int i = 0; i < taskCount; i++) {
      tasks.add(task(tc, "task" + i));
    }
    return new Workflow(word(tc, "name"), word(tc, "id"), tasks,
        new Vector<WorkflowCondition>(), new Vector<WorkflowCondition>());
  }

  /**
   * A class name that names a real condition class produces an instance of it,
   * and a fresh one each time. A condition instance may hold state — the
   * example that counts its refusals does — so two declarations naming the
   * same class must not end up sharing one object.
   */
  @HegelTest
  void namingAConditionClassBuildsOneOfThatClass(TestCase tc) {
    String className = tc.draw(sampledFrom(CONDITION_CLASSES), "className");

    WorkflowConditionInstance first = GenericWorkflowObjectFactory
        .getConditionObjectFromClassName(className);
    WorkflowConditionInstance second = GenericWorkflowObjectFactory
        .getConditionObjectFromClassName(className);

    assertNotNull(first, className + " could not be built");
    assertEquals(className, first.getClass().getName(),
        "something other than " + className + " was built");
    assertNotSame(first, second, className + " was handed out twice");
  }

  /**
   * A name that is not a class produces nothing rather than failing. A workflow
   * file is written by hand and may name a class that was never deployed; the
   * manager logs that and carries on with the rest of the model.
   */
  @HegelTest
  void namingSomethingThatIsNotAClassProducesNothing(TestCase tc) {
    String className = tc.draw(sampledFrom(ABSENT_CLASSES), "className");

    assertNull(GenericWorkflowObjectFactory.getConditionObjectFromClassName(
        className), className + " built a condition out of nothing");
    assertNull(GenericWorkflowObjectFactory.getTaskObjectFromClassName(
        className), className + " built a task out of nothing");
    assertNull(GenericWorkflowObjectFactory.getPrioritySorterFromClassName(
        className), className + " built a sorter out of nothing");
    assertNull(GenericWorkflowObjectFactory.getConditionObjectFromClassName(
        null), "a condition was built from no name at all");
    assertNull(GenericWorkflowObjectFactory.getTaskObjectFromClassName(null),
        "a task was built from no name at all");
    assertNull(GenericWorkflowObjectFactory.getPrioritySorterFromClassName(
        null), "a sorter was built from no name at all");
  }

  /**
   * The task and sorter lookups behave as the condition lookup does for names
   * that are real classes. These three are how the whole of a workflow model's
   * behaviour is reached from the strings in its file.
   */
  @HegelTest
  void namingATaskOrSorterClassBuildsOneOfThatClass(TestCase tc) {
    tc.note("no input: these two classes are what the module ships");

    WorkflowTaskInstance task = GenericWorkflowObjectFactory
        .getTaskObjectFromClassName(NoOpTask.class.getName());
    PrioritySorter sorter = GenericWorkflowObjectFactory
        .getPrioritySorterFromClassName(
            org.apache.oodt.cas.workflow.structs.HighestPrioritySorter.class
                .getName());

    assertNotNull(task, "the no-op task could not be built");
    assertEquals(NoOpTask.class, task.getClass(),
        "something other than the no-op task was built");
    assertNotNull(sorter, "the highest-priority sorter could not be built");
    assertEquals(
        org.apache.oodt.cas.workflow.structs.HighestPrioritySorter.class,
        sorter.getClass(), "something other than that sorter was built");
  }

  /**
   * A copied workflow carries the original's identity and tasks, and is a
   * separate object: editing the copy's task list must not reach the original,
   * since the point of copying a model is to hand one run a model of its own.
   */
  @HegelTest
  void aCopiedWorkflowMatchesTheOriginalAndIsSeparateFromIt(TestCase tc) {
    Workflow original = workflow(tc);

    Workflow copy = GenericWorkflowObjectFactory.copyWorkflow(original);

    assertNotNull(copy, "copying a workflow produced nothing");
    assertNotSame(original, copy, "the copy is the original");
    assertEquals(original.getName(), copy.getName(), "the name changed");
    assertEquals(original.getId(), copy.getId(), "the id changed");
    assertEquals(original.getTasks().size(), copy.getTasks().size(),
        "the copy runs a different number of tasks");
    for (int i = 0; i < original.getTasks().size(); i++) {
      assertNotSame(original.getTasks().get(i), copy.getTasks().get(i),
          "task " + i + " of the copy is the original's task");
      assertEquals(original.getTasks().get(i).getTaskId(),
          copy.getTasks().get(i).getTaskId(),
          "task " + i + " came back with a different id");
    }

    copy.getTasks().clear();
    assertTrue(copy.getTasks().isEmpty(), "clearing the copy did nothing");
    assertEquals(original.getTasks().size(),
        GenericWorkflowObjectFactory.copyWorkflow(original).getTasks().size(),
        "emptying the copy emptied the original");
  }

  /**
   * A copied task would run the same work, guarded the same way, on the same
   * metadata. Its required metadata fields are part of that: the engine
   * refuses to start a task whose required fields are missing, so a copy that
   * requires nothing is a copy that runs when the original would not have.
   */
  @HegelTest
  void aCopiedTaskWouldDoTheSameWork(TestCase tc) {
    WorkflowTask original = task(tc, "task");

    WorkflowTask copy = GenericWorkflowObjectFactory.copyTask(original);

    assertEquals(original.getTaskId(), copy.getTaskId(), "the task id changed");
    assertEquals(original.getTaskName(), copy.getTaskName(),
        "the task name changed");
    assertEquals(original.getTaskInstanceClassName(),
        copy.getTaskInstanceClassName(),
        "the copy would run a different class");
    assertEquals(original.getOrder(), copy.getOrder(), "the order changed");
    assertEquals(original.getTaskConfig().getProperties(),
        copy.getTaskConfig().getProperties(), "the configuration changed");
    assertEquals(original.getPreConditions().size(),
        copy.getPreConditions().size(),
        "the copy is guarded by a different number of conditions");
    assertEquals(original.getRequiredMetFields(), copy.getRequiredMetFields(),
        "the copy requires " + copy.getRequiredMetFields() + " rather than "
            + original.getRequiredMetFields());
  }

  /**
   * A copied condition is the same condition. Its id is how everything else in
   * a workflow model refers to it, and its configuration is what it actually
   * checks; a copy without them is a differently-behaving condition wearing
   * the same name.
   */
  @HegelTest
  void aCopiedConditionIsTheSameCondition(TestCase tc) {
    WorkflowCondition original = condition(tc, "cond");

    WorkflowCondition copy = GenericWorkflowObjectFactory.copyCondition(
        original);

    assertNotSame(original, copy, "the copy is the original");
    assertEquals(original.getConditionName(), copy.getConditionName(),
        "the condition name changed");
    assertEquals(original.getConditionInstanceClassName(),
        copy.getConditionInstanceClassName(),
        "the copy would run a different class");
    assertEquals(original.getOrder(), copy.getOrder(), "the order changed");
    assertEquals(original.getConditionId(), copy.getConditionId(),
        "the copy of condition [" + original.getConditionId()
            + "] came back identified as [" + copy.getConditionId() + "]");
    assertEquals(original.getCondConfig().getProperties(),
        copy.getCondConfig().getProperties(),
        "the copy checks something else");
  }

  /**
   * Copying nothing gives nothing, and copying a list copies each of its
   * members. The list forms are what a repository calls when it hands out
   * every workflow it knows, and it may know none.
   */
  @HegelTest
  void copyingNothingGivesNothingAndCopyingAListCopiesEachMember(TestCase tc) {
    int count = tc.draw(integers().min(0).max(3), "count");
    List<Workflow> workflows = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      workflows.add(workflow(tc));
    }

    List copies = GenericWorkflowObjectFactory.copyWorkflows(workflows);

    assertNull(GenericWorkflowObjectFactory.copyWorkflows(null),
        "copying no workflows produced a list");
    assertNull(GenericWorkflowObjectFactory.copyTasks(null),
        "copying no tasks produced a list");
    assertNull(GenericWorkflowObjectFactory.copyConditions(null),
        "copying no conditions produced a list");
    assertNotNull(copies, "copying a list of workflows produced nothing");
    assertEquals(count, copies.size(),
        "a list of " + count + " workflows copied to " + copies.size());
    for (int i = 0; i < count; i++) {
      assertNotSame(workflows.get(i), copies.get(i),
          "workflow " + i + " was not copied");
      assertEquals(workflows.get(i).getId(), ((Workflow) copies.get(i)).getId(),
          "workflow " + i + " came back with a different id");
    }
  }
}
