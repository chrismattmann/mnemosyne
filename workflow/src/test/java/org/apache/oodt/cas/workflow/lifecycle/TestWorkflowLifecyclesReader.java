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
import org.apache.oodt.cas.workflow.structs.Workflow;

//JDK imports
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;

//JUnit imports
import junit.framework.TestCase;

/**
 * Reading a lifecycle that describes its own state machine.
 *
 * Three things are new in the file format, all optional: a priority on a
 * stage, the states reachable from a state, and preconditions guarding entry
 * to one. Two things were broken and are fixed here: named lifecycles were
 * looked for inside the default element rather than beside it, and the
 * workflowId attribute was never read, so the per-workflow form documented in
 * the shipped examples could never take effect.
 *
 * @author mattmann
 */
public class TestWorkflowLifecyclesReader extends TestCase {

  private static final String STATE_MACHINE =
      "./src/main/resources/examples/wengine/statemachine/wengine-lifecycle.xml";

  private static final String PLAIN =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private WorkflowLifecycleManager manager;

  private File scratch;

  public TestWorkflowLifecyclesReader() {
    // Reading a deliberately broken file logs at SEVERE, which is the point
    // of those tests rather than a problem with them.
    LogManager.getLogManager().getLogger("").setLevel(Level.OFF);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.manager = new WorkflowLifecycleManager(STATE_MACHINE);
  }

  @Override
  protected void tearDown() throws Exception {
    if (this.scratch != null) {
      deleteRecursively(this.scratch);
      this.scratch = null;
    }
    super.tearDown();
  }

  // ---- what the format gained --------------------------------------------

  public void testStagePriorityIsRead() {
    WorkflowLifecycle lifecycle = manager.getDefaultLifecycle();
    assertEquals(20, lifecycle.getCategoryByName("running").getPriority());
    assertEquals(5, lifecycle.getCategoryByName("waiting").getPriority());
  }

  /**
   * A stage that declares no priority gets zero, so an existing file keeps a
   * single uniform priority and no transition is preferred over another.
   */
  public void testUndeclaredPriorityIsZero() throws Exception {
    assertEquals(0, manager.getDefaultLifecycle().getCategoryByName("holding")
        .getPriority());
    WorkflowLifecycleManager plain = new WorkflowLifecycleManager(PLAIN);
    for (Object stage : plain.getDefaultLifecycle().getStages()) {
      assertEquals(0, ((WorkflowLifecycleStage) stage).getPriority());
    }
  }

  /**
   * Declaration order is kept, because it is the tie-break of last resort
   * between candidates whose stages have equal priority.
   */
  public void testTransitionsAreReadInOrder() {
    WorkflowState queued = manager.getDefaultLifecycle()
        .getStateByName("Queued");
    assertEquals(2, queued.getNextStateNames().size());
    assertEquals("PreConditionEval", queued.getNextStateNames().get(0));
    assertEquals("Blocked", queued.getNextStateNames().get(1));
  }

  public void testTerminalStatesDeclareNoTransitions() {
    assertTrue(manager.getDefaultLifecycle().getStateByName("Success")
        .getNextStateNames().isEmpty());
  }

  public void testPreConditionAndItsConfigurationAreRead() {
    WorkflowState state = manager.getDefaultLifecycle()
        .getStateByName("PreConditionEval");
    assertEquals(1, state.getPreConditions().size());

    WorkflowState.AttachedPreCondition attached = state.getPreConditions()
        .get(0);
    assertEquals(MetadataPreCondition.class.getName(),
        attached.getClassName());
    assertTrue(attached.getPreCondition() instanceof MetadataPreCondition);
    assertEquals("InputsStaged", attached.getConfiguration()
        .getProperty("key"));
    assertEquals("true", attached.getConfiguration().getProperty("value"));
  }

  /**
   * A state carrying no preconditions can always be entered, which is what
   * makes a bare next declaration an unconditional transition.
   */
  public void testStateWithNoPreConditionsIsEnterable() {
    assertTrue(manager.getDefaultLifecycle().getStateByName("Blocked")
        .preConditionsMet(null));
  }

  /**
   * A precondition whose class cannot be loaded leaves the state it guards
   * unenterable. Failing the other way would let a typo in a class name look
   * like a guard that passed.
   */
  public void testUnloadablePreConditionIsNeverMet() throws Exception {
    File file = writeScratchFile("broken.xml",
        "<cas:workflowlifecycles xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\">"
        + "<default><stage name=\"initial\">"
        + "<status name=\"Null\"><next state=\"Loaded\"/></status>"
        + "<status name=\"Loaded\">"
        + "<precondition class=\"no.such.PreCondition\"/>"
        + "</status>"
        + "</stage></default></cas:workflowlifecycles>");

    WorkflowLifecycle lifecycle = new WorkflowLifecycleManager(file.getPath())
        .getDefaultLifecycle();
    WorkflowState loaded = lifecycle.getStateByName("Loaded");

    assertEquals(1, loaded.getPreConditions().size());
    assertNull(loaded.getPreConditions().get(0).getPreCondition());
    assertFalse(loaded.preConditionsMet(null));
  }

  // ---- what was broken ---------------------------------------------------

  /**
   * A named lifecycle sits beside the default element. The reader used to look
   * for it inside, so this form was silently ignored.
   */
  public void testNamedLifecycleBesideDefaultIsRead() {
    WorkflowLifecycle immediate = manager.getLifecycleByName("immediate");
    assertNotNull("a lifecycle declared beside <default> must be read",
        immediate);
    assertEquals(3, immediate.getStages().size());
  }

