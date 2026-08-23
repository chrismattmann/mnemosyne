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

package org.apache.oodt.cas.workflow.instrepo;

//OODT imports
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

//JDK imports
import java.util.List;

//JUnit imports
import junit.framework.TestCase;

/**
 * Category queries on the workflow instance repository.
 *
 * A scheduler wants the instances it can still act on, not every instance ever
 * recorded. Asking the repository that question directly is what keeps a long
 * running deployment from paging through finished work on every pass, which is
 * the situation this exists to avoid: hand a system several hundred workflows
 * at once and, as they complete, the finished ones come to dominate whatever
 * the repository returns first.
 *
 * These exercise the default implementation on the interface, which every
 * repository inherits unless it overrides with something cheaper.
 *
 * @author mattmann
 */
public class TestWorkflowInstanceCategoryQueries extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private MemoryWorkflowInstanceRepository repo;

  private WorkflowLifecycleManager lifecycleManager;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.repo = new MemoryWorkflowInstanceRepository(20);
    this.lifecycleManager = new WorkflowLifecycleManager(LIFECYCLE);
  }

  public void testByCategoryReturnsOnlyThatCategory() throws Exception {
    addInstance("done-1", "Success", "done");
    addInstance("live-1", "Loaded", "initial");

    List done = repo.getWorkflowInstancesByCategory("done");
    assertEquals(1, done.size());
    // The repository assigns its own ids on add, so identify by state.
    assertEquals("Success",
        ((WorkflowInstance) done.get(0)).getState().getName());
  }

  /**
   * The direction the scheduler needs: everything still workable.
   */
  public void testNotByCategoryExcludesThatCategory() throws Exception {
    addInstance("done-1", "Success", "done");
    addInstance("done-2", "Success", "done");
    addInstance("live-1", "Loaded", "initial");

    List live = repo.getWorkflowInstancesNotByCategory("done");
    assertEquals(1, live.size());
    assertEquals("Loaded",
        ((WorkflowInstance) live.get(0)).getState().getName());
  }

  /**
   * The two forms must partition the repository between them, with nothing
   * dropped or counted twice.
   */
  public void testTheTwoFormsPartitionTheRepository() throws Exception {
    addInstance("done-1", "Success", "done");
    addInstance("live-1", "Loaded", "initial");
    addInstance("live-2", "Queued", "waiting");

    int inCategory = repo.getWorkflowInstancesByCategory("done").size();
    int notInCategory = repo.getWorkflowInstancesNotByCategory("done").size();
    assertEquals(repo.getWorkflowInstances().size(), inCategory + notInCategory);
  }

  public void testEmptyRepositoryReturnsEmptyNotNull() throws Exception {
    assertNotNull(repo.getWorkflowInstancesByCategory("done"));
    assertTrue(repo.getWorkflowInstancesByCategory("done").isEmpty());
    assertNotNull(repo.getWorkflowInstancesNotByCategory("done"));
    assertTrue(repo.getWorkflowInstancesNotByCategory("done").isEmpty());
  }

  public void testUnknownCategoryMatchesNothingAndExcludesNothing()
      throws Exception {
    addInstance("live-1", "Loaded", "initial");

    assertTrue(repo.getWorkflowInstancesByCategory("nosuch").isEmpty());
    assertEquals(1, repo.getWorkflowInstancesNotByCategory("nosuch").size());
  }

  /**
   * An instance with no state cannot belong to a category, so it must not be
   * reported as being in one. The excluding form still returns it, since the
   * scheduler should see it rather than lose it silently.
   */
  public void testInstanceWithoutStateIsNotInAnyCategory() throws Exception {
    WorkflowInstance stateless = new WorkflowInstance();
    stateless.setId("no-state");
    repo.addWorkflowInstance(stateless);

    assertTrue(repo.getWorkflowInstancesByCategory("done").isEmpty());
    assertEquals(1, repo.getWorkflowInstancesNotByCategory("done").size());
  }

  private void addInstance(String id, String stateName, String category)
      throws Exception {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId(id);
    WorkflowState state = lifecycleManager.getDefaultLifecycle()
        .createState(stateName, category, "");
    inst.setState(state);
    repo.addWorkflowInstance(inst);
  }
}
