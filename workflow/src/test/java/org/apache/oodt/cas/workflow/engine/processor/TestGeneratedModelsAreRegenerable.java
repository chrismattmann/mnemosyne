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

package org.apache.oodt.cas.workflow.engine.processor;

import org.apache.oodt.cas.workflow.instrepo.MemoryWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.repository.PackagedWorkflowRepository;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

import junit.framework.TestCase;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * The workflows this queue builds during a run used to live only in the
 * repository instance the engine happened to hold, and only in memory. Nothing
 * else could describe an instance of one, and a restart lost them. They are now
 * built again on demand from the declared model.
 */
public class TestGeneratedModelsAreRegenerable extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String MODEL_DIR = "./src/test/resources/wengine-e2e";

  private WorkflowProcessorQueue queue;

  private MemoryWorkflowInstanceRepository instanceRepo;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.instanceRepo = new MemoryWorkflowInstanceRepository(500);
    PackagedWorkflowRepository modelRepo = new PackagedWorkflowRepository(
        Arrays.asList(new File(MODEL_DIR).listFiles()));
    this.queue = new WorkflowProcessorQueue(instanceRepo,
        new WorkflowLifecycleManager(LIFECYCLE), modelRepo);
  }

  /**
   * Every task of a composite workflow used to be given the same generated id:
   * "task-workflow-" plus the parent's id, with nothing naming the task. They
   * all collided, so no instance could be told from another.
   */
  public void testAgeneratedIdNamesBothEnds() {
    String first = WorkflowProcessorQueue.generatedId(
        WorkflowProcessorQueue.TASK_WORKFLOW, "urn:oodt:e2e:TwoStep",
        "urn:oodt:e2e:First");
    String second = WorkflowProcessorQueue.generatedId(
        WorkflowProcessorQueue.TASK_WORKFLOW, "urn:oodt:e2e:TwoStep",
        "urn:oodt:e2e:Second");

    assertFalse("two tasks of one parent must not share an id",
        first.equals(second));
    assertTrue(first.contains("urn:oodt:e2e:TwoStep"));
    assertTrue(first.contains("urn:oodt:e2e:First"));
  }

  /** A task workflow built again from its id alone. */
  public void testAtaskWorkflowIsRegeneratedFromItsId() {
    String id = WorkflowProcessorQueue.generatedId(
        WorkflowProcessorQueue.TASK_WORKFLOW, "urn:oodt:e2e:TwoStep",
        "urn:oodt:e2e:First");

    ParentChildWorkflow model = queue.regenerateModel(id);

    assertNotNull("a generated model should be rebuildable from its id", model);
    assertEquals("task", model.getGraph().getExecutionType());
    assertEquals(1, model.getTasks().size());
    assertEquals("urn:oodt:e2e:First", model.getTasks().get(0).getTaskId());
  }

  /** The regenerated task keeps the configuration the declared model gives it. */
  public void testAregeneratedTaskCarriesItsConfiguration() {
    String id = WorkflowProcessorQueue.generatedId(
        WorkflowProcessorQueue.TASK_WORKFLOW, "urn:oodt:e2e:TwoStep",
        "urn:oodt:e2e:First");

    ParentChildWorkflow model = queue.regenerateModel(id);

    assertNotNull(model);
    assertNotNull("without its configuration the task runs on nothing",
        model.getTasks().get(0).getTaskConfig());
  }

  public void testAnidThatIsNotOursIsDeclined() {
    assertNull(queue.regenerateModel("urn:oodt:e2e:TwoStep"));
    assertNull(queue.regenerateModel(null));
  }

  public void testAnidNamingSomethingUndeclaredIsDeclined() {
    assertNull(queue.regenerateModel(WorkflowProcessorQueue.generatedId(
        WorkflowProcessorQueue.TASK_WORKFLOW, "urn:oodt:e2e:TwoStep",
        "urn:oodt:no:such:task")));
  }

  /**
   * The whole point: an instance persisted against a generated model resolves
   * even though the model was never stored anywhere.
   */
  public void testAninstanceOfAgeneratedModelResolves() throws Exception {
    String id = WorkflowProcessorQueue.generatedId(
        WorkflowProcessorQueue.TASK_WORKFLOW, "urn:oodt:e2e:TwoStep",
        "urn:oodt:e2e:First");

    Workflow bare = new Workflow();
    bare.setId(id);
    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(bare);
    inst.setStatus("Loaded");
    inst.setCurrentTaskId("urn:oodt:e2e:First");
    instanceRepo.addWorkflowInstance(inst);

    List<WorkflowProcessor> processors = queue.getProcessors();

    assertEquals(1, processors.size());
    assertEquals("task", processors.get(0).getWorkflowInstance()
        .getParentChildWorkflow().getGraph().getExecutionType());
  }
}