  /**
   * With the workflowId attribute unread, every lifecycle claimed to belong to
   * no workflow and the default was always chosen.
   */
  public void testLifecycleIsSelectedByWorkflowId() {
    Workflow workflow = new Workflow();
    workflow.setId("urn:oodt:ImmediateWorkflow");

    WorkflowLifecycle selected = manager.getLifecycleForWorkflow(workflow);
    assertEquals("immediate", selected.getName());
  }

  public void testWorkflowWithNoLifecycleOfItsOwnGetsTheDefault() {
    Workflow workflow = new Workflow();
    workflow.setId("urn:oodt:SomethingElse");

    assertEquals(WorkflowLifecycle.DEFAULT_LIFECYCLE,
        manager.getLifecycleForWorkflow(workflow).getName());
  }

  /**
   * Two stages sharing an order used to compare equal to the sorted set that
   * holds them, so the second was dropped without a word.
   */
  public void testStagesSharingAnOrderAreBothKept() {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle("test", null);
    lifecycle.addStage(new WorkflowLifecycleStage("first", null, 1));
    lifecycle.addStage(new WorkflowLifecycleStage("second", null, 1));

    assertEquals(2, lifecycle.getStages().size());
    assertNotNull(lifecycle.getCategoryByName("first"));
    assertNotNull(lifecycle.getCategoryByName("second"));
  }

  // ---- imports -----------------------------------------------------------

  public void testImportedLifecyclesAreAvailable() {
    assertNotNull(manager.getLifecycleByName("immediate"));
    assertNotNull(manager.getLifecycleByName("minimal"));
  }

  /**
   * The importing file's own default wins, so a deployment can take a shared
   * set of lifecycles and still say which one it starts from.
   */
  public void testLocalDefaultBeatsAnImportedOne() {
    assertEquals(WorkflowLifecycle.DEFAULT_LIFECYCLE,
        manager.getDefaultLifecycle().getName());
  }

  /**
   * Read on its own, the shared file's default attribute is what marks its
   * default, with no default element anywhere in it.
   */
  public void testDefaultAttributeStandsInForTheDefaultElement()
      throws Exception {
    WorkflowLifecycleManager shared = new WorkflowLifecycleManager(
        "./src/main/resources/examples/wengine/statemachine/"
        + "wengine-shared-lifecycles.xml");
    assertEquals("minimal", shared.getDefaultLifecycle().getName());
  }

  /**
   * Two files importing each other must stop rather than recurse until the
   * stack runs out.
   */
  public void testMutualImportsTerminate() throws Exception {
    writeScratchFile("a.xml",
        "<cas:workflowlifecycles xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\">"
        + "<import file=\"b.xml\"/>"
        + "<default><stage name=\"initial\">"
        + "<status name=\"Null\"/></stage></default>"
        + "</cas:workflowlifecycles>");
    writeScratchFile("b.xml",
        "<cas:workflowlifecycles xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\">"
        + "<import file=\"a.xml\"/>"
        + "<lifecycle name=\"fromB\"><stage name=\"done\">"
        + "<status name=\"Success\"/></stage></lifecycle>"
        + "</cas:workflowlifecycles>");

    WorkflowLifecycleManager cyclic = new WorkflowLifecycleManager(
        new File(this.scratch, "a.xml").getPath());
    assertNotNull(cyclic.getDefaultLifecycle());
    assertNotNull(cyclic.getLifecycleByName("fromB"));
  }

  /**
   * A file with no default at all is a mistake worth reporting rather than
   * one to paper over, since every workflow needs somewhere to start.
   */
  public void testFileWithNoDefaultIsRejected() throws Exception {
    File file = writeScratchFile("nodefault.xml",
        "<cas:workflowlifecycles xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\">"
        + "<lifecycle name=\"only\"><stage name=\"done\">"
        + "<status name=\"Success\"/></stage></lifecycle>"
        + "</cas:workflowlifecycles>");
    try {
      new WorkflowLifecycleManager(file.getPath());
      fail("a lifecycle file with no default should be rejected");
    } catch (InstantiationException expected) {
      // as intended
    }
  }

  // ---- what must not have changed ----------------------------------------

  /**
   * The lifecycle files that exist today declare no transitions, no
   * preconditions and no priorities, and must read exactly as they did.
   */
  public void testExistingFileReadsUnchanged() throws Exception {
    WorkflowLifecycleManager plain = new WorkflowLifecycleManager(PLAIN);
    WorkflowLifecycle lifecycle = plain.getDefaultLifecycle();

    assertEquals(7, lifecycle.getStages().size());
    for (Object stage : lifecycle.getStages()) {
      for (WorkflowState state : ((WorkflowLifecycleStage) stage)
          .getStates()) {
        assertTrue("no existing state declares transitions",
            state.getNextStateNames().isEmpty());
        assertTrue("no existing state declares preconditions",
            state.getPreConditions().isEmpty());
      }
    }
    assertEquals("Uninitialized State",
        lifecycle.getStateByName("Null").getDescription());
  }

  /**
   * The pre-0.4 form, where a status is element text rather than a name
   * attribute, still reads.
   */
  public void testBackwardsCompatibleStatusFormStillReads() throws Exception {
    WorkflowLifecycleManager old = new WorkflowLifecycleManager(
        "./src/main/resources/examples/workflow-lifecycle.xml");
    List<WorkflowState> states = old.getDefaultLifecycle()
        .getCategoryByName("workflow_start").getStates();
    assertEquals(2, states.size());
  }

  // ---- helpers -----------------------------------------------------------

  private File writeScratchFile(String name, String body) throws Exception {
    if (this.scratch == null) {
      this.scratch = java.nio.file.Files
          .createTempDirectory("lifecycle-reader").toFile();
    }
    File file = new File(this.scratch, name);
    Writer writer = new FileWriter(file);
    try {
      writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + body);
    } finally {
      writer.close();
    }
    return file;
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
