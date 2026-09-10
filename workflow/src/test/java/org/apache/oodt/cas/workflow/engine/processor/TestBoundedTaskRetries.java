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

import java.util.logging.Level;
import java.util.logging.LogManager;

import junit.framework.TestCase;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * A task that failed once is not necessarily a task that cannot be done.
 *
 * <p>
 * A failure used to be the end of it, so one transient fault lost a chunk for
 * good: a translation whose node was restarted, or which arrived while the
 * translation service was still loading its model. On a run of several hundred
 * chunks something is always being asked at a bad moment, and the run then
 * fails at the join, hours later, because one chunk of four hundred and
 * fifty-eight is missing.
 * </p>
 *
 * <p>
 * The engine cannot tell a transient fault from a permanent one -- a missing
 * input fails exactly like a service that was not ready -- so the bound is
 * what protects the cluster, not any judgement about the failure.
 * </p>
 */
public class TestBoundedTaskRetries extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private WorkflowLifecycleManager manager;

  public TestBoundedTaskRetries() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  @Override
  protected void setUp() throws Exception {
    manager = new WorkflowLifecycleManager(LIFECYCLE);
  }

  /**
   * Nothing changes for a deployment that has not asked for retries, which is
   * every deployment that exists today.
   */
  public void testWithoutConfigurationAFailureIsStillFinal() throws Exception {
    TaskProcessor task = taskWith(null);
    assertEquals("Failure", task.failureOrRetry("it broke").getName());
  }

  public void testZeroRetriesIsTheSameAsNotAskingForAny() throws Exception {
    TaskProcessor task = taskWith("0");
    assertEquals("Failure", task.failureOrRetry("it broke").getName());
  }

  /** With retries configured, a failure goes back in the queue. */
  public void testAFailureIsRequeuedWhileRetriesRemain() throws Exception {
    TaskProcessor task = taskWith("2");

    WorkflowState first = task.failureOrRetry("the service was not ready");
    assertEquals("Queued", first.getName());
    assertEquals("waiting", first.getCategory().getName());
  }

  /** And the bound is honoured: two retries means three attempts, then done. */
  public void testItStopsAfterTheConfiguredNumber() throws Exception {
    TaskProcessor task = taskWith("2");

    assertEquals("Queued", task.failureOrRetry("first").getName());
    assertEquals("Queued", task.failureOrRetry("second").getName());
    assertEquals("a third failure is past the bound and must be final",
        "Failure", task.failureOrRetry("third").getName());
    assertEquals("and it stays final",
        "Failure", task.failureOrRetry("fourth").getName());
  }

  /**
   * The count lives on the instance, not in the processor, so a manager
   * restart cannot hand a task a fresh set of attempts. A task that fails
   * forever would otherwise be retried forever, a few at a time, by whichever
   * manager happened to reload it.
   */
  public void testTheCountIsCarriedOnTheInstance() throws Exception {
    TaskProcessor task = taskWith("3");
    task.failureOrRetry("first");
    task.failureOrRetry("second");

    Metadata carried = task.getWorkflowInstance().getSharedContext();
    assertEquals("2", carried.getMetadata(TaskProcessor.RETRIES_USED));

    // A different processor over the same instance, which is what a reload
    // produces, has to see the attempts already spent.
    TaskProcessor reloaded =
        new TaskProcessor(manager, task.getWorkflowInstance());
    assertEquals("Queued", reloaded.failureOrRetry("third").getName());
    assertEquals("the fourth is past the bound of three",
        "Failure", reloaded.failureOrRetry("fourth").getName());
  }

  /** A count that is nonsense is not a licence for unlimited retries. */
  public void testAnUnreadableCountDoesNotGrantExtraAttempts()
      throws Exception {
    TaskProcessor task = taskWith("1");
    task.getWorkflowInstance().getSharedContext()
        .replaceMetadata(TaskProcessor.RETRIES_USED, "not a number");

    assertEquals("Queued", task.failureOrRetry("first").getName());
    assertEquals("Failure", task.failureOrRetry("second").getName());
  }

  public void testAnUnreadableMaxIsTreatedAsNoRetries() throws Exception {
    assertEquals("Failure",
        taskWith("plenty").failureOrRetry("it broke").getName());
    assertEquals("Failure",
        taskWith("-1").failureOrRetry("it broke").getName());
  }

  // --------------------------------------------------------------- setup ---

  private TaskProcessor taskWith(String maxRetries) throws Exception {
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:oodt:retryable");
    task.setTaskName("Retryable Task");
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    if (maxRetries != null) {
      config.addConfigProperty(TaskProcessor.MAX_RETRIES, maxRetries);
    }
    task.setTaskConfig(config);

    Graph graph = new Graph();
    graph.setTask(task);
    ParentChildWorkflow workflow = new ParentChildWorkflow(graph);
    workflow.getTasks().add(task);

    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("urn:oodt:instance");
    inst.setParentChildWorkflow(workflow);
    inst.setCurrentTaskId(task.getTaskId());
    inst.setSharedContext(new Metadata());
    return new TaskProcessor(manager, inst);
  }
}
