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

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.engine.runner.AsynchronousLocalEngineRunner;
import org.apache.oodt.cas.workflow.instrepo.MemoryWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.repository.PackagedWorkflowRepository;
import org.apache.oodt.cas.workflow.structs.HighestFIFOPrioritySorter;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

import junit.framework.TestCase;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * When a workflow finished.
 *
 * <p>
 * A wall clock is the difference between two times. This engine recorded
 * only the start, so everything downstream had nothing to subtract from:
 * finished work showed no elapsed time, or read as though it were still
 * running.
 * </p>
 */
public class TestW2EndDates extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String MODEL_DIR = "./src/test/resources/wengine-e2e";

  private MemoryWorkflowInstanceRepository instanceRepo;
  private PrioritizedQueueBasedWorkflowEngine engine;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.instanceRepo = new MemoryWorkflowInstanceRepository(500);
    this.engine = new PrioritizedQueueBasedWorkflowEngine(instanceRepo,
        new HighestFIFOPrioritySorter(1, 50, 1),
        new WorkflowLifecycleManager(LIFECYCLE),
        new AsynchronousLocalEngineRunner(),
        new PackagedWorkflowRepository(
            Arrays.asList(new File(MODEL_DIR).listFiles())),
        1);
  }

  /** A workflow that has run to completion says when it finished. */
  public void testAfinishedInstanceHasAnEndDate() throws Exception {
    WorkflowInstance inst = engine.startWorkflow(
        modelFor("urn:oodt:e2e:TwoStep"), new Metadata());

    WorkflowInstance finished = waitForDone(inst.getId());

    assertNotNull("a finished workflow must say when it finished",
        finished.getEndDateTimeIsoStr());
    assertFalse("an empty end date is no better than none",
        finished.getEndDateTimeIsoStr().trim().equals(""));
  }

  /** And the elapsed time it implies is usable: after the start, not before. */
  public void testTheEndDateIsAfterTheStart() throws Exception {
    WorkflowInstance inst = engine.startWorkflow(
        modelFor("urn:oodt:e2e:TwoStep"), new Metadata());

    WorkflowInstance finished = waitForDone(inst.getId());

    assertNotNull(finished.getStartDateTimeIsoStr());
    assertTrue("a workflow cannot finish before it started: start ["
            + finished.getStartDateTimeIsoStr() + "] end ["
            + finished.getEndDateTimeIsoStr() + "]",
        finished.getEndDateTimeIsoStr().compareTo(
            finished.getStartDateTimeIsoStr()) >= 0);
  }

  private WorkflowInstance waitForDone(String id) throws Exception {
    for (int i = 0; i < 120; i++) {
      WorkflowInstance inst = instanceRepo.getWorkflowInstanceById(id);
      if (inst != null && inst.getState() != null
          && inst.getState().getCategory() != null
          && "done".equals(inst.getState().getCategory().getName())) {
        return inst;
      }
      Thread.sleep(500);
    }
    fail("workflow [" + id + "] did not finish");
    return null;
  }

  private Workflow modelFor(String id) throws Exception {
    List<?> workflows = engine.getWorkflowRepository().getWorkflowsForEvent(id);
    assertNotNull("no workflow for event [" + id + "]", workflows);
    assertFalse(workflows.isEmpty());
    return (Workflow) workflows.get(0);
  }
}
