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
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Properties of {@link XmlStructFactory}, which turns the workflow policy
 * files into the model the manager serves.
 *
 * <p>The XML repository reads three files — conditions, tasks, workflows — and
 * each one is turned into structs by this class. Tasks and workflows refer to
 * conditions and tasks by identifier, so what the factory does with an
 * identifier it cannot resolve matters as much as what it does with one it
 * can.
 *
 * <p>The elements here are built in memory rather than parsed from a file,
 * which is what lets these properties state the general case rather than the
 * shapes the sample policy in the source tree happens to contain.
 */
class XmlStructFactoryPropertyTest {

  /** A small alphabet of identifiers, so that references resolve and miss. */
  private static final List<String> IDS =
      List.of("urn:oodt:one", "urn:oodt:two", "urn:oodt:three");

  private static Document document() throws ParserConfigurationException {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .newDocument();
  }

  /** A value safe to write into an attribute, and never a shell variable. */
  private static String word(TestCase tc, String label) {
    return "v" + tc.draw(integers().min(0).max(999), label);
  }

  /** Appends a {@code <configuration>} block of the given properties. */
  private static void addConfiguration(Document document, Element parent,
      Map<String, String> properties) {
    Element configuration = document.createElement("configuration");
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      Element property = document.createElement("property");
      property.setAttribute("name", entry.getKey());
      property.setAttribute("value", entry.getValue());
      configuration.appendChild(property);
    }
    parent.appendChild(configuration);
  }

  private static Map<String, String> drawProperties(TestCase tc, String label) {
    int count = tc.draw(integers().min(0).max(3), label + "Count");
    Map<String, String> properties = new HashMap<>();
    for (int i = 0; i < count; i++) {
      properties.put("p" + i, word(tc, label + "Value" + i));
    }
    return properties;
  }

  /**
   * A condition element becomes the condition it describes. Everything on it
   * decides how the engine treats a task guarded by it: the class that decides
   * the answer, how long it will wait for one and whether it may proceed
   * without it.
   */
  @HegelTest
  void aConditionElementBecomesThatCondition(TestCase tc) throws Exception {
    Document document = document();
    String id = tc.draw(sampledFrom(IDS), "id");
    String name = word(tc, "name");
    String clazz = word(tc, "class");
    boolean statesTimeout = tc.draw(booleans(), "statesTimeout");
    long timeout = tc.draw(integers().min(0).max(600), "timeout");
    boolean optional = tc.draw(booleans(), "optional");
    Map<String, String> properties = drawProperties(tc, "prop");
    Element element = document.createElement("condition");
    element.setAttribute("id", id);
    element.setAttribute("name", name);
    element.setAttribute("class", clazz);
    element.setAttribute("optional", String.valueOf(optional));
    if (statesTimeout) {
      element.setAttribute("timeout", String.valueOf(timeout));
    }
    addConfiguration(document, element, properties);

    WorkflowCondition condition = XmlStructFactory.getWorkflowCondition(
        element);

    assertEquals(id, condition.getConditionId(), "the condition id changed");
    assertEquals(name, condition.getConditionName(),
        "the condition name changed");
    assertEquals(clazz, condition.getConditionInstanceClassName(),
        "the condition would run a different class");
    assertEquals(optional, condition.isOptional(),
        "whether the condition is optional changed");
    assertEquals(statesTimeout ? timeout : -1L, condition.getTimeoutSeconds(),
        "a condition that states no timeout should wait indefinitely");
    assertEquals(new Properties(), difference(properties,
        condition.getCondConfig().getProperties()),
        "the condition's configuration is not the one written");
  }

  /** The properties of {@code declared} that {@code actual} does not carry. */
  private static Properties difference(Map<String, String> declared,
      Properties actual) {
    Properties missing = new Properties();
    for (Map.Entry<String, String> entry : declared.entrySet()) {
      if (!entry.getValue().equals(actual.getProperty(entry.getKey()))) {
        missing.setProperty(entry.getKey(), entry.getValue());
      }
    }
    return missing;
  }

  /**
   * A task element becomes the task it describes, with the conditions it names
   * resolved from those already read, in the order it names them and numbered
   * from one. A task names conditions defined in another file entirely, and
   * the order is what the engine checks them in.
   */
  @HegelTest
  void aTaskElementBecomesThatTaskWithItsConditionsResolved(TestCase tc)
      throws Exception {
    Document document = document();
    String id = tc.draw(sampledFrom(IDS), "id");
    String name = word(tc, "name");
    String clazz = word(tc, "class");
    int referenced = tc.draw(integers().min(0).max(3), "referenced");
    Map<String, WorkflowCondition> known = new HashMap<>();
    for (String knownId : IDS) {
      WorkflowCondition condition = new WorkflowCondition(knownId + "Name",
          knownId, knownId + "Class", 0);
      known.put(knownId, condition);
    }
    Element element = document.createElement("task");
    element.setAttribute("id", id);
    element.setAttribute("name", name);
    element.setAttribute("class", clazz);
    Element conditions = document.createElement("conditions");
    List<String> referencedIds = new ArrayList<>();
    for (int i = 0; i < referenced; i++) {
      String conditionId = tc.draw(sampledFrom(IDS), "conditionId" + i);
      referencedIds.add(conditionId);
      Element condition = document.createElement("condition");
      condition.setAttribute("id", conditionId);
      conditions.appendChild(condition);
    }
    element.appendChild(conditions);

    WorkflowTask task = XmlStructFactory.getWorkflowTask(element, known);

    assertEquals(id, task.getTaskId(), "the task id changed");
    assertEquals(name, task.getTaskName(), "the task name changed");
    assertEquals(clazz, task.getTaskInstanceClassName(),
        "the task would run a different class");
    List<WorkflowCondition> resolved = task.getPreConditions();
    assertEquals(referencedIds.size(), resolved.size(),
        "the task names " + referencedIds.size()
            + " conditions but came back with " + resolved.size());
    for (int i = 0; i < resolved.size(); i++) {
      assertEquals(referencedIds.get(i), resolved.get(i).getConditionId(),
          "condition " + i + " is not the one the task names");
      assertEquals(known.get(referencedIds.get(i))
          .getConditionInstanceClassName(),
          resolved.get(i).getConditionInstanceClassName(),
          "condition " + i + " would run a different class");
      assertEquals(i + 1, resolved.get(i).getOrder(),
          "condition " + i + " is checked out of order");
    }
  }

  /**
   * A task naming a condition nobody defined comes back without it rather than
   * with a hole in its list. The three policy files are read separately, so a
   * task can name a condition that was never declared, and a null in the list
   * would be dereferenced the first time the task ran.
   */
  @HegelTest
  void aTaskNamingAnUndefinedConditionSimplyDoesNotHaveIt(TestCase tc)
      throws Exception {
    Document document = document();
    int referenced = tc.draw(integers().min(1).max(3), "referenced");
    Map<String, WorkflowCondition> known = new HashMap<>();
    String defined = tc.draw(sampledFrom(IDS), "defined");
    known.put(defined,
        new WorkflowCondition(defined, defined, defined + "Class", 0));
    Element element = document.createElement("task");
    element.setAttribute("id", tc.draw(sampledFrom(IDS), "id"));
    Element conditions = document.createElement("conditions");
    int expected = 0;
    for (int i = 0; i < referenced; i++) {
      String conditionId = tc.draw(sampledFrom(IDS), "conditionId" + i);
      if (conditionId.equals(defined)) {
        expected++;
      }
      Element condition = document.createElement("condition");
      condition.setAttribute("id", conditionId);
      conditions.appendChild(condition);
    }
    element.appendChild(conditions);

    WorkflowTask task = XmlStructFactory.getWorkflowTask(element, known);

    assertEquals(expected, task.getPreConditions().size(),
        "only the defined conditions should be attached");
    for (WorkflowCondition condition : task.getPreConditions()) {
      assertNotNull(condition, "the task was given a condition that is null");
    }
  }

  /**
   * The metadata fields a task requires are the ones it lists, and a task
   * listing none requires nothing. The engine refuses to start a task whose
   * required fields are absent from the workflow's context.
   */
  @HegelTest
  void aTasksRequiredFieldsAreTheOnesItLists(TestCase tc) throws Exception {
    Document document = document();
    int count = tc.draw(integers().min(0).max(4), "count");
    Element element = document.createElement("task");
    element.setAttribute("id", tc.draw(sampledFrom(IDS), "id"));
    List<String> listed = new ArrayList<>(count);
    if (count > 0) {
      Element required = document.createElement("requiredMetFields");
      for (int i = 0; i < count; i++) {
        String field = word(tc, "field" + i) + i;
        listed.add(field);
        Element metField = document.createElement("metfield");
        metField.setAttribute("name", field);
        required.appendChild(metField);
      }
      element.appendChild(required);
    }

    WorkflowTask task = XmlStructFactory.getWorkflowTask(element,
        new HashMap<String, WorkflowCondition>());

    if (count > 0) {
      assertEquals(listed, task.getRequiredMetFields(),
          "the task requires something other than what it lists");
    } else {
      assertTrue(task.getRequiredMetFields() == null
          || task.getRequiredMetFields().isEmpty(),
          "a task listing no required fields requires "
              + task.getRequiredMetFields());
    }
  }

  /**
   * A configuration block becomes the properties it declares, one for one.
   * This is the whole of what a task or condition is configured with.
   */
  @HegelTest
  void aConfigurationBlockBecomesItsProperties(TestCase tc) throws Exception {
    Document document = document();
    Map<String, String> declared = drawProperties(tc, "prop");
    Element configuration = document.createElement("configuration");
    for (Map.Entry<String, String> entry : declared.entrySet()) {
      Element property = document.createElement("property");
      property.setAttribute("name", entry.getKey());
      property.setAttribute("value", entry.getValue());
      configuration.appendChild(property);
    }

    Properties properties = XmlStructFactory.getConfiguration(configuration);

    assertEquals(declared.size(), properties.size(),
        "the block declares " + declared.size() + " properties but produced "
            + properties.size());
    for (Map.Entry<String, String> entry : declared.entrySet()) {
      assertEquals(entry.getValue(), properties.getProperty(entry.getKey()),
          entry.getKey() + " did not come through");
    }
  }

  /**
   * Read as metadata, a configuration property with a delimiter becomes one
   * value per delimited part, and one without becomes a single value. A
   * packaged workflow declares a multi-valued property this way and nothing
   * else in the file distinguishes the two cases.
   */
  @HegelTest
  void aDelimitedPropertyBecomesOneValuePerPart(TestCase tc) throws Exception {
    Document document = document();
    int parts = tc.draw(integers().min(1).max(4), "parts");
    boolean delimited = tc.draw(booleans(), "delimited");
    List<String> values = new ArrayList<>(parts);
    for (int i = 0; i < parts; i++) {
      values.add(word(tc, "part" + i));
    }
    Element configuration = document.createElement("configuration");
    Element property = document.createElement("property");
    property.setAttribute("name", "aProperty");
    property.setAttribute("value", String.join(",", values));
    if (delimited) {
      property.setAttribute("delim", ",");
    }
    configuration.appendChild(property);

    Metadata metadata = XmlStructFactory.getConfigurationAsMetadata(
        configuration);

    if (delimited) {
      assertEquals(values, metadata.getAllMetadata("aProperty"),
          "the delimited property did not come apart into its parts");
    } else {
      assertEquals(List.of(String.join(",", values)),
          metadata.getAllMetadata("aProperty"),
          "an undelimited property was taken apart anyway");
    }
    assertNull(metadata.getMetadata("somethingElse"),
        "reading the block invented a property");
  }

  /**
   * A workflow element becomes the workflow it describes, running the tasks it
   * names in the order it names them. The order is the workflow: the
   * sequential engine runs task one, then task two.
   */
  @HegelTest
  void aWorkflowElementRunsTheTasksItNamesInOrder(TestCase tc)
      throws Exception {
    Document document = document();
    String id = tc.draw(sampledFrom(IDS), "id");
    String name = word(tc, "name");
    int referenced = tc.draw(integers().min(0).max(3), "referenced");
    Map<String, WorkflowTask> known = new HashMap<>();
    for (String knownId : IDS) {
      WorkflowTask task = new WorkflowTask();
      task.setTaskId(knownId);
      task.setTaskName(knownId + "Name");
      task.setTaskInstanceClassName(knownId + "Class");
      known.put(knownId, task);
    }
    Element element = document.createElement("workflow");
    element.setAttribute("id", id);
    element.setAttribute("name", name);
    Element tasks = document.createElement("tasks");
    List<String> referencedIds = new ArrayList<>();
    for (int i = 0; i < referenced; i++) {
      String taskId = tc.draw(sampledFrom(IDS), "taskId" + i);
      referencedIds.add(taskId);
      Element task = document.createElement("task");
      task.setAttribute("id", taskId);
      tasks.appendChild(task);
    }
    element.appendChild(tasks);

    Workflow workflow = XmlStructFactory.getWorkflow(element, known,
        new HashMap<String, WorkflowCondition>());

    assertEquals(id, workflow.getId(), "the workflow id changed");
    assertEquals(name, workflow.getName(), "the workflow name changed");
    if (referenced == 0) {
      assertTrue(workflow.getTasks() == null || workflow.getTasks().isEmpty(),
          "a workflow naming no tasks runs " + workflow.getTasks());
      return;
    }
    assertEquals(referencedIds.size(), workflow.getTasks().size(),
        "the workflow names " + referencedIds.size() + " tasks but runs "
            + workflow.getTasks().size());
    for (int i = 0; i < referencedIds.size(); i++) {
      WorkflowTask task = workflow.getTasks().get(i);
      assertEquals(referencedIds.get(i), task.getTaskId(),
          "task " + i + " is not the one the workflow names");
      assertEquals(known.get(referencedIds.get(i)).getTaskInstanceClassName(),
          task.getTaskInstanceClassName(),
          "task " + i + " would run a different class");
      assertEquals(i + 1, task.getOrder(),
          "task " + i + " is ordered " + task.getOrder());
    }
  }
}
