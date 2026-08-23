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

package org.apache.oodt.cas.workflow.engine.processor;

//OODT imports
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

//JDK imports
import java.util.List;
import java.util.Vector;

//JUnit imports
import junit.framework.TestCase;

/**
 * Selecting sub-processors by state.
 *
 * getWorkflowProcessorsByState compared a WorkflowState against a String.
 * WorkflowState.equals type-checks its argument and answers false for anything
 * that is not a WorkflowState, so the comparison was false every time and the
 * method returned an empty list for every state, always.
 *
 * That is not a cosmetic bug. isDone() asks this method which children have
 * failed; it was told none, and concluded from "every child is in the done
 * category" -- which Failure is -- that the workflow had succeeded. A workflow
 * whose task threw an exception was reported as Success.
 *
 * @author mattmann
 */
public class TestWorkflowProcessorHelperSelection extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private WorkflowLifecycleManager lifecycleManager;

  private WorkflowProcessorHelper helper;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.lifecycleManager = new WorkflowLifecycleManager(LIFECYCLE);
    this.helper = new WorkflowProcessorHelper(this.lifecycleManager);
  }

  /**
   * The bug, stated directly.
   */
  public void testFailedProcessorsAreFound() {
    List<WorkflowProcessor> processors = new Vector<WorkflowProcessor>();
    processors.add(processorInState("Failure", "done"));
    processors.add(processorInState("Success", "done"));

    List<WorkflowProcessor> failed = helper.getWorkflowProcessorsByState(
        processors, "Failure");

    assertEquals("the failed processor must be found", 1, failed.size());
    assertEquals("Failure",
        failed.get(0).getWorkflowInstance().getState().getName());
  }

  public void testSelectionIsByTheStateAsked() {
    List<WorkflowProcessor> processors = new Vector<WorkflowProcessor>();
    processors.add(processorInState("Failure", "done"));
    processors.add(processorInState("Success", "done"));
    processors.add(processorInState("Success", "done"));

    assertEquals(2,
        helper.getWorkflowProcessorsByState(processors, "Success").size());
    assertEquals(0,
        helper.getWorkflowProcessorsByState(processors, "Queued").size());
  }

  public void testEmptyInputYieldsEmptyOutput() {
    assertTrue(helper.getWorkflowProcessorsByState(
        new Vector<WorkflowProcessor>(), "Failure").isEmpty());
  }

  /**
   * A processor whose state was never set must not match, and must not throw.
   */
  public void testProcessorWithoutAStateIsSkipped() {
    List<WorkflowProcessor> processors = new Vector<WorkflowProcessor>();
    WorkflowProcessor stateless = processorInState("Failure", "done");
    stateless.getWorkflowInstance().setState(null);
    processors.add(stateless);
    processors.add(processorInState("Failure", "done"));

    assertEquals(1,
        helper.getWorkflowProcessorsByState(processors, "Failure").size());
  }

  private WorkflowProcessor processorInState(String name, String category) {
    WorkflowInstance instance = new WorkflowInstance();
    instance.setParentChildWorkflow(new ParentChildWorkflow(new Graph()));
    TaskProcessor processor = new TaskProcessor(lifecycleManager, instance);
    processor.getWorkflowInstance().setState(lifecycleManager
        .getDefaultLifecycle().createState(name, category, "under test"));
    return processor;
  }
}
