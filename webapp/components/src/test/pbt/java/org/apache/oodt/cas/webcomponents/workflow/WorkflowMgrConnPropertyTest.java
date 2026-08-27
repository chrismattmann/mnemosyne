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

package org.apache.oodt.cas.webcomponents.workflow;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * Properties of the serialisable copies {@link WorkflowMgrConn} hands to
 * Wicket.
 *
 * <p>Every workflow object a monitor page shows has been through one of these
 * copy constructors, and the copy is what the page keeps between requests. A
 * field the copy drops, or a copy the session store cannot hold, is a page that
 * cannot show what the workflow manager told it.
 *
 * <p>The connection is built against a URL that cannot be parsed, so nothing
 * here contacts a workflow manager; only the copy constructors are exercised.
 */
class WorkflowMgrConnPropertyTest {

  private static Generator<String> word() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  /** A connection with no workflow manager behind it. */
  private static WorkflowMgrConn disconnected() {
    return new WorkflowMgrConn("this is not a url");
  }

  private static WorkflowCondition drawCondition(TestCase tc, String suffix) {
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    config.addConfigProperty("Key" + suffix, tc.draw(word(), "condConfig" + suffix));

    WorkflowCondition condition = new WorkflowCondition();
    condition.setConditionName(tc.draw(word(), "condName" + suffix));
    condition.setConditionId(tc.draw(word(), "condId" + suffix));
    condition.setConditionInstanceClassName(tc.draw(word(), "condClass" + suffix));
    condition.setOrder(tc.draw(integers().min(0).max(50), "condOrder" + suffix));
    condition.setTimeoutSeconds(tc.draw(longs().min(0).max(3600), "condTimeout" + suffix));
    condition.setOptional(tc.draw(booleans(), "condOptional" + suffix));
    condition.setCondConfig(config);
    return condition;
  }

  private static WorkflowTask drawTask(TestCase tc) {
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("Key", tc.draw(word(), "taskConfig"));

    List<WorkflowCondition> preConditions = new Vector<WorkflowCondition>();
    preConditions.add(drawCondition(tc, "Pre"));

    WorkflowTask task = new WorkflowTask();
    task.setTaskId(tc.draw(word(), "taskId"));
    task.setTaskName(tc.draw(word(), "taskName"));
    task.setTaskInstanceClassName(tc.draw(word(), "taskClass"));
    task.setOrder(tc.draw(integers().min(0).max(50), "taskOrder"));
    task.setPreConditions(preConditions);
    task.setPostConditions(new Vector<WorkflowCondition>());
    task.setRequiredMetFields(new ArrayList<String>(tc.draw(lists(word()).maxSize(3), "metFields")));
    task.setTaskConfig(config);
    return task;
  }

  /**
   * The copy of a task describes the same task. The task viewer reads its id,
   * name, class, order, conditions and configuration off this copy and off
   * nothing else.
   */
  @HegelTest
  void theCopyOfATaskDescribesTheSameTask(TestCase tc) {
    WorkflowTask original = drawTask(tc);

    WorkflowTask copy = disconnected().new SerializableWorkflowTask(original);

    assertEquals(original.getTaskId(), copy.getTaskId(), "taskId");
    assertEquals(original.getTaskName(), copy.getTaskName(), "taskName");
    assertEquals(
        original.getTaskInstanceClassName(), copy.getTaskInstanceClassName(), "instance class");
    assertEquals(original.getOrder(), copy.getOrder(), "order");
    assertEquals(original.getRequiredMetFields(), copy.getRequiredMetFields(), "required fields");
    assertEquals(
        original.getPreConditions().size(), copy.getPreConditions().size(), "pre-conditions");
    assertEquals(
        original.getTaskConfig().getProperties(),
        copy.getTaskConfig().getProperties(),
        "task configuration");
  }

  /**
   * The copy of a condition describes the same condition. The condition viewer
   * addresses a condition by its id, and a workflow's behaviour depends on
   * whether a condition is optional and on how long it may take, so all three
   * have to survive the copy.
   */
  @HegelTest
  void theCopyOfAConditionDescribesTheSameCondition(TestCase tc) {
    WorkflowCondition original = drawCondition(tc, "");

    WorkflowCondition copy = disconnected().new SerializableWorkflowCondition(original);

    assertEquals(original.getConditionName(), copy.getConditionName(), "conditionName");
    assertEquals(
        original.getConditionInstanceClassName(),
        copy.getConditionInstanceClassName(),
        "instance class");
    assertEquals(original.getOrder(), copy.getOrder(), "order");
    assertEquals(
        original.getTaskConfig().getProperties(),
        copy.getTaskConfig().getProperties(),
        "condition configuration");
    assertEquals(original.getConditionId(), copy.getConditionId(), "conditionId");
    assertEquals(original.getTimeoutSeconds(), copy.getTimeoutSeconds(), "timeoutSeconds");
    assertEquals(original.isOptional(), copy.isOptional(), "optional");
  }

  /**
   * The copy of a task survives a trip through the session store. That is the
   * only reason these copies exist: Wicket writes a page's models out between
   * requests, and a task that comes back empty is a task viewer that comes back
   * empty.
   */
  @HegelTest
  void theCopyOfATaskSurvivesATripThroughTheSessionStore(TestCase tc) throws Exception {
    WorkflowTask original = drawTask(tc);
    WorkflowTask copy = disconnected().new SerializableWorkflowTask(original);

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ObjectOutputStream out = new ObjectOutputStream(bytes);
    try {
      out.writeObject(copy);
    } finally {
      out.close();
    }

    ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    WorkflowTask restored;
    try {
      restored = (WorkflowTask) in.readObject();
    } finally {
      in.close();
    }

    assertNotNull(restored, "nothing came back out of the session store");
    assertEquals(original.getTaskId(), restored.getTaskId(), "taskId");
    assertEquals(original.getTaskName(), restored.getTaskName(), "taskName");
    assertEquals(original.getOrder(), restored.getOrder(), "order");
  }
}
