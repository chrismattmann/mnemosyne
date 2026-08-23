/**
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

//JUnit imports
import junit.framework.TestCase;

/**
 * How a {@link WorkflowInstance} carries its workflow.
 *
 * Two positions have to hold at once here. The queue-based engine wants a
 * graph, and asking every instance to be graph-shaped is the simplest way to
 * give it one. But WorkflowInstance is shared by both engines, and a shared
 * structure that carries one engine's model forces the other engine to know
 * about it too, which is how the type ended up in the ThreadPool engine's own
 * worker.
 *
 * The instance now stores whatever workflow it was given and derives the graph
 * form on demand. Engines that need a graph still get one; the structure no
 * longer insists that everything is a graph.
 *
 * @author mattmann
 */
public class TestWorkflowInstanceGraphView extends TestCase {

  /**
   * A plain workflow stays plain. Previously the constructor rewrote it into a
   * ParentChildWorkflow, so this could not be observed.
   */
  public void testPlainWorkflowIsStoredAsGiven() {
    Workflow plain = new Workflow();
    plain.setId("urn:oodt:plain");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(plain);

    assertSame("a plain workflow should be stored, not converted",
        plain, inst.getWorkflow());
    assertFalse("the stored workflow should not have been made graph-shaped",
        inst.getWorkflow() instanceof ParentChildWorkflow);
  }

  /**
   * The engine that wants a graph still gets one, without the structure having
   * imposed it on everybody.
   */
  public void testGraphViewIsAvailableForAPlainWorkflow() {
    Workflow plain = new Workflow();
    plain.setId("urn:oodt:plain");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(plain);

    ParentChildWorkflow view = inst.getParentChildWorkflow();
    assertNotNull(view);
    assertEquals("the view should describe the same workflow",
        "urn:oodt:plain", view.getId());
  }

  /**
   * Callers read through the view repeatedly, so it must be stable rather than
   * a fresh wrapper per call.
   */
  public void testGraphViewIsStableAcrossCalls() {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(new Workflow());

    assertSame(inst.getParentChildWorkflow(), inst.getParentChildWorkflow());
  }

  /**
   * A workflow that is already graph-shaped is handed back as itself, not
   * wrapped a second time.
   */
  public void testGraphShapedWorkflowIsNotRewrapped() {
    ParentChildWorkflow graph = new ParentChildWorkflow(new Graph());
    graph.setId("urn:oodt:graph");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(graph);

    assertSame(graph, inst.getParentChildWorkflow());
    assertSame(graph, inst.getWorkflow());
  }

  /**
   * Setting the graph form directly, as the queue-based engine does, keeps the
   * stored workflow and the view as one object so they cannot drift.
   */
  public void testSettingTheGraphFormKeepsBothInAgreement() {
    ParentChildWorkflow graph = new ParentChildWorkflow(new Graph());
    graph.setId("urn:oodt:graph");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setParentChildWorkflow(graph);

    assertSame(graph, inst.getParentChildWorkflow());
    assertSame(graph, inst.getWorkflow());
  }

  /**
   * Replacing the workflow must not leave the previous graph view behind.
   */
  public void testReplacingTheWorkflowDiscardsTheOldView() {
    WorkflowInstance inst = new WorkflowInstance();
    Workflow first = new Workflow();
    first.setId("urn:oodt:first");
    inst.setWorkflow(first);
    ParentChildWorkflow firstView = inst.getParentChildWorkflow();

    Workflow second = new Workflow();
    second.setId("urn:oodt:second");
    inst.setWorkflow(second);

    assertNotSame("the view must follow the workflow it describes",
        firstView, inst.getParentChildWorkflow());
    assertEquals("urn:oodt:second", inst.getParentChildWorkflow().getId());
  }

  /**
   * A default-constructed instance is still usable by an engine that expects a
   * graph, which is what the old force-wrap guaranteed.
   */
  public void testDefaultInstanceStillYieldsAView() {
    WorkflowInstance inst = new WorkflowInstance();
    assertNotNull(inst.getWorkflow());
    assertNotNull(inst.getParentChildWorkflow());
  }

  /**
   * Tasks reached through the view are the workflow's own, not a copy, so the
   * two agree on what the workflow contains.
   */
  public void testViewSharesTasksWithTheWorkflow() {
    Workflow plain = new Workflow();
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:oodt:task");
    plain.getTasks().add(task);

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(plain);

    assertEquals(1, inst.getParentChildWorkflow().getTasks().size());
    assertEquals("urn:oodt:task", ((WorkflowTask) inst
        .getParentChildWorkflow().getTasks().get(0)).getTaskId());
  }
}
