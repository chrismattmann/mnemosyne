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

import junit.framework.TestCase;

import java.io.File;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.LogManager;

/**
 * The execution attribute on a conditions block, end to end through the engine.
 *
 * <p>
 * The two fixture workflows differ only in that attribute, so what separates
 * them is exactly what is under test. Concurrency is measured rather than
 * inferred from timing: the probe condition counts how many evaluations were in
 * flight at once.
 * </p>
 */
public class TestParallelConditions extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String MODEL_DIR = "./src/test/resources/wengine-e2e";

  private static final long TIMEOUT_MILLIS = 30000;

  private static final int PROBES = 3;

  private PrioritizedQueueBasedWorkflowEngine engine;

  private MemoryWorkflowInstanceRepository instanceRepo;

  private AsynchronousLocalEngineRunner runner;

  public TestParallelConditions() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    RecordingTask.reset();
    GateCondition.reset();
    ConcurrencyProbeCondition.reset(PROBES);

    this.instanceRepo = new MemoryWorkflowInstanceRepository(500);
    this.runner = new AsynchronousLocalEngineRunner();

    PackagedWorkflowRepository modelRepo = new PackagedWorkflowRepository(
        Arrays.asList(new File(MODEL_DIR).listFiles()));

    this.engine = new PrioritizedQueueBasedWorkflowEngine(instanceRepo,
        new HighestFIFOPrioritySorter(1, 50, 1),
        new WorkflowLifecycleManager(LIFECYCLE), runner, modelRepo, 1);
  }

  @Override
  protected void tearDown() throws Exception {
    if (this.engine != null) {
      this.engine.shutdown();
    }
    if (this.runner != null) {
      this.runner.shutdown();
    }
    RecordingTask.reset();
    super.tearDown();
  }

  public void testTheModelCarriesTheDeclaredStrategy() throws Exception {
    assertEquals(Workflow.PARALLEL_CONDITIONS,
        modelFor("urn:oodt:e2e:ParallelConds").getPreConditionExecutionType());
  }

  public void testAnUndeclaredBlockCarriesNoStrategy() throws Exception {
    assertNull("absent must stay absent, so nothing changes for it",
        modelFor("urn:oodt:e2e:TwoStep").getPreConditionExecutionType());
  }

  /**
   * The conditions are shared definitions, referenced by id-ref from both
   * fixture workflows. A strategy recorded on the condition rather than on the
   * workflow would follow it across, which is why this pair is written with
   * the same three conditions.
   */
  public void testTheStrategyDoesNotLeakThroughASharedCondition()
      throws Exception {
    assertEquals(Workflow.SEQUENTIAL_CONDITIONS,
        modelFor("urn:oodt:e2e:SequentialConds").getPreConditionExecutionType());
  }

  public void testAParallelBlockEvaluatesItsConditionsAtOnce()
      throws Exception {
    engine.startWorkflow(modelFor("urn:oodt:e2e:ParallelConds"), new Metadata());

    awaitRecorded("after-probes");

    assertTrue("expected the conditions to overlap, saw at most "
        + ConcurrencyProbeCondition.maxInFlight() + " at once",
        ConcurrencyProbeCondition.maxInFlight() > 1);
  }

  public void testAsequentialBlockEvaluatesOneAtATime()
      throws Exception {
    engine.startWorkflow(modelFor("urn:oodt:e2e:SequentialConds"),
        new Metadata());

    awaitRecorded("after-probes");

    assertEquals("a block that asked to be sequential must evaluate one at a time",
        1, ConcurrencyProbeCondition.maxInFlight());
  }

  /** Whatever the strategy, the guarded task still waits for all of them. */
  public void testTheGuardedTaskStillRunsAfterAParallelBlock()
      throws Exception {
    engine.startWorkflow(modelFor("urn:oodt:e2e:ParallelConds"), new Metadata());

    awaitRecorded("after-probes");

    assertTrue(RecordingTask.recorded().contains("after-probes"));
  }

  private Workflow modelFor(String id) throws Exception {
    return engine.getWorkflowRepository().getWorkflowById(id);
  }

  private void awaitRecorded(String entry) throws Exception {
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      if (RecordingTask.recorded().contains(entry)) {
        return;
      }
      Thread.sleep(100);
    }
    fail("Timed out after " + TIMEOUT_MILLIS + "ms waiting for [" + entry
        + "]; recorded " + RecordingTask.recorded() + ", max conditions in "
        + "flight was " + ConcurrencyProbeCondition.maxInFlight());
  }
}
