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

package org.apache.oodt.cas.workflow.tools;

//JDK imports
import java.io.File;
import java.util.List;

//APACHE imports
import org.apache.commons.io.FileUtils;

//OODT imports
import org.apache.oodt.cas.workflow.instrepo.LuceneWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowStatus;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;

//Junit imports
import junit.framework.TestCase;

/**
 * 
 * Test harness for the {@link InstanceRepoCleaner}.
 * 
 * @author mattmann
 * @version $Revision$
 * @since 
 * 
 */
public class TestInstanceRepoCleaner extends TestCase {

  private String instRepoPath;

  public void testClean() {
    InstanceRepoCleaner cleaner = new InstanceRepoCleaner();
    cleaner.setInstanceRepo(instRepoPath);
    try {
      cleaner.cleanRepository();
    } catch (Exception e) {
      fail(e.getMessage());
    }

    WorkflowInstanceRepository repo = new LuceneWorkflowInstanceRepository(
        instRepoPath, 20);
    try {
      assertEquals(1, repo.getNumWorkflowInstances());
      for (WorkflowInstance inst : (List<WorkflowInstance>) repo
          .getWorkflowInstances()) {
        if (!inst.getStatus().equals(WorkflowStatus.FINISHED)) {
          fail("Workflow Instance: [" + inst.getId()
              + "] does was not marked as finished by the cleaner: status: ["
              + inst.getStatus() + "]");
        }
      }

    } catch (InstanceRepositoryException e) {
      fail(e.getMessage());
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see junit.framework.TestCase#setUp()
   */
  @Override
  protected void setUp() throws Exception {
    // Build the index rather than ship one.
    //
    // This test used to copy a Lucene index committed to the repository in
    // February 2018, and Lucene reads one major version back, not four: the
    // current library cannot open it at all. A binary index checked in as a
    // fixture is a landmine that goes off at every major upgrade, and it went
    // off at this one. Building it here means the fixture is always in the
    // format the library being tested actually writes.
    File tempDir = File.createTempFile("bogus", "txt").getParentFile();
    File repoDir = new File(tempDir, "testinstrepo-" + System.nanoTime());
    assertTrue("could not create " + repoDir, repoDir.mkdirs());
    instRepoPath = repoDir.getAbsolutePath();

    LuceneWorkflowInstanceRepository repo =
        new LuceneWorkflowInstanceRepository(instRepoPath, 20);

    // One instance, left in a state the cleaner is supposed to finish.
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("test-instance");
    inst.setStatus(WorkflowStatus.STARTED);
    Workflow workflow = new Workflow();
    workflow.setId("urn:oodt:TestWorkflow");
    workflow.setName("Test Workflow");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:oodt:TestTask");
    task.setTaskName("Test Task");
    task.setTaskInstanceClassName(
        "org.apache.oodt.cas.workflow.examples.NoOpTask");
    task.setTaskConfig(new WorkflowTaskConfiguration());
    workflow.getTasks().add(task);
    inst.setWorkflow(workflow);
    inst.setCurrentTaskId(task.getTaskId());
    repo.addWorkflowInstance(inst);
  }

  /*
   * (non-Javadoc)
   * 
   * @see junit.framework.TestCase#tearDown()
   */
  @Override
  protected void tearDown() throws Exception {
    FileUtils.deleteDirectory(new File(instRepoPath));

  }

}
