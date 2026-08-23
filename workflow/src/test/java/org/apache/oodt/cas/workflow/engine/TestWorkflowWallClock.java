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

package org.apache.oodt.cas.workflow.engine;

//OODT imports
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.commons.util.DateConvert;

//JDK imports
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.LogManager;

//JUnit imports
import junit.framework.TestCase;

/**
 * Wall clock computation at both the workflow and the current-task level.
 *
 * The two levels are computed by near-identical code that had drifted apart:
 * the task level guarded against a start that falls after the end, the
 * workflow level did not, so the same inputs produced a sane 0.0 at one level
 * and a negative duration at the other. Neither level tolerated an
 * unparseable end date.
 *
 * @author mattmann
 */
public class TestWorkflowWallClock extends TestCase {

  private static final double DELTA = 0.0001;

  public TestWorkflowWallClock() {
    // The methods under test log at WARNING when they reject a start date
    // that falls after the end date, which is expected here.
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  // ---- workflow level ---------------------------------------------------

  /**
   * A workflow whose recorded end precedes its start must not report a
   * negative duration. The task level already refused this; the workflow
   * level returned the negative difference.
   */
  public void testWorkflowStartAfterEndIsNotNegative() throws Exception {
    WorkflowInstance inst = new WorkflowInstance();
    Date now = new Date();
    inst.setEndDateTimeIsoStr(DateConvert.isoFormat(now));
    inst.setStartDateTimeIsoStr(DateConvert.isoFormat(
        new Date(now.getTime() + 60000)));

    double mins = ThreadPoolWorkflowEngine.getWallClockMinutes(inst);
    assertTrue("wall clock must never be negative, got " + mins, mins >= 0.0);
    assertEquals(0.0, mins, DELTA);
  }

  /**
   * An end date that cannot be parsed should degrade to "still running"
   * rather than throwing.
   */
  public void testWorkflowUnparseableEndDateDoesNotThrow() throws Exception {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setStartDateTimeIsoStr(DateConvert.isoFormat(
        new Date(System.currentTimeMillis() - 120000)));
    inst.setEndDateTimeIsoStr("not-a-date");

    double mins = ThreadPoolWorkflowEngine.getWallClockMinutes(inst);
    assertTrue("an unparseable end date should be treated as still running",
        mins > 0.0);
  }

  public void testWorkflowElapsedIsComputed() throws Exception {
    WorkflowInstance inst = new WorkflowInstance();
    Date start = new Date(System.currentTimeMillis() - 120000);
    inst.setStartDateTimeIsoStr(DateConvert.isoFormat(start));
    inst.setEndDateTimeIsoStr(DateConvert.isoFormat(
        new Date(start.getTime() + 120000)));

    assertEquals(2.0, ThreadPoolWorkflowEngine.getWallClockMinutes(inst), 0.05);
  }

  public void testWorkflowWithoutStartIsZero() {
    assertEquals(0.0,
        ThreadPoolWorkflowEngine.getWallClockMinutes(new WorkflowInstance()),
        DELTA);
  }

  public void testNullInstanceIsZero() {
    // Cast required: both methods are overloaded on String and WorkflowInstance.
    assertEquals(0.0, ThreadPoolWorkflowEngine
        .getWallClockMinutes((WorkflowInstance) null), DELTA);
    assertEquals(0.0, ThreadPoolWorkflowEngine
        .getCurrentTaskWallClockMinutes((WorkflowInstance) null), DELTA);
  }

  // ---- current task level -----------------------------------------------

  /**
   * The current-task dates are stored on the task itself, not on the instance,
   * so setCurrentTaskStartDateTimeIsoStr is a silent no-op unless the instance
   * already carries a task whose id matches currentTaskId.
   */
  private WorkflowInstance instanceWithCurrentTask() {
    WorkflowInstance inst = new WorkflowInstance();
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:oodt:wallClockTask");
    ParentChildWorkflow workflow = new ParentChildWorkflow(new Graph());
    workflow.getTasks().add(task);
    inst.setParentChildWorkflow(workflow);
    inst.setCurrentTaskId("urn:oodt:wallClockTask");
    return inst;
  }


  public void testCurrentTaskStartAfterEndIsNotNegative() throws Exception {
    WorkflowInstance inst = instanceWithCurrentTask();
    Date now = new Date();
    inst.setCurrentTaskEndDateTimeIsoStr(DateConvert.isoFormat(now));
    inst.setCurrentTaskStartDateTimeIsoStr(DateConvert.isoFormat(
        new Date(now.getTime() + 60000)));

    double mins = ThreadPoolWorkflowEngine.getCurrentTaskWallClockMinutes(inst);
    assertEquals(0.0, mins, DELTA);
  }

  /**
   * The task level shared the workflow level's intolerance of a bad end date.
   */
  public void testCurrentTaskUnparseableEndDateDoesNotThrow() throws Exception {
    WorkflowInstance inst = instanceWithCurrentTask();
    inst.setCurrentTaskStartDateTimeIsoStr(DateConvert.isoFormat(
        new Date(System.currentTimeMillis() - 120000)));
    inst.setCurrentTaskEndDateTimeIsoStr("not-a-date");

    double mins = ThreadPoolWorkflowEngine.getCurrentTaskWallClockMinutes(inst);
    assertTrue("an unparseable end date should be treated as still running",
        mins > 0.0);
  }

  public void testCurrentTaskElapsedIsComputed() throws Exception {
    WorkflowInstance inst = instanceWithCurrentTask();
    Date start = new Date(System.currentTimeMillis() - 90000);
    inst.setCurrentTaskStartDateTimeIsoStr(DateConvert.isoFormat(start));
    inst.setCurrentTaskEndDateTimeIsoStr(DateConvert.isoFormat(
        new Date(start.getTime() + 90000)));

    assertEquals(1.5,
        ThreadPoolWorkflowEngine.getCurrentTaskWallClockMinutes(inst), 0.05);
  }

  /**
   * A task still running has no end date; elapsed time is measured to now.
   * Uses a start safely in the past so the assertion does not race the clock.
   */
  public void testCurrentTaskStillRunningMeasuresToNow() throws Exception {
    WorkflowInstance inst = instanceWithCurrentTask();
    inst.setCurrentTaskStartDateTimeIsoStr(DateConvert.isoFormat(
        new Date(System.currentTimeMillis() - 60000)));

    assertTrue(ThreadPoolWorkflowEngine.getCurrentTaskWallClockMinutes(inst)
        > 0.0);
  }

  /**
   * Both levels treat the literal string "null" and the empty string as
   * absent, which is how they are persisted when unset.
   */
  public void testLiteralNullAndEmptyAreTreatedAsAbsent() throws Exception {
    WorkflowInstance inst = instanceWithCurrentTask();
    inst.setStartDateTimeIsoStr("null");
    assertEquals(0.0, ThreadPoolWorkflowEngine.getWallClockMinutes(inst), DELTA);

    inst.setStartDateTimeIsoStr("");
    assertEquals(0.0, ThreadPoolWorkflowEngine.getWallClockMinutes(inst), DELTA);

    inst.setCurrentTaskStartDateTimeIsoStr("null");
    assertEquals(0.0,
        ThreadPoolWorkflowEngine.getCurrentTaskWallClockMinutes(inst), DELTA);
  }

  /**
   * The two levels are the same calculation applied to different fields, so
   * identical inputs must produce identical answers.
   */
  public void testBothLevelsAgreeOnIdenticalInputs() throws Exception {
    Date start = new Date(System.currentTimeMillis() - 180000);
    String startStr = DateConvert.isoFormat(start);
    String endStr = DateConvert.isoFormat(new Date(start.getTime() + 180000));

    WorkflowInstance inst = instanceWithCurrentTask();
    inst.setStartDateTimeIsoStr(startStr);
    inst.setEndDateTimeIsoStr(endStr);
    inst.setCurrentTaskStartDateTimeIsoStr(startStr);
    inst.setCurrentTaskEndDateTimeIsoStr(endStr);

    assertEquals(ThreadPoolWorkflowEngine.getWallClockMinutes(inst),
        ThreadPoolWorkflowEngine.getCurrentTaskWallClockMinutes(inst), DELTA);
  }
}
