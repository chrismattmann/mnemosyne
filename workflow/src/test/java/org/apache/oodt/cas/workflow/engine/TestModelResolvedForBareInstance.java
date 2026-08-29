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

package org.apache.oodt.cas.workflow.engine;

import org.apache.oodt.cas.workflow.engine.processor.WorkflowProcessor;
import org.apache.oodt.cas.workflow.engine.processor.WorkflowProcessorQueue;
import org.apache.oodt.cas.workflow.instrepo.MemoryWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.repository.PackagedWorkflowRepository;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;

import junit.framework.TestCase;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * An instance repository stores instance state; the model belongs to the model
 * repository. DataSourceWorkflowInstanceRepository reconstructs an instance
 * with a Workflow carrying nothing but its id -- see
 * DbStructFactory.getWorkflowInstance -- so the graph was empty, no execution
 * type matched a processor class, and the instance sat in its initial state for
 * ever. This reproduces that shape without needing a database: an instance
 * whose workflow has only an id.
 */
public class TestModelResolvedForBareInstance extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String MODEL_DIR = "./src/test/resources/wengine-e2e";

  private WorkflowProcessorQueue queue;

  private MemoryWorkflowInstanceRepository instanceRepo;

  private WorkflowLifecycleManager lifecycle;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.instanceRepo = new MemoryWorkflowInstanceRepository(500);
    PackagedWorkflowRepository modelRepo = new PackagedWorkflowRepository(
        Arrays.asList(new File(MODEL_DIR).listFiles()));
    this.lifecycle = new WorkflowLifecycleManager(LIFECYCLE);
    this.queue = new WorkflowProcessorQueue(instanceRepo, lifecycle, modelRepo);
  }

  /** The shape a JDBC repository hands back. */
  private WorkflowInstance bareInstanceFor(String modelId) throws Exception {
    Workflow bare = new Workflow();
    bare.setId(modelId);

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(bare);
    inst.setState(initialState());
    inst.setCurrentTaskId("urn:oodt:e2e:First");
    instanceRepo.addWorkflowInstance(inst);
    return inst;
  }

  /** getProcessors skips an instance that has no lifecycle state at all. */
  private WorkflowState initialState() {
    return lifecycle.getDefaultLifecycle().createState("Loaded", "initial",
        "created by TestModelResolvedForBareInstance");
  }

  public void testAbareInstanceStillYieldsAProcessor() throws Exception {
    bareInstanceFor("urn:oodt:e2e:TwoStep");

    List<WorkflowProcessor> processors = queue.getProcessors();

    assertNotNull(processors);
    assertEquals("a bare instance should still produce a processor",
        1, processors.size());
  }

  public void testTheResolvedModelCarriesTheWorkflowsTasks() throws Exception {
    bareInstanceFor("urn:oodt:e2e:TwoStep");

    WorkflowProcessor processor = queue.getProcessors().get(0);
    Workflow resolved = processor.getWorkflowInstance().getParentChildWorkflow();

    assertEquals("the model, not the bare stub, should be attached",
        2, resolved.getTasks().size());
  }

  public void testTheResolvedModelCarriesItsExecutionType() throws Exception {
    bareInstanceFor("urn:oodt:e2e:TwoStep");

    WorkflowProcessor processor = queue.getProcessors().get(0);

    assertEquals("sequential", processor.getWorkflowInstance()
        .getParentChildWorkflow().getGraph().getExecutionType());
  }

  /** An instance that already carries its model must not be overwritten. */
  public void testAninstanceThatAlreadyHasAModelIsLeftAlone() throws Exception {
    WorkflowInstance inst = new WorkflowInstance();
    Workflow model = (Workflow) new PackagedWorkflowRepository(
        Arrays.asList(new File(MODEL_DIR).listFiles()))
        .getWorkflowById("urn:oodt:e2e:TwoStep");
    inst.setWorkflow(model);
    inst.setState(initialState());
    inst.setCurrentTaskId("urn:oodt:e2e:First");
    instanceRepo.addWorkflowInstance(inst);

    WorkflowProcessor processor = queue.getProcessors().get(0);

    assertEquals(2,
        processor.getWorkflowInstance().getParentChildWorkflow().getTasks().size());
  }

  /**
   * The other half of what a JDBC repository hands back: the schema stores
   * workflow_instance_status as a string, so setStatus builds a state with a
   * name and no category -- and getProcessors skips anything whose state has
   * no category, so such instances were invisible to the querier.
   */
  public void testAstateCarryingOnlyANameIsStillVisibleToTheQuerier()
      throws Exception {
    Workflow bare = new Workflow();
    bare.setId("urn:oodt:e2e:TwoStep");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(bare);
    inst.setStatus("Loaded");
    inst.setCurrentTaskId("urn:oodt:e2e:First");
    assertNull("setStatus should leave the category unset",
        inst.getState().getCategory());
    instanceRepo.addWorkflowInstance(inst);

    List<WorkflowProcessor> processors = queue.getProcessors();

    assertEquals("an instance with a name-only state must still be processed",
        1, processors.size());
    assertNotNull("and its category should have been resolved",
        processors.get(0).getWorkflowInstance().getState().getCategory());
  }

  /** A state the lifecycle does not define must not bring the queue down. */
  public void testAnunknownStateNameIsSurvivable() throws Exception {
    Workflow bare = new Workflow();
    bare.setId("urn:oodt:e2e:TwoStep");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(bare);
    inst.setStatus("NoSuchStateAnywhere");
    inst.setCurrentTaskId("urn:oodt:e2e:First");
    instanceRepo.addWorkflowInstance(inst);

    assertNotNull(queue.getProcessors());
  }

  /** A model the repository does not hold must not bring the queue down. */
  public void testAnunknownModelIdIsSurvivable() throws Exception {
    bareInstanceFor("urn:oodt:no:such:workflow");

    List<WorkflowProcessor> processors = queue.getProcessors();

    assertNotNull("getProcessors must not throw for an unresolvable model",
        processors);
  }
}
