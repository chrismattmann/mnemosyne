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

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.instrepo.MemoryWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.repository.PackagedWorkflowRepository;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

import junit.framework.TestCase;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * A condition is evaluated against the shared context of what it guards.
 *
 * <p>
 * A condition attached to a task got a context of its own instead, empty, and
 * a condition that reads what the workflow is working on -- which chunk, which
 * product -- had nothing to read and held forever. The same condition attached
 * to the workflow worked, so the model looked right and the deployment stalled
 * with every job queued and no node busy.
 * </p>
 */
public class TestConditionsSeeTheContext extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String MODEL_DIR = "./src/test/resources/wengine-e2e";

  private WorkflowProcessorQueue queue;

  private MemoryWorkflowInstanceRepository instanceRepo;

  /** The task instance the condition guards. */
  private WorkflowInstance parent;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.instanceRepo = new MemoryWorkflowInstanceRepository(500);
    PackagedWorkflowRepository modelRepo = new PackagedWorkflowRepository(
        Arrays.asList(new File(MODEL_DIR).listFiles()));
    this.queue = new WorkflowProcessorQueue(instanceRepo,
        new WorkflowLifecycleManager(LIFECYCLE), modelRepo);
  }

  /** The reported bug: a condition written on the task. */
  public void testAtaskConditionSeesWhatTheTaskSees() throws Exception {
    WorkflowProcessor condition = conditionUnder("urn:oodt:e2e:GuardedTask");

    assertNotNull("the task's condition was never built", condition);
    assertNotNull("the condition has no context at all", condition
        .getWorkflowInstance().getSharedContext());
    assertEquals("chunk-00376.json", condition.getWorkflowInstance()
        .getSharedContext().getMetadata("Filename"));
  }

  /** And it is the parent's context, not a copy, so writes travel back. */
  public void testTheConditionSharesRatherThanCopies() throws Exception {
    WorkflowProcessor condition = conditionUnder("urn:oodt:e2e:GuardedTask");

    condition.getWorkflowInstance().getSharedContext()
        .replaceMetadata("WrittenByTheCondition", "yes");

    assertEquals("yes", parent.getSharedContext().getMetadata(
        "WrittenByTheCondition"));
  }

  /**
   * Loads a task instance carrying a context and returns the processor built
   * for the condition guarding it.
   */
  private WorkflowProcessor conditionUnder(String taskId) throws Exception {
    Metadata context = new Metadata();
    context.addMetadata("Filename", "chunk-00376.json");

    Workflow bare = new Workflow();
    bare.setId(WorkflowProcessorQueue.generatedId(
        WorkflowProcessorQueue.TASK_WORKFLOW, "urn:oodt:e2e:GuardedTaskWorkflow",
        taskId));
    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(bare);
    inst.setStatus("Loaded");
    inst.setCurrentTaskId(taskId);
    inst.setSharedContext(context);
    instanceRepo.addWorkflowInstance(inst);
    this.parent = inst;

    List<WorkflowProcessor> processors = queue.getProcessors();
    assertFalse("the task instance was not loaded", processors.isEmpty());

    List<WorkflowProcessor> subs = processors.get(0).getSubProcessors();
    return subs.isEmpty() ? null : subs.get(0);
  }
}
