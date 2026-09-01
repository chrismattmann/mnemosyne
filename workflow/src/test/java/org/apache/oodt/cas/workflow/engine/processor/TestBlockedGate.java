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

import java.util.Calendar;
import java.util.logging.Level;
import java.util.logging.LogManager;

import junit.framework.TestCase;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * A blocked task waits for its back-off, and then for its conditions.
 */
public class TestBlockedGate extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  public TestBlockedGate() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  /**
   * The reported hazard: the back-off elapsing was the only test, so a task
   * that bailed because its gate had not opened ran anyway once the clock ran
   * out -- a gate that delays rather than one that holds.
   */
  public void testABlockedTaskWaitsForItsConditionsNotOnlyItsClock()
      throws Exception {
    TaskProcessor blocked = blockedLongAgo(false);
    assertTrue("a task ran with its conditions unmet",
        blocked.getRunnableWorkflowProcessors().isEmpty());
  }

  /** Once they pass, the elapsed back-off lets it through. */
  public void testABlockedTaskRunsOnceItsConditionsPass() throws Exception {
    TaskProcessor blocked = blockedLongAgo(true);
    assertEquals(1, blocked.getRunnableWorkflowProcessors().size());
  }

  /** And before the back-off elapses it waits regardless. */
  public void testABlockedTaskWaitsOutItsBackOff() throws Exception {
    TaskProcessor blocked = blockedNow(true);
    assertTrue(blocked.getRunnableWorkflowProcessors().isEmpty());
  }

  private TaskProcessor blockedLongAgo(final boolean conditionsPass)
      throws Exception {
    return blocked(conditionsPass, -10);
  }

  private TaskProcessor blockedNow(final boolean conditionsPass)
      throws Exception {
    return blocked(conditionsPass, 0);
  }

  private TaskProcessor blocked(final boolean conditionsPass, int minutesAgo)
      throws Exception {
    WorkflowLifecycleManager manager = new WorkflowLifecycleManager(LIFECYCLE);
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("urn:oodt:blocked");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:oodt:task");
    task.setTaskConfig(new WorkflowTaskConfiguration());
    Graph graph = new Graph();
    graph.setTask(task);
    ParentChildWorkflow workflow = new ParentChildWorkflow(graph);
    workflow.getTasks().add(task);
    inst.setParentChildWorkflow(workflow);
    inst.setCurrentTaskId(task.getTaskId());
    inst.setSharedContext(new Metadata());

    TaskProcessor processor = new TaskProcessor(manager, inst) {
      @Override
      protected boolean passedPreConditions() {
        return conditionsPass;
      }
    };
    org.apache.oodt.cas.workflow.lifecycle.WorkflowState state = manager
        .getDefaultLifecycle().createState("Blocked", "waiting", "");
    Calendar when = Calendar.getInstance();
    when.add(Calendar.MINUTE, minutesAgo);
    state.setStartTime(when.getTime());
    processor.setState(state);
    return processor;
  }
}
