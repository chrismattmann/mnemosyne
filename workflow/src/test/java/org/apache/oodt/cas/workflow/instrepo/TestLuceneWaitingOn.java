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

package org.apache.oodt.cas.workflow.instrepo;

import java.nio.file.Files;
import java.util.List;

import junit.framework.TestCase;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

/**
 * The Lucene repository is the other half of the interchangeable pair, so the
 * reason an instance is waiting has to survive a restart there too.
 */
public class TestLuceneWaitingOn extends TestCase {

  private String path;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.path = Files.createTempDirectory("winst-lucene-waiting")
        .toFile().getAbsolutePath();
  }

  public void testTheReasonOutlivesTheProcessThatWroteIt() throws Exception {
    LuceneWorkflowInstanceRepository repo = repository();
    WorkflowInstance inst = instance();
    inst.setWaitingOn("condition:urn:drat:MapsDone");
    repo.addWorkflowInstance(inst);
    String id = inst.getId();
    repo.release();

    WorkflowInstance found = find(repository().getWorkflowInstances(), id);
    assertNotNull("the instance did not come back", found);
    assertEquals("condition:urn:drat:MapsDone", found.getWaitingOn());
  }

  public void testNoReasonComesBackAsNoReason() throws Exception {
    LuceneWorkflowInstanceRepository repo = repository();
    WorkflowInstance inst = instance();
    repo.addWorkflowInstance(inst);
    String id = inst.getId();
    repo.release();

    WorkflowInstance found = find(repository().getWorkflowInstances(), id);
    assertNotNull(found);
    assertNull(found.getWaitingOn());
  }

  public void testTheReasonIsUpdated() throws Exception {
    LuceneWorkflowInstanceRepository repo = repository();
    WorkflowInstance inst = instance();
    inst.setWaitingOn("task:urn:drat:RepoCrawler");
    repo.addWorkflowInstance(inst);
    inst.setWaitingOn("condition:urn:drat:MapsSettling");
    repo.updateWorkflowInstance(inst);
    String id = inst.getId();
    repo.release();

    assertEquals("condition:urn:drat:MapsSettling",
        find(repository().getWorkflowInstances(), id).getWaitingOn());
  }

  private WorkflowInstance find(List<?> instances, String id) {
    for (Object o : instances) {
      WorkflowInstance inst = (WorkflowInstance) o;
      if (id.equals(inst.getId())) {
        return inst;
      }
    }
    return null;
  }

  private WorkflowInstance instance() {
    WorkflowInstance inst = new WorkflowInstance();
    Workflow workflow = new Workflow();
    workflow.setId("urn:oodt:w");
    workflow.setName("test workflow");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:oodt:t");
    task.setTaskName("test task");
    task.setTaskInstanceClassName(
        "org.apache.oodt.cas.workflow.examples.NoOpTask");
    task.setTaskConfig(new org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration());
    workflow.getTasks().add(task);
    inst.setWorkflow(workflow);
    inst.setCurrentTaskId("urn:oodt:t");
    inst.setStatus("Queued");
    inst.setSharedContext(new Metadata());
    return inst;
  }

  private LuceneWorkflowInstanceRepository repository() {
    return new LuceneWorkflowInstanceRepository(path, 20);
  }
}
