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

package org.apache.oodt.cas.workflow.lifecycle;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * addStage guards with stages.contains(stage), but stages is a TreeSet, so
 * contains() answers by the comparator -- which keys on order first -- while
 * WorkflowLifecycleStage.equals is name-only. A stage whose name was already
 * present but whose order differed compared as a different element and was
 * admitted, which is exactly the case the guard exists to catch. getNumStages
 * then counted both, and that count is the denominator of
 * getPercentageComplete.
 */
public class TestWorkflowLifecycleStages {

  private static WorkflowLifecycleStage stage(String name, int order) {
    WorkflowLifecycleStage s = new WorkflowLifecycleStage();
    s.setName(name);
    s.setOrder(order);
    return s;
  }

  @Test
  public void testALifecycleHoldsOneStagePerName() {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("test", "urn:test:wf");

    lifecycle.addStage(stage("running", 1));
    lifecycle.addStage(stage("running", 2));

    assertEquals("the same stage name was stored twice", 1,
        lifecycle.getStages().size());
  }

  /** the same name at the same order was already rejected; it still is. */
  @Test
  public void testTheSameNameAtTheSameOrderIsStillRejected() {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("test", "urn:test:wf");

    lifecycle.addStage(stage("running", 1));
    lifecycle.addStage(stage("running", 1));

    assertEquals(1, lifecycle.getStages().size());
  }

  /** distinct names sharing an order both survive -- both are real stages. */
  @Test
  public void testDistinctNamesSharingAnOrderAreBothKept() {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("test", "urn:test:wf");

    lifecycle.addStage(stage("running", 1));
    lifecycle.addStage(stage("blocked", 1));

    assertEquals(2, lifecycle.getStages().size());
  }

  /** the first stage under a name is the one kept. */
  @Test
  public void testTheFirstStageUnderANameIsTheOneKept() {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("test", "urn:test:wf");

    lifecycle.addStage(stage("running", 1));
    lifecycle.addStage(stage("running", 7));

    WorkflowLifecycleStage held =
        (WorkflowLifecycleStage) lifecycle.getStages().first();
    assertEquals("running", held.getName());
    assertEquals(1, held.getOrder());
  }

  /**
   * remove() searched by the same comparator, so a caller holding a stage
   * with the right name but a different order removed nothing.
   */
  @Test
  public void testAStageCanBeRemovedByNameAlone() {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("test", "urn:test:wf");
    lifecycle.addStage(stage("running", 1));

    assertTrue("the stage was not removed", lifecycle.removeStage(stage("running", 99)));
    assertEquals(0, lifecycle.getStages().size());
  }

  /** removing a name the lifecycle does not hold reports failure. */
  @Test
  public void testRemovingAnAbsentStageReportsFailure() {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("test", "urn:test:wf");
    lifecycle.addStage(stage("running", 1));

    assertFalse(lifecycle.removeStage(stage("blocked", 1)));
    assertEquals(1, lifecycle.getStages().size());
  }

  /**
   * The stage count reaches getPercentageComplete as its denominator, by way
   * of WorkflowLifecycleManager.getNumStages, which is getStages().size().
   */
  @Test
  public void testTheStageCountIsNotInflatedByADuplicateName() {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("test", "urn:test:wf");

    lifecycle.addStage(stage("running", 1));
    lifecycle.addStage(stage("running", 2));
    lifecycle.addStage(stage("done", 3));

    assertEquals(2, lifecycle.getStages().size());
  }
}
