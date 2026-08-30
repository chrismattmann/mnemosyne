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

package org.apache.oodt.cas.workflow.repository;

import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A workflow manager builds this repository twice: once for itself and once for
 * the engine's processor queue. With a randomly generated redirector id the two
 * disagreed, so the manager created instances referencing ids the engine's
 * repository had never minted, and every nested sub-workflow failed with
 * "undefined task".
 */
public class TestRedirectorIdsAreStable {

  private static final String MODEL_DIR = "src/test/resources/wengine-e2e";

  @Test
  public void tworepositoriesOverTheSameFilesAgreeOnRedirectorIds()
      throws Exception {
    List<String> first = redirectorIdsFrom(repository());
    List<String> second = redirectorIdsFrom(repository());

    assertFalse("the fixtures should produce at least one redirector",
        first.isEmpty());
    assertEquals("two repositories over the same files must agree", first, second);
  }

  /** The id has to describe what it redirects to, not when it was built. */
  @Test
  public void aredirectorIdNamesTheWorkflowItReachesFor() throws Exception {
    List<String> ids = redirectorIdsFrom(repository());

    for (String id : ids) {
      assertTrue("id should be derived, not random: " + id,
          id.startsWith("redirector-urn:"));
    }
  }

  /**
   * The failure this produced: the second repository rejects a sub-workflow
   * built against the first one's ids.
   */
  @Test
  public void asubWorkflowBuiltAgainstOneRepositoryRegistersInAnother()
      throws Exception {
    PackagedWorkflowRepository fromManager = repository();
    PackagedWorkflowRepository fromEngine = repository();

    WorkflowTask redirector = null;
    for (Object o : fromManager.getWorkflows()) {
      for (Object t : ((Workflow) o).getTasks()) {
        WorkflowTask task = (WorkflowTask) t;
        if (task.getTaskId().startsWith("redirector-")) {
          redirector = task;
          break;
        }
      }
      if (redirector != null) {
        break;
      }
    }
    assertNotNull("expected a redirector task in the fixtures", redirector);

    Workflow generated = new Workflow();
    generated.setName("Task Workflow-Redirector Task");
    generated.setId("task-workflow-under-test");
    generated.setTasks(new ArrayList<WorkflowTask>(
        Collections.singletonList(redirector)));

    // Throws "Reference in new workflow ... to undefined task" when the two
    // repositories do not agree.
    assertNotNull(fromEngine.addWorkflow(generated));
  }

  private PackagedWorkflowRepository repository() throws Exception {
    return new PackagedWorkflowRepository(
        Arrays.asList(new File(MODEL_DIR).listFiles()));
  }

  private List<String> redirectorIdsFrom(PackagedWorkflowRepository repo)
      throws Exception {
    List<String> ids = new ArrayList<String>();
    for (Object o : repo.getWorkflows()) {
      for (Object t : ((Workflow) o).getTasks()) {
        String id = ((WorkflowTask) t).getTaskId();
        if (id != null && id.startsWith("redirector-")) {
          ids.add(id);
        }
      }
    }
    Collections.sort(ids);
    return ids;
  }
}
