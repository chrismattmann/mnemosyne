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
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.util.AvroTypeFactory;

/**
 * Counting the times an instance was put off rather than run.
 */
public class TestTimesBlocked extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  public TestTimesBlocked() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  /** Nothing counted it before, in either engine. */
  public void testAnInstanceStartsAtZero() {
    assertEquals(0, instance().getTimesBlocked());
  }

  public void testRecordingCounts() {
    WorkflowInstance inst = instance();
    inst.recordBlocked();
    inst.recordBlocked();
    assertEquals(2, inst.getTimesBlocked());
  }

  /**
   * What the querier asks each pass to decide whether this was deferred.
   * A processor with conditions that have not passed is waiting; one with no
   * conditions passes them trivially and is not.
   */
  public void testAProcessorSaysWhenItIsWaitingOnConditions() throws Exception {
    assertTrue(processorWaitingOnConditions().isWaitingOnPreConditions());
    assertFalse(processor().isWaitingOnPreConditions());
  }

  /**
   * Counted every pass, because being deferred ten times is what being
   * deferred ten times looks like from outside.
   */
  public void testEachDeferralCounts() throws Exception {
    TaskProcessor processor = processorWaitingOnConditions();
    for (int pass = 0; pass < 3; pass++) {
      if (processor.isWaitingOnPreConditions()) {
        processor.getWorkflowInstance().recordBlocked();
      }
    }
    assertEquals(3, processor.getWorkflowInstance().getTimesBlocked());
  }

  /** Being sent to Blocked counts once, not once per look. */
  public void testEnteringBlockedCountsOnce() throws Exception {
    TaskProcessor processor = processor();
    setState(processor, "Queued", "waiting");
    int before = processor.getWorkflowInstance().getTimesBlocked();

    setState(processor, "Blocked", "waiting");
    setState(processor, "Blocked", "waiting");

    assertEquals(before + 1, processor.getWorkflowInstance().getTimesBlocked());
  }

  /** Running is not being deferred. */
  public void testProgressDoesNotCount() throws Exception {
    TaskProcessor processor = processor();
    setState(processor, "Null", "initial");

    processor.nextState();
    processor.nextState();

    assertEquals("moving from Null to Loaded to Queued is progress",
        0, processor.getWorkflowInstance().getTimesBlocked());
  }

  /** And it has to survive the trip to whatever is asking. */
  public void testItCrossesTheWire() {
    WorkflowInstance inst = instance();
    inst.recordBlocked();
    inst.recordBlocked();
    inst.recordBlocked();

    WorkflowInstance back = AvroTypeFactory.getWorkflowInstance(
        AvroTypeFactory.getAvroWorkflowInstance(inst));

    assertEquals(3, back.getTimesBlocked());
  }

  private TaskProcessor processor() throws Exception {
    return new TaskProcessor(new WorkflowLifecycleManager(LIFECYCLE),
        instance());
  }

  /**
   * A processor whose conditions do not pass. A processor with no conditions
   * passes them trivially, so it is never deferred and never counts.
   */
  private TaskProcessor processorWaitingOnConditions() throws Exception {
    return new TaskProcessor(new WorkflowLifecycleManager(LIFECYCLE),
        instance()) {
      @Override
      protected boolean passedPreConditions() {
        return false;
      }
    };
  }

  private WorkflowInstance instance() {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("urn:oodt:timesBlockedTest");
    inst.setParentChildWorkflow(new ParentChildWorkflow(new Graph()));
    inst.setSharedContext(new Metadata());
    return inst;
  }

  private void setState(WorkflowProcessor processor, String name,
      String category) throws Exception {
    processor.setState(processor.getLifecycleManager().getDefaultLifecycle()
        .createState(name, category, ""));
  }
}
