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

package org.apache.oodt.cas.workflow.system;

import java.io.File;
import java.nio.file.Files;

import junit.framework.TestCase;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.instrepo.LuceneWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

/**
 * Clearing the instances a manager holds, over the wire.
 *
 * <p>
 * A manager with nothing running, because clearing is refused while a run is
 * going and that refusal is tested where a run is going.
 * </p>
 */
public class TestClearWorkflowInstances extends TestCase {

  private static final int WM_PORT = 65525;

  private AvroRpcWorkflowManager wmgr;
  private String idxPath;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    idxPath = Files.createTempDirectory("clear-winst").toFile()
        .getCanonicalPath();

    // The manager builds a workflow repository as well as an instance one,
    // and defaults to the JDBC factory, which needs configuration this test
    // has no use for.
    System.setProperty("workflow.repo.factory",
        "org.apache.oodt.cas.workflow.repository.XMLWorkflowRepositoryFactory");
    System.setProperty("workflow.engine.factory",
        "org.apache.oodt.cas.workflow.engine.ThreadPoolWorkflowEngineFactory");
    System.setProperty("workflow.engine.instanceRep.factory",
        "org.apache.oodt.cas.workflow.instrepo.LuceneWorkflowInstanceRepositoryFactory");
    System.setProperty(
        "org.apache.oodt.cas.workflow.instanceRep.lucene.idxPath", idxPath);
    System.setProperty("org.apache.oodt.cas.workflow.repo.dirs", "file://"
        + new File("./src/main/resources/examples").getCanonicalPath());
    System.setProperty("org.apache.oodt.cas.workflow.lifecycle.filePath",
        new File("./src/main/resources/examples/workflow-lifecycle.xml")
            .getCanonicalPath());

    // Written straight into the repository: no event is fired, so nothing is
    // executing and the manager has instances to clear.
    LuceneWorkflowInstanceRepository repo =
        new LuceneWorkflowInstanceRepository(idxPath, 20);
    repo.addWorkflowInstance(instance());
    repo.addWorkflowInstance(instance());
    repo.release();

    wmgr = new AvroRpcWorkflowManager(WM_PORT);
  }

  @Override
  protected void tearDown() throws Exception {
    if (wmgr != null) {
      wmgr.shutdown();
    }
    super.tearDown();
  }

  /**
   * Whatever the deployment configured answers, so a caller does not have to
   * know where instances live -- nor stop the manager to reach them, which is
   * what DRAT had to do, and what left it deleting a directory that only
   * exists for one of the three implementations.
   */
  public void testClearingRemovesEveryInstance() throws Exception {
    assertEquals(2, wmgr.getNumWorkflowInstances());

    assertTrue(wmgr.clearWorkflowInstances(true));

    assertEquals("instances survived the clear", 0,
        wmgr.getNumWorkflowInstances());
  }

  /** It has to be meant: this discards every record of every run. */
  public void testClearingWithoutForceIsRefused() throws Exception {
    try {
      wmgr.clearWorkflowInstances(false);
      fail("clearing without force should have been refused");
    } catch (Exception expected) {
      // The refusal is the point.
    }
    assertEquals("instances were cleared despite the refusal", 2,
        wmgr.getNumWorkflowInstances());
  }

  private WorkflowInstance instance() {
    WorkflowInstance inst = new WorkflowInstance();
    Workflow workflow = new Workflow();
    workflow.setId("urn:oodt:clear-me");
    workflow.setName("Clear Me");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:oodt:t");
    task.setTaskName("t");
    task.setTaskInstanceClassName(
        "org.apache.oodt.cas.workflow.examples.NoOpTask");
    task.setTaskConfig(
        new org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration());
    workflow.getTasks().add(task);
    inst.setWorkflow(workflow);
    inst.setCurrentTaskId("urn:oodt:t");
    inst.setStatus("Success");
    inst.setSharedContext(new Metadata());
    return inst;
  }
}
