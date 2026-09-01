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
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * How long a blocked task waits before it is looked at again.
 */
public class TestBlockTimeElapse extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  public TestBlockTimeElapse() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  /** Seconds, said precisely. */
  public void testSecondsAreTakenAsSeconds() throws Exception {
    assertEquals(15L, processor("BlockTimeElapseSeconds", "15")
        .blockTimeElapseSeconds());
  }

  /**
   * A configuration that says BlockTimeElapse was written meaning minutes,
   * and still means minutes. Reinterpreting it would quietly change what
   * somebody's deployment does.
   */
  public void testTheOlderPropertyStillMeansMinutes() throws Exception {
    assertEquals(120L, processor("BlockTimeElapse", "2")
        .blockTimeElapseSeconds());
  }

  /** Seconds win when both are given, being the more exact of the two. */
  public void testSecondsWinOverMinutes() throws Exception {
    TaskProcessor processor = processor("BlockTimeElapse", "5");
    processor.getWorkflowInstance().getCurrentTask().getTaskConfig()
        .addConfigProperty("BlockTimeElapseSeconds", "3");
    assertEquals(3L, processor.blockTimeElapseSeconds());
  }

  /** The default is seconds now, not the two minutes the divide made it. */
  public void testTheDefaultIsSeconds() throws Exception {
    assertEquals(TaskProcessor.DEFAULT_BLOCK_SECONDS,
        processor(null, null).blockTimeElapseSeconds());
    assertEquals(2L, TaskProcessor.DEFAULT_BLOCK_SECONDS);
  }

  /** Nonsense is not a wait of zero. */
  public void testAnUnreadableValueFallsBackToTheDefault() throws Exception {
    assertEquals(TaskProcessor.DEFAULT_BLOCK_SECONDS,
        processor("BlockTimeElapseSeconds", "soon").blockTimeElapseSeconds());
  }

  /** No truncation: 119 seconds is 119 seconds, not one minute. */
  public void testElapsedTimeIsNotRoundedDownToMinutes() throws Exception {
    TaskProcessor processor = processor(null, null);
    Calendar when = Calendar.getInstance();
    when.add(Calendar.SECOND, -119);
    processor.getWorkflowInstance().getState().setStartTime(when.getTime());

    long elapsed = processor.secondsBlocked();
    assertTrue("expected about 119 seconds, got " + elapsed,
        elapsed >= 118 && elapsed <= 121);
  }

  /**
   * A state's start time is not written to the instance repository, so a
   * manager restarted while an instance was blocked rebuilds it without one.
   * Reading it threw, on the very path that describes an instance a restart
   * left behind.
   */
  public void testAMissingStartTimeIsStampedRatherThanThrown() throws Exception {
    TaskProcessor processor = processor(null, null);
    processor.getWorkflowInstance().getState().setStartTime(null);

    long elapsed = processor.secondsBlocked();

    assertTrue("a restart-orphaned block should start counting from now",
        elapsed >= 0 && elapsed <= 2);
    assertNotNull(processor.getWorkflowInstance().getState().getStartTime());
  }

  private TaskProcessor processor(String key, String value) throws Exception {
    WorkflowLifecycleManager manager = new WorkflowLifecycleManager(LIFECYCLE);
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("urn:oodt:blocked");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:oodt:task");
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    if (key != null) {
      config.addConfigProperty(key, value);
    }
    task.setTaskConfig(config);
    Graph graph = new Graph();
    graph.setTask(task);
    ParentChildWorkflow workflow = new ParentChildWorkflow(graph);
    workflow.getTasks().add(task);
    inst.setParentChildWorkflow(workflow);
    inst.setCurrentTaskId(task.getTaskId());
    inst.setSharedContext(new Metadata());
    TaskProcessor processor = new TaskProcessor(manager, inst);
    WorkflowState blocked = manager.getDefaultLifecycle()
        .createState("Blocked", "waiting", "");
    processor.setState(blocked);
    return processor;
  }
}
