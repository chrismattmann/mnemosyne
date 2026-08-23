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
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

//JDK imports
import java.util.logging.Level;
import java.util.logging.LogManager;

//JUnit imports
import junit.framework.TestCase;

/**
 * Which of the two state machines the processor obeys.
 *
 * There are now two: the chain of string comparisons in
 * {@link WorkflowProcessor#nextState()}, which is what every existing
 * deployment runs on, and whatever the lifecycle file declares. The declared
 * one is consulted first and only answers when the lifecycle actually says
 * something, so a deployment gets the new behaviour by editing its lifecycle
 * file and by no other means.
 *
 * @author mattmann
 */
public class TestWorkflowProcessorNextState extends TestCase {

  private static final String PLAIN =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String STATE_MACHINE =
      "./src/main/resources/examples/wengine/statemachine/wengine-lifecycle.xml";

  public TestWorkflowProcessorNextState() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  /**
   * A lifecycle that declares nothing leaves the hard-coded chain in charge,
   * which is the whole compatibility guarantee in one assertion.
   */
  public void testUndeclaredLifecycleKeepsTheHardCodedChain() throws Exception {
    TaskProcessor processor = processorFor(PLAIN);

    assertEquals("Null", processor.getWorkflowInstance().getState().getName());
    processor.nextState();
    assertEquals("Loaded", processor.getWorkflowInstance().getState()
        .getName());
    processor.nextState();
    assertEquals("Queued", processor.getWorkflowInstance().getState()
        .getName());
  }

  /**
   * With transitions declared, the file decides. Queued is the state where the
   * two machines visibly disagree: the chain reaches for PreConditionEval or
   * Success, the declared machine sends an instance with nothing staged to
   * Blocked.
   */
  public void testDeclaredTransitionsTakeOver() throws Exception {
    TaskProcessor processor = processorFor(STATE_MACHINE);
    WorkflowLifecycleManager manager = new WorkflowLifecycleManager(
        STATE_MACHINE);
    processor.getWorkflowInstance().setState(manager.getDefaultLifecycle()
        .createState("Queued", "waiting", ""));

    processor.nextState();

    assertEquals("Blocked", processor.getWorkflowInstance().getState()
        .getName());
  }

  /**
   * The choice is made per state, not per file: a state the lifecycle says
   * nothing about still falls through to the chain.
   */
  public void testUndeclaredStateInADeclaredLifecycleFallsBack()
      throws Exception {
    TaskProcessor processor = processorFor(STATE_MACHINE);
    WorkflowLifecycleManager manager = new WorkflowLifecycleManager(
        STATE_MACHINE);
    // Unknown is listed in the file but declares no transitions, and the
    // hard-coded chain has no branch for it either, so nothing moves.
    processor.getWorkflowInstance().setState(manager.getDefaultLifecycle()
        .createState("Unknown", "holding", ""));

    processor.nextState();

    assertEquals("Unknown", processor.getWorkflowInstance().getState()
        .getName());
  }

  /**
   * A guard that starts failing and later passes moves the workflow on,
   * without the processor having been told anything about the condition.
   */
  public void testGuardDecidesWhenTheWorkflowMovesOn() throws Exception {
    TaskProcessor processor = processorFor(STATE_MACHINE);
    WorkflowLifecycleManager manager = new WorkflowLifecycleManager(
        STATE_MACHINE);
    processor.getWorkflowInstance().setState(manager.getDefaultLifecycle()
        .createState("Queued", "waiting", ""));
    processor.getWorkflowInstance().getSharedContext()
        .addMetadata("InputsStaged", "true");

    processor.nextState();

    assertEquals("PreConditionEval", processor.getWorkflowInstance().getState()
        .getName());
  }

  private TaskProcessor processorFor(String lifecycleFile) throws Exception {
    WorkflowLifecycleManager manager = new WorkflowLifecycleManager(
        lifecycleFile);
    WorkflowInstance instance = new WorkflowInstance();
    instance.setId("urn:oodt:nextStateTest");
    instance.setParentChildWorkflow(new ParentChildWorkflow(new Graph()));
    instance.setSharedContext(new Metadata());
    return new TaskProcessor(manager, instance);
  }
}
