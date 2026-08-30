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

import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;

import junit.framework.TestCase;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * What a condition is, as opposed to what its enclosing workflow happens to
 * be configured with.
 *
 * <p>
 * A single Metadata was threaded through the whole parse and mutated at every
 * node, so a &lt;configuration&gt; block did not stop at the element that
 * wrote it. Everything visited afterwards inherited it, and each task and
 * condition snapshotted the union of everything parsed before. Conditions
 * that declare no configuration came back carrying a task's script paths.
 * </p>
 */
public class TestPackagedConditionScoping extends TestCase {

  private static final String GRANULE_MAPS =
      "src/main/resources/examples/wengine/GranuleMaps.xml";

  private static final String HELLO_GOODBYE =
      "src/main/resources/examples/wengine/hello-goodbye.xml";

  /** Two configured tasks and two conditions that declare nothing. */
  private static final String SCOPING =
      "src/test/resources/wengine-scoping/scoping.xml";

  private PackagedWorkflowRepository repo;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.repo = new PackagedWorkflowRepository(
        Arrays.asList(new File(GRANULE_MAPS)));
  }

  /**
   * The conditions in GranuleMaps declare no configuration of their own, and
   * the workflow that references them declares a great deal.
   */
  public void testAconditionDoesNotInheritATasksConfiguration()
      throws Exception {
    PackagedWorkflowRepository scoping = new PackagedWorkflowRepository(
        Arrays.asList(new File(SCOPING)));

    List<WorkflowCondition> conditions = conditionsOf(scoping);
    assertEquals("expected both conditions", 2, conditions.size());

    for (int i = 0; i < conditions.size(); i++) {
      WorkflowCondition cond = conditions.get(i);
      if (cond.getCondConfig() == null) {
        continue;
      }
      assertEquals("condition [" + cond.getConditionId() + "] declares no"
              + " configuration and must not report any",
          0, cond.getCondConfig().getProperties().size());
    }
  }

  /**
   * Two sibling tasks, each configured. Neither may end up holding the
   * other's properties, and a key they both declare keeps its own value.
   */
  public void testSiblingTasksDoNotInheritEachOthersConfiguration()
      throws Exception {
    PackagedWorkflowRepository scoping = new PackagedWorkflowRepository(
        Arrays.asList(new File(SCOPING)));

    org.apache.oodt.cas.workflow.structs.WorkflowTask aggregate =
        scoping.getTaskById("urn:test:Aggregate");
    assertNotNull(aggregate);
    java.util.Properties props = aggregate.getTaskConfig().getProperties();

    assertEquals("aggregate-value", props.getProperty("OnlyOnAggregate"));
    assertNull("the partitioner's property leaked into the aggregator",
        props.getProperty("OnlyOnPartition"));
    assertEquals("a key both declare must keep this task's value",
        "from-aggregate", props.getProperty("Shared"));
  }

  /**
   * Order is the whole content of a sequential block, and nothing set it, so
   * every condition reported the -1 default.
   */
  public void testEachconditionIsNumberedByItsPositionInTheBlock()
      throws Exception {
    boolean checkedOne = false;
    for (Object o : repo.getWorkflows()) {
      Workflow w = (Workflow) o;
      List<WorkflowCondition> pre = w.getPreConditions();
      if (pre == null || pre.isEmpty()) {
        continue;
      }
      for (int i = 0; i < pre.size(); i++) {
        assertEquals("condition [" + pre.get(i).getConditionId()
                + "] on [" + w.getId() + "] should be numbered by position",
            i + 1, pre.get(i).getOrder());
        checkedOne = true;
      }
    }
    assertTrue("expected at least one workflow-level condition", checkedOne);
  }

  /**
   * The same definition referenced twice must not have one reference's
   * position overwrite the other's. Sharing one object made order a property
   * of whichever workflow was parsed last.
   */
  public void testAdefinitionReferencedTwiceIsNotSharedBetweenReferences()
      throws Exception {
    List<WorkflowCondition> conditions = allConditions();
    for (int i = 0; i < conditions.size(); i++) {
      for (int j = i + 1; j < conditions.size(); j++) {
        WorkflowCondition a = conditions.get(i);
        WorkflowCondition b = conditions.get(j);
        if (a.getConditionId() != null
            && a.getConditionId().equals(b.getConditionId())) {
          assertNotSame("two references to [" + a.getConditionId()
              + "] share one object, so they cannot be ordered"
              + " independently", a, b);
        }
      }
    }
  }

  /** A task keeps the configuration it declared, which is the point. */
  public void testAtaskStillCarriesItsOwnConfiguration() throws Exception {
    PackagedWorkflowRepository hello = new PackagedWorkflowRepository(
        Arrays.asList(new File(HELLO_GOODBYE)));
    boolean sawConfiguredTask = false;
    for (Object o : hello.getWorkflows()) {
      Workflow w = (Workflow) o;
      for (int i = 0; i < w.getTasks().size(); i++) {
        Object t = w.getTasks().get(i);
        org.apache.oodt.cas.workflow.structs.WorkflowTask task =
            (org.apache.oodt.cas.workflow.structs.WorkflowTask) t;
        if (task.getTaskConfig() != null
            && !task.getTaskConfig().getProperties().isEmpty()) {
          sawConfiguredTask = true;
        }
      }
    }
    assertTrue("scoping the configuration must not empty a task's own",
        sawConfiguredTask);
  }

  private List<WorkflowCondition> conditionsOf(PackagedWorkflowRepository r)
      throws Exception {
    List<WorkflowCondition> out = new java.util.ArrayList<WorkflowCondition>();
    for (Object o : r.getWorkflows()) {
      Workflow w = (Workflow) o;
      if (w.getPreConditions() != null) {
        out.addAll(w.getPreConditions());
      }
      if (w.getPostConditions() != null) {
        out.addAll(w.getPostConditions());
      }
    }
    return out;
  }

  private List<WorkflowCondition> allConditions() throws Exception {
    List<WorkflowCondition> out = new java.util.ArrayList<WorkflowCondition>();
    for (Object o : repo.getWorkflows()) {
      Workflow w = (Workflow) o;
      if (w.getPreConditions() != null) {
        out.addAll(w.getPreConditions());
      }
      if (w.getPostConditions() != null) {
        out.addAll(w.getPostConditions());
      }
    }
    return out;
  }
}
