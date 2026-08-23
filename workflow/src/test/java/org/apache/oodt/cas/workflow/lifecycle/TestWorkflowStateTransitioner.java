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

package org.apache.oodt.cas.workflow.lifecycle;

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

//JDK imports
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.logging.Level;
import java.util.logging.LogManager;

//JUnit imports
import junit.framework.TestCase;

/**
 * Choosing the next state from what the lifecycle declares.
 *
 * Where a workflow can go next used to be a chain of string comparisons inside
 * WorkflowProcessor, so changing the state machine meant changing Java. A
 * lifecycle can now say it: each state names the states that may follow it,
 * each of those may be guarded, and stage priority decides between the ones
 * that are eligible at the same moment.
 *
 * The important negative case is that a lifecycle declaring nothing produces
 * no answer here at all, so the hard-coded chain still runs and existing
 * deployments behave as they always have.
 *
 * @author mattmann
 * @author bfoster
 */
public class TestWorkflowStateTransitioner extends TestCase {

  private static final String STATE_MACHINE =
      "./src/main/resources/examples/wengine/statemachine/wengine-lifecycle.xml";

  private static final String PLAIN =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private WorkflowLifecycleManager manager;

  private WorkflowStateTransitioner transitioner;

  private File scratch;

  public TestWorkflowStateTransitioner() {
    LogManager.getLogManager().getLogger("").setLevel(Level.OFF);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.manager = new WorkflowLifecycleManager(STATE_MACHINE);
    this.transitioner = new WorkflowStateTransitioner(this.manager);
  }

  @Override
  protected void tearDown() throws Exception {
    if (this.scratch != null) {
      deleteRecursively(this.scratch);
      this.scratch = null;
    }
    super.tearDown();
  }

  // ---- the declared machine ----------------------------------------------

  public void testUnconditionalTransitionIsTaken() {
    WorkflowInstance inst = instanceInState("Null", "initial");
    assertEquals("Loaded", transitioner.nextState(inst).getName());
  }

  /**
   * Both states reachable from Queued are structurally legal. Which one is
   * taken is decided by the guard on one of them and the priority of the
   * stages they sit in, not by an if statement.
   */
  public void testGuardedStateIsSkippedWhenItsPreConditionFails() {
    WorkflowInstance inst = instanceInState("Queued", "waiting");

    WorkflowState next = transitioner.nextState(inst);
    assertEquals("without staged inputs the workflow waits",
        "Blocked", next.getName());
  }

  public void testHigherPriorityStageWinsOnceItsGuardPasses() {
    WorkflowInstance inst = instanceInState("Queued", "waiting");
    inst.getSharedContext().addMetadata("InputsStaged", "true");

    WorkflowState next = transitioner.nextState(inst);
    assertEquals("running outranks waiting, so staged inputs run",
        "PreConditionEval", next.getName());
  }

  /**
   * A key present with the wrong value is not the same as the key being
   * present, or the guard would let anything through.
   */
  public void testPreConditionComparesTheValueNotJustTheKey() {
    WorkflowInstance inst = instanceInState("Queued", "waiting");
    inst.getSharedContext().addMetadata("InputsStaged", "false");

    assertEquals("Blocked", transitioner.nextState(inst).getName());
  }

  public void testTerminalStateGoesNowhere() {
    assertNull(transitioner.nextState(instanceInState("Success", "done")));
  }

  /**
   * The state it came from is recorded, which is what the prevState field on
   * WorkflowState has always been for.
   */
  public void testPreviousStateIsRecorded() {
    WorkflowInstance inst = instanceInState("Null", "initial");
    WorkflowState next = transitioner.nextState(inst);

    assertNotNull(next.getPrevState());
    assertEquals("Null", next.getPrevState().getName());
    assertNotNull(next.getStartTime());
  }

  /**
   * The instance carries a copy of its state built from a name, a category and
   * a message; the transitions have to be read from the lifecycle's own
   * declaration rather than from that copy.
   */
  public void testTransitionsAreReadFromTheLifecycleNotTheInstanceCopy() {
    WorkflowInstance inst = instanceInState("Null", "initial");
    assertTrue("the instance's own state carries no transitions",
        inst.getState().getNextStateNames().isEmpty());
    assertNotNull(transitioner.nextState(inst));
  }

  // ---- selecting the lifecycle -------------------------------------------

  /**
   * A workflow bound to its own lifecycle follows that one. Under the default
   * lifecycle, Loaded is followed by Queued; under the workflow's own, it runs
   * straight away.
   */
  public void testWorkflowsOwnLifecycleIsUsed() {
    WorkflowInstance defaultInst = instanceInState("Loaded", "initial");
    assertEquals("Queued", transitioner.nextState(defaultInst).getName());

    WorkflowInstance boundInst = instanceInState("Loaded", "initial");
    boundInst.getWorkflow().setId("urn:oodt:ImmediateWorkflow");
    assertEquals("Executing", transitioner.nextState(boundInst).getName());
  }

  // ---- staying put -------------------------------------------------------

  /**
   * The case that keeps every existing deployment working: a lifecycle with
   * no transitions in it declines to answer, and the caller falls back.
   */
  public void testLifecycleWithoutTransitionsDeclinesToAnswer()
      throws Exception {
    WorkflowLifecycleManager plain = new WorkflowLifecycleManager(PLAIN);
    WorkflowStateTransitioner plainTransitioner =
        new WorkflowStateTransitioner(plain);

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(new Workflow());
    inst.setState(plain.getDefaultLifecycle()
        .createState("Null", "initial", ""));

    assertFalse(plainTransitioner.declaresTransitions(inst));
    assertNull(plainTransitioner.nextState(inst));
  }

