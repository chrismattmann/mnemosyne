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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Vector;

/**
 * Properties of {@link WorkflowTask}, the model of a single step of work.
 *
 * <p>A task carries two lists of conditions, and the class keeps a deprecated
 * single-list view of them for callers written before the split. Those two
 * views have to tell the same story, because the engine reads the new one and
 * every struct factory in this module still writes the old one.
 */
class WorkflowTaskPropertyTest {

  private static String word(TestCase tc, String label) {
    return tc.draw(text().minSize(1).maxSize(6).categories("Lu", "Ll"), label);
  }

  private static WorkflowCondition condition(String id) {
    return new WorkflowCondition(id + "Name", id, id + "Class", 0);
  }

  /** Draws a list of distinctly identified conditions. */
  private static List<WorkflowCondition> conditions(TestCase tc, String label,
      int count) {
    List<WorkflowCondition> drawn = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      drawn.add(condition(label + i));
    }
    return drawn;
  }

  /**
   * A task built by the default constructor is usable straight away: it has a
   * configuration to read properties from and empty condition lists rather
   * than null ones. Every reader in this module builds a task this way and
   * then fills it in field by field, so a null here is a null pointer later.
   */
  @HegelTest
  void aDefaultTaskIsReadyToBeFilledIn(TestCase tc) {
    tc.note("no input: the default constructor's promises are unconditional");

    WorkflowTask task = new WorkflowTask();

    assertNotNull(task.getTaskConfig(), "a new task has no configuration");
    assertNotNull(task.getPreConditions(), "a new task has null pre-conditions");
    assertNotNull(task.getPostConditions(),
        "a new task has null post-conditions");
    assertNotNull(task.getRequiredMetFields(),
        "a new task has null required met fields");
    assertTrue(task.getPreConditions().isEmpty(),
        "a new task already has pre-conditions");
    assertTrue(task.getPostConditions().isEmpty(),
        "a new task already has post-conditions");
    assertNotNull(task.getConditions(),
        "a new task has no deprecated condition view");
  }

  /**
   * The deprecated single-list view of a task's conditions is its
   * pre-conditions followed by its post-conditions, and nothing else. The
   * XML-RPC struct factory sends this list and nothing else, so a condition
   * missing from it never reaches the other end of the wire.
   */
  @HegelTest
  void theDeprecatedViewIsThePreConditionsThenThePostConditions(TestCase tc) {
    int preCount = tc.draw(integers().min(0).max(3), "preCount");
    int postCount = tc.draw(integers().min(0).max(3), "postCount");
    List<WorkflowCondition> pre = conditions(tc, "pre", preCount);
    List<WorkflowCondition> post = conditions(tc, "post", postCount);
    WorkflowTask task = new WorkflowTask();
    task.setPreConditions(pre);
    task.setPostConditions(post);

    List<?> view = task.getConditions();

    assertEquals(preCount + postCount, view.size(),
        "the deprecated view holds " + view.size() + " of "
            + (preCount + postCount) + " conditions");
    for (int i = 0; i < preCount; i++) {
      assertEquals(pre.get(i), view.get(i),
          "pre-condition " + i + " is not where the view puts it");
    }
    for (int i = 0; i < postCount; i++) {
      assertEquals(post.get(i), view.get(preCount + i),
          "post-condition " + i + " is not where the view puts it");
    }
  }

  /**
   * Changing the deprecated view does not change the task. It is built fresh
   * on every call, and a caller that appends to what it was handed — which is
   * what a caller written against the old single-list API would do — must not
   * be quietly editing the task's pre-conditions.
   */
  @HegelTest
  void theDeprecatedViewIsACopy(TestCase tc) {
    int preCount = tc.draw(integers().min(0).max(3), "preCount");
    WorkflowTask task = new WorkflowTask();
    task.setPreConditions(conditions(tc, "pre", preCount));
    task.setPostConditions(new Vector<WorkflowCondition>());

    List view = task.getConditions();
    view.add(condition("addedByTheCaller"));

    assertEquals(preCount, task.getPreConditions().size(),
        "a condition added to the view leaked into the task");
  }

  /**
   * Setting conditions through the deprecated setter sets the pre-conditions,
   * as the class documents, and leaves the post-conditions where they were.
   * Readers built before the split call this setter, and a workflow whose post
   * conditions were dropped by it would run its cleanup steps as guards.
   */
  @HegelTest
  void theDeprecatedSetterSetsPreConditionsAndLeavesPostConditionsAlone(
      TestCase tc) {
    int postCount = tc.draw(integers().min(0).max(3), "postCount");
    int newCount = tc.draw(integers().min(0).max(3), "newCount");
    List<WorkflowCondition> post = conditions(tc, "post", postCount);
    List<WorkflowCondition> replacement = conditions(tc, "new", newCount);
    WorkflowTask task = new WorkflowTask();
    task.setPostConditions(post);

    task.setConditions(replacement);

    assertEquals(replacement, task.getPreConditions(),
        "the deprecated setter did not set the pre-conditions");
    assertEquals(post, task.getPostConditions(),
        "the deprecated setter disturbed the post-conditions");
  }

  /**
   * The deprecated constructor keeps the instance class name and the order it
   * is given. Those two arguments are what say which code runs and when; a
   * task built through this constructor and then executed has to run the class
   * the caller named.
   */
  @HegelTest
  void theDeprecatedConstructorKeepsTheClassNameAndOrderItIsGiven(
      TestCase tc) {
    String instanceClass = word(tc, "instanceClass");
    int order = tc.draw(integers().min(0).max(20), "order");
    int preCount = tc.draw(integers().min(0).max(3), "preCount");
    List<WorkflowCondition> pre = conditions(tc, "pre", preCount);

    WorkflowTask task = new WorkflowTask(word(tc, "id"), word(tc, "name"),
        new WorkflowTaskConfiguration(), pre, instanceClass, order);

    assertEquals(instanceClass, task.getTaskInstanceClassName(),
        "the task will run [" + task.getTaskInstanceClassName()
            + "] rather than the class it was constructed with");
    assertEquals(order, task.getOrder(),
        "the task was constructed at order " + order + " but reports "
            + task.getOrder());
    assertEquals(pre, task.getPreConditions(),
        "the conditions the constructor was given are not the task's");
  }

  /**
   * Everything set on a task reads back unchanged. A task is a plain record
   * that a repository fills in from a file and an engine reads while running,
   * and there is nothing between the two but these accessors.
   */
  @HegelTest
  void whateverIsSetOnATaskReadsBack(TestCase tc) {
    String id = word(tc, "id");
    String name = word(tc, "name");
    String instanceClass = word(tc, "instanceClass");
    int order = tc.draw(integers().min(-1).max(20), "order");
    long startMillis = tc.draw(integers().min(0).max(100000), "start");
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty(name, id);
    List<String> requiredMetFields = List.of(name, id);
    WorkflowTask task = new WorkflowTask();

    task.setTaskId(id);
    task.setTaskName(name);
    task.setTaskInstanceClassName(instanceClass);
    task.setOrder(order);
    task.setTaskConfig(config);
    task.setRequiredMetFields(requiredMetFields);
    task.setStartDate(new Date(startMillis));
    task.setEndDate(new Date(startMillis + 1));

    assertEquals(id, task.getTaskId(), "the task id changed");
    assertEquals(name, task.getTaskName(), "the task name changed");
    assertEquals(instanceClass, task.getTaskInstanceClassName(),
        "the instance class name changed");
    assertEquals(order, task.getOrder(), "the order changed");
    assertEquals(id, task.getTaskConfig().getProperty(name),
        "the configuration changed");
    assertEquals(requiredMetFields, task.getRequiredMetFields(),
        "the required met fields changed");
    assertEquals(new Date(startMillis), task.getStartDate(),
        "the start date changed");
    assertEquals(new Date(startMillis + 1), task.getEndDate(),
        "the end date changed");
  }
}
