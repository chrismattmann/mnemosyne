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
import org.apache.oodt.cas.workflow.structs.HighestPrioritySorter;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

import junit.framework.TestCase;

import java.io.File;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.LogManager;

/**
 * The older engine puts the standard keys into the shared context before a task
 * runs; the wengine runners did not. StdPGETaskInstance requires WorkflowInstId
 * and refuses to start without it, so no PGE task could run under wengine at
 * all, and BranchRedirector -- which reads WorkflowManagerUrl from the same
 * place -- only worked if the caller passed that key by hand.
 */
public class TestTaskMetadataIsStamped extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String MODEL_DIR = "./src/test/resources/wengine-e2e";

  private static final long TIMEOUT_MILLIS = 30000;

  private PrioritizedQueueBasedWorkflowEngine engine;

  private AsynchronousLocalEngineRunner runner;

  public TestTaskMetadataIsStamped() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    RecordingTask.reset();
    GateCondition.reset();

    this.runner = new AsynchronousLocalEngineRunner();
    PackagedWorkflowRepository modelRepo = new PackagedWorkflowRepository(
        Arrays.asList(new File(MODEL_DIR).listFiles()));
    this.engine = new PrioritizedQueueBasedWorkflowEngine(
        new MemoryWorkflowInstanceRepository(500), new HighestPrioritySorter(),
        new WorkflowLifecycleManager(LIFECYCLE), runner, modelRepo, 1);
    this.engine.setWorkflowManagerUrl(new java.net.URL("http://localhost:9001"));
  }

  @Override
  protected void tearDown() throws Exception {
    if (engine != null) {
      engine.shutdown();
    }
    if (runner != null) {
      runner.shutdown();
    }
    RecordingTask.reset();
    super.tearDown();
  }

  /** The key StdPGETaskInstance refuses to start without. */
  public void testAtaskSeesTheWorkflowInstanceId() throws Exception {
    WorkflowInstance inst = engine.startWorkflow(
        modelFor("urn:oodt:e2e:TwoStep"), new Metadata());
    awaitRecorded("first");

    assertTrue("a task must be told which instance it belongs to",
        RecordingTask.keysSeenBy("first").contains("WorkflowInstId"));
  }

  /** The key BranchRedirector reads to reach a nested sub-workflow. */
  public void testAtaskSeesTheWorkflowManagerUrl() throws Exception {
    engine.startWorkflow(modelFor("urn:oodt:e2e:TwoStep"), new Metadata());
    awaitRecorded("first");

    assertTrue("without this a nested sub-workflow cannot be reached",
        RecordingTask.keysSeenBy("first").contains("WorkflowManagerUrl"));
  }

  public void testAtaskSeesTheStandardKeys() throws Exception {
    engine.startWorkflow(modelFor("urn:oodt:e2e:TwoStep"), new Metadata());
    awaitRecorded("first");

    for (String key : new String[] {"TaskId", "JobId", "ProcessingNode",
        "WorkflowId", "WorkflowName"}) {
      assertTrue("the older engine supplies " + key + " and so should this one",
          RecordingTask.keysSeenBy("first").contains(key));
    }
  }

  /** What the caller supplied has to survive being stamped over. */
  public void testWhatTheCallerSuppliedIsStillThere() throws Exception {
    Metadata met = new Metadata();
    met.addMetadata("SuppliedAtStart", "yes");

    engine.startWorkflow(modelFor("urn:oodt:e2e:TwoStep"), met);
    awaitRecorded("first");

    assertTrue(RecordingTask.keysSeenBy("first").contains("SuppliedAtStart"));
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
    fail("Timed out waiting for [" + entry + "]; recorded "
        + RecordingTask.recorded());
  }
}