  public void testDeclaresTransitionsIsTrueForADeclaredState() {
    assertTrue(transitioner
        .declaresTransitions(instanceInState("Null", "initial")));
    assertFalse(transitioner
        .declaresTransitions(instanceInState("Success", "done")));
  }

  /**
   * A transition naming a state the lifecycle does not define is ignored
   * rather than fatal, so one bad name does not take the engine down.
   */
  public void testTransitionToAnUnknownStateIsIgnored() throws Exception {
    WorkflowStateTransitioner broken = transitionerFor(
        "<default><stage name=\"initial\">"
        + "<status name=\"Null\"><next state=\"NoSuchState\"/></status>"
        + "</stage></default>");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(new Workflow());
    inst.setState(stateNamed("Null"));

    assertTrue(broken.declaresTransitions(inst));
    assertNull(broken.nextState(inst));
  }

  /**
   * A state that names itself as its own successor means stay put, not churn.
   */
  public void testSelfTransitionMeansStayPut() throws Exception {
    WorkflowStateTransitioner selfLoop = transitionerFor(
        "<default><stage name=\"initial\">"
        + "<status name=\"Null\"><next state=\"Null\"/></status>"
        + "</stage></default>");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(new Workflow());
    inst.setState(stateNamed("Null"));

    assertNull(selfLoop.nextState(inst));
  }

  /**
   * Every reachable state guarded, and none of the guards passing, leaves the
   * workflow where it is. This is the difference between a transition that is
   * legal and one that should be taken now.
   */
  public void testNoEligibleCandidateLeavesTheStateAlone() throws Exception {
    WorkflowStateTransitioner guarded = transitionerFor(
        "<default><stage name=\"initial\">"
        + "<status name=\"Null\"><next state=\"Loaded\"/></status>"
        + "<status name=\"Loaded\">"
        + "<precondition class=\"org.apache.oodt.cas.workflow.lifecycle."
        + "MetadataPreCondition\">"
        + "<property name=\"key\" value=\"NeverSet\"/>"
        + "</precondition></status>"
        + "</stage></default>");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(new Workflow());
    inst.setSharedContext(new Metadata());
    inst.setState(stateNamed("Null"));

    assertTrue("the transition is declared", guarded.declaresTransitions(inst));
    assertNull("but its guard does not pass", guarded.nextState(inst));

    inst.getSharedContext().addMetadata("NeverSet", "now it is");
    assertEquals("Loaded", guarded.nextState(inst).getName());
  }

  public void testNullsAreTolerated() {
    assertNull(transitioner.nextState(null));
    assertNull(transitioner.nextState(new WorkflowInstance()));
    assertFalse(transitioner.declaresTransitions(null));
    assertNull(new WorkflowStateTransitioner(null)
        .nextState(instanceInState("Null", "initial")));
  }

  // ---- priority ----------------------------------------------------------

  /**
   * Equal priority falls back to the order the transitions were declared in,
   * so a lifecycle that sets no priorities is still predictable rather than
   * depending on how a sort happened to arrange things.
   */
  public void testEqualPriorityFallsBackToDeclarationOrder() throws Exception {
    WorkflowStateTransitioner tied = transitionerFor(
        "<default>"
        + "<stage name=\"initial\">"
        + "<status name=\"Null\">"
        + "<next state=\"Second\"/><next state=\"First\"/></status>"
        + "</stage>"
        + "<stage name=\"a\" priority=\"3\">"
        + "<status name=\"First\"/></stage>"
        + "<stage name=\"b\" priority=\"3\">"
        + "<status name=\"Second\"/></stage>"
        + "</default>");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(new Workflow());
    inst.setState(stateNamed("Null"));

    assertEquals("Second", tied.nextState(inst).getName());
  }

  /**
   * Priority is consulted, not declaration order, when they disagree.
   */
  public void testPriorityBeatsDeclarationOrder() throws Exception {
    WorkflowStateTransitioner ranked = transitionerFor(
        "<default>"
        + "<stage name=\"initial\">"
        + "<status name=\"Null\">"
        + "<next state=\"Low\"/><next state=\"High\"/></status>"
        + "</stage>"
        + "<stage name=\"a\" priority=\"1\">"
        + "<status name=\"Low\"/></stage>"
        + "<stage name=\"b\" priority=\"99\">"
        + "<status name=\"High\"/></stage>"
        + "</default>");

    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(new Workflow());
    inst.setState(stateNamed("Null"));

    assertEquals("High", ranked.nextState(inst).getName());
  }

  // ---- helpers -----------------------------------------------------------

  private WorkflowInstance instanceInState(String name, String category) {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(new Workflow());
    inst.setSharedContext(new Metadata());
    inst.setState(manager.getDefaultLifecycle()
        .createState(name, category, "under test"));
    return inst;
  }

  private WorkflowState stateNamed(String name) {
    WorkflowState state = new WorkflowState();
    state.setName(name);
    return state;
  }

  private WorkflowStateTransitioner transitionerFor(String body)
      throws Exception {
    if (this.scratch == null) {
      this.scratch = java.nio.file.Files
          .createTempDirectory("state-transitioner").toFile();
    }
    File file = new File(this.scratch, "lifecycle-" + body.hashCode() + ".xml");
    Writer writer = new FileWriter(file);
    try {
      writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<cas:workflowlifecycles "
          + "xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\">"
          + body + "</cas:workflowlifecycles>");
    } finally {
      writer.close();
    }
    return new WorkflowStateTransitioner(
        new WorkflowLifecycleManager(file.getPath()));
  }

  private void deleteRecursively(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursively(child);
      }
    }
    file.delete();
  }
}
