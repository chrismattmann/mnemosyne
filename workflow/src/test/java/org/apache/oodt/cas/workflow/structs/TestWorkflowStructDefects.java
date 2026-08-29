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

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.examples.CheckForMetadataKeys;
import org.apache.oodt.cas.workflow.util.GenericWorkflowObjectFactory;
import org.apache.oodt.cas.workflow.util.XmlRpcStructFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Items 18 and 20-25 of #134: values that are assembled and then quietly lost
 * between one representation and the next.
 */
public class TestWorkflowStructDefects {

  /** 18. Written to the wire at "priority", never read back. */
  @Test
  public void instancePrioritySurvivesTheXmlRpcRoundTrip() {
    WorkflowInstance instance = instanceWith(Priority.LOW, "inst-1");

    Map struct = XmlRpcStructFactory.getXmlRpcWorkflowInstance(instance);
    WorkflowInstance received = XmlRpcStructFactory.getWorkflowInstanceFromXmlRpc(struct);

    assertEquals(Priority.LOW.getValue(), received.getPriority().getValue(), 0.0);
  }

  @Test
  public void ahighPriorityAlsoSurvives() {
    WorkflowInstance instance = instanceWith(Priority.HIGH, "inst-2");

    WorkflowInstance received = XmlRpcStructFactory.getWorkflowInstanceFromXmlRpc(
        XmlRpcStructFactory.getXmlRpcWorkflowInstance(instance));

    assertEquals(Priority.HIGH.getValue(), received.getPriority().getValue(), 0.0);
  }

  /** 20. The declared execution type was overwritten with the node name. */
  @Test
  public void adeclaredExecutionTypeIsKept() throws Exception {
    Graph graph = new Graph(element("<workflow id=\"w1\" execution=\"sequential\"/>"),
        new Metadata());

    assertEquals("sequential", graph.getExecutionType());
  }

  @Test
  public void anodeWithNoExecutionAttributeStillUsesItsName() throws Exception {
    Graph graph = new Graph(element("<task id=\"t1\"/>"), new Metadata());

    assertEquals("task", graph.getExecutionType());
  }

  @Test
  public void aworkflowWithNoExecutionTypeStillFails() {
    try {
      new Graph(element("<workflow id=\"w1\"/>"), new Metadata());
      throw new AssertionError("expected a WorkflowException");
    } catch (Exception e) {
      assertTrue(String.valueOf(e.getMessage()).contains("missing execution type"));
    }
  }

  /** 21. A copy the javadoc calls exact, dropping the fields that gate a run. */
  @Test
  public void acopiedTaskKeepsTheFieldsThatDecideWhetherItRuns() {
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("t1");
    task.setRequiredMetFields(new Vector<String>(Arrays.asList("NeedsThis")));
    task.setStartDate(new Date(1000L));
    task.setEndDate(new Date(2000L));
    task.setPostConditions(new Vector<WorkflowCondition>());

    WorkflowTask copy = GenericWorkflowObjectFactory.copyTask(task);

    assertEquals(Arrays.asList("NeedsThis"), copy.getRequiredMetFields());
    assertEquals(new Date(1000L), copy.getStartDate());
    assertEquals(new Date(2000L), copy.getEndDate());
  }

  @Test
  public void acopiedConditionKeepsItsIdentityAndItsGates() {
    WorkflowCondition condition = new WorkflowCondition();
    condition.setConditionId("c1");
    condition.setConditionName("check");
    condition.setTimeoutSeconds(30L);
    condition.setOptional(true);
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    config.addConfigProperty("key", "value");
    condition.setCondConfig(config);

    WorkflowCondition copy = GenericWorkflowObjectFactory.copyCondition(condition);

    assertEquals("c1", copy.getConditionId());
    assertEquals(30L, copy.getTimeoutSeconds());
    assertTrue(copy.isOptional());
    assertNotNull(copy.getCondConfig());
    assertEquals("value", copy.getCondConfig().getProperty("key"));
  }

  /** 22. The deprecated constructor delegated with null and -1. */
  @Test
  public void thedeprecatedTaskConstructorKeepsItsArguments() {
    WorkflowTask task = new WorkflowTask("t1", "task one",
        new WorkflowTaskConfiguration(), new Vector<WorkflowCondition>(),
        "org.example.SomeTask", 7);

    assertEquals("org.example.SomeTask", task.getTaskInstanceClassName());
    assertEquals(7, task.getOrder());
  }

  /** 23. Wrapping sent every condition to preConditions. */
  @Test
  public void wrappingAworkflowKeepsItsPostConditions() {
    WorkflowCondition post = new WorkflowCondition();
    post.setConditionId("after");

    Workflow workflow = new Workflow();
    workflow.setName("w");
    workflow.setId("w1");
    workflow.setTasks(new Vector<WorkflowTask>());
    workflow.setPreConditions(new Vector<WorkflowCondition>());
    workflow.setPostConditions(new Vector<WorkflowCondition>(Arrays.asList(post)));

    ParentChildWorkflow wrapped = new ParentChildWorkflow(workflow);

    assertEquals(1, wrapped.getPostConditions().size());
    assertEquals("after", wrapped.getPostConditions().get(0).getConditionId());
  }

  /** 24. A missing property became a key literally named "null". */
  @Test
  public void amissingRequiredKeyListDoesNotWaitForAKeyNamedNull() {
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();

    assertTrue(new CheckForMetadataKeys().evaluate(new Metadata(), config));
  }

  @Test
  public void aconfiguredRequiredKeyIsStillChecked() {
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    config.addConfigProperty("reqMetKeys", "NeedsThis");

    Metadata without = new Metadata();
    Metadata with = new Metadata();
    with.addMetadata("NeedsThis", "yes");

    assertFalse(new CheckForMetadataKeys().evaluate(without, config));
    assertTrue(new CheckForMetadataKeys().evaluate(with, config));
  }

  /** 25. A task can reach getCurrentTask with a null id. */
  @Test
  public void ataskWithNoIdDoesNotBringDownGetCurrentTask() {
    List<WorkflowTask> tasks = new ArrayList<WorkflowTask>();
    tasks.add(new WorkflowTask());

    Workflow workflow = new Workflow();
    workflow.setTasks(tasks);

    WorkflowInstance instance = new WorkflowInstance();
    instance.setWorkflow(workflow);
    instance.setCurrentTaskId("t1");

    assertNull(instance.getCurrentTask());
  }

  @Test
  public void arealCurrentTaskIsStillFound() {
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("t1");
    List<WorkflowTask> tasks = new ArrayList<WorkflowTask>();
    tasks.add(new WorkflowTask());
    tasks.add(task);

    Workflow workflow = new Workflow();
    workflow.setTasks(tasks);

    WorkflowInstance instance = new WorkflowInstance();
    instance.setWorkflow(workflow);
    instance.setCurrentTaskId("t1");

    assertEquals("t1", instance.getCurrentTask().getTaskId());
  }

  /** The XML-RPC struct is a Hashtable, which rejects null values. */
  private WorkflowInstance instanceWith(Priority priority, String id) {
    WorkflowInstance instance = new WorkflowInstance();
    instance.setId(id);
    instance.setCurrentTaskId("t1");
    instance.setStatus("STARTED");
    Workflow workflow = new Workflow();
    workflow.setId("w1");
    workflow.setName("w");
    instance.setWorkflow(workflow);
    instance.setPriority(priority);
    return instance;
  }

  private Element element(String xml) {
    try {
      Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
          .parse(new InputSource(new StringReader(xml)));
      return document.getDocumentElement();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
