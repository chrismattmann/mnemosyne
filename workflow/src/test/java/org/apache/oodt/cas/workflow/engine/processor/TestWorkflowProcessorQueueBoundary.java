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

package org.apache.oodt.cas.workflow.engine.processor;

//OODT imports
import org.apache.oodt.cas.workflow.instrepo.MemoryWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

//JUnit imports
import junit.framework.TestCase;

/**
 * Conversion at the queue-based engine's boundary.
 *
 * A workflow that came from a repository with no notion of graphs carries no
 * execution type, and this engine needs one to decide how to run it. The
 * alternative was to teach every repository to produce graph-shaped workflows,
 * which puts one engine's model into code the other engine also uses. Doing
 * the conversion here keeps the repositories unaware of graphs while leaving
 * this engine able to run workflows from any of them.
 *
 * @author mattmann
 */
public class TestWorkflowProcessorQueueBoundary extends TestCase {

  private static final String PARALLEL_PROPERTY =
      "org.apache.oodt.cas.workflow.wengine.packagedRepo.parallelProcessors";

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private String priorPropertyValue;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.priorPropertyValue = System.getProperty(PARALLEL_PROPERTY);
    System.clearProperty(PARALLEL_PROPERTY);
  }

  @Override
  protected void tearDown() throws Exception {
    if (this.priorPropertyValue == null) {
      System.clearProperty(PARALLEL_PROPERTY);
    } else {
      System.setProperty(PARALLEL_PROPERTY, this.priorPropertyValue);
    }
    super.tearDown();
  }

  /**
   * The default reading of an ordered task list is that its order matters.
   */
  public void testPlainWorkflowDefaultsToSequential() throws Exception {
    WorkflowProcessorQueue queue = newQueue();
    WorkflowInstance inst = instanceForPlainWorkflow();

    invokeEnsureExecutionType(queue, inst);

    assertEquals("sequential",
        inst.getParentChildWorkflow().getGraph().getExecutionType());
  }

  /**
   * Declaring the tasks independent lets the graph run them together.
   */
  public void testParallelPropertyChangesTheDefault() throws Exception {
    System.setProperty(PARALLEL_PROPERTY, "true");
    WorkflowProcessorQueue queue = newQueue();
    WorkflowInstance inst = instanceForPlainWorkflow();

    invokeEnsureExecutionType(queue, inst);

    assertEquals("parallel",
        inst.getParentChildWorkflow().getGraph().getExecutionType());
  }

  /**
   * A workflow that already describes how it runs is left alone. The boundary
   * supplies what is missing; it does not overrule the model.
   */
  public void testExistingExecutionTypeIsPreserved() throws Exception {
    System.setProperty(PARALLEL_PROPERTY, "true");
    WorkflowProcessorQueue queue = newQueue();

    Graph graph = new Graph();
    graph.setExecutionType("sequential");
    ParentChildWorkflow workflow = new ParentChildWorkflow(graph);
    workflow.setId("urn:oodt:explicit");
    WorkflowInstance inst = new WorkflowInstance();
    inst.setParentChildWorkflow(workflow);

    invokeEnsureExecutionType(queue, inst);

    assertEquals("an explicit execution type must win over the default",
        "sequential", inst.getParentChildWorkflow().getGraph()
            .getExecutionType());
  }

  /**
   * An empty execution type is as absent as a null one; both are filled in.
   */
  public void testEmptyExecutionTypeIsTreatedAsAbsent() throws Exception {
    WorkflowProcessorQueue queue = newQueue();

    Graph graph = new Graph();
    graph.setExecutionType("");
    ParentChildWorkflow workflow = new ParentChildWorkflow(graph);
    WorkflowInstance inst = new WorkflowInstance();
    inst.setParentChildWorkflow(workflow);

    invokeEnsureExecutionType(queue, inst);

    assertEquals("sequential",
        inst.getParentChildWorkflow().getGraph().getExecutionType());
  }

  /**
   * Conversion must not fail on an instance carrying nothing useful.
   */
  public void testInstanceWithoutAWorkflowIsTolerated() throws Exception {
    WorkflowProcessorQueue queue = newQueue();
    invokeEnsureExecutionType(queue, new WorkflowInstance());
  }

  // ---- helpers ----------------------------------------------------------

  private WorkflowProcessorQueue newQueue() throws Exception {
    return new WorkflowProcessorQueue(new MemoryWorkflowInstanceRepository(20),
        new WorkflowLifecycleManager(LIFECYCLE), null);
  }

  /**
   * An instance holding a plain workflow, as a graph-unaware repository would
   * produce: real tasks, but nothing describing how to run them.
   */
  private WorkflowInstance instanceForPlainWorkflow() {
    Workflow plain = new Workflow();
    plain.setId("urn:oodt:plain");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:oodt:task");
    plain.getTasks().add(task);

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(plain);
    inst.setCurrentTaskId("urn:oodt:task");
    return inst;
  }

  private void invokeEnsureExecutionType(WorkflowProcessorQueue queue,
      WorkflowInstance inst) throws Exception {
    java.lang.reflect.Method m = WorkflowProcessorQueue.class
        .getDeclaredMethod("ensureExecutionType", WorkflowInstance.class);
    m.setAccessible(true);
    m.invoke(queue, inst);
  }
}
