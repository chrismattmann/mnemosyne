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
import org.apache.oodt.cas.workflow.engine.ChangeType;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

//JDK imports
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.LogManager;

//JUnit imports
import junit.framework.TestCase;

/**
 * A parent reacting to its children without waiting to be asked.
 *
 * The listener machinery has been in this class since the port, and nothing
 * ever fired it or registered anything with it, so a parent only ever learned
 * that a child had finished when the querier came round again -- up to
 * waitSeconds, and on a nested workflow potentially a pass per level.
 *
 * The notification is a shortcut and not a source of truth: the parent still
 * recomputes from all of its children through isDone() whenever it reacts. A
 * notification that is missed or arrives out of order therefore costs latency
 * and nothing else, which is the property worth having in a component whose
 * job is to report truthfully whether work succeeded.
 *
 * @author mattmann
 * @author bfoster
 */
public class TestWorkflowProcessorNotification extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private WorkflowLifecycleManager lifecycleManager;

  public TestWorkflowProcessorNotification() {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.lifecycleManager = new WorkflowLifecycleManager(LIFECYCLE);
  }

  /**
   * The point of the change: no querier is running here, and the parent still
   * ends up in the right state the moment its children are done.
   */
  public void testParentReachesSuccessWhenChildrenFinish() throws Exception {
    WorkflowProcessor parent = processorInState("Executing", "running");
    TaskProcessor child = attachChild(parent);

    child.setState(state("Success", "done"));

    assertEquals("the parent should have reacted to its child",
        "Success", parent.getWorkflowInstance().getState().getName());
  }

  /**
   * And the failing case, which matters more, because getting this wrong is
   * how a failed workflow gets reported as a successful one.
   */
  public void testParentReachesFailureWhenAChildFails() throws Exception {
    WorkflowProcessor parent = processorInState("Executing", "running");
    TaskProcessor child = attachChild(parent);

    child.setState(state("Failure", "done"));

    assertEquals("Failure", parent.getWorkflowInstance().getState().getName());
  }

  /**
   * A parent with work still outstanding must not conclude anything. One child
   * done and one still running is not a finished workflow.
   */
  public void testParentStaysPutWhileWorkRemains() throws Exception {
    WorkflowProcessor parent = processorInState("Executing", "running");
    TaskProcessor first = attachChild(parent);
    attachChild(parent);

    first.setState(state("Success", "done"));

    assertEquals("a workflow with a child still running is not done",
        "Executing", parent.getWorkflowInstance().getState().getName());
  }

  /**
   * The notification is an optimisation over the recomputation, not a
   * replacement for it. Asking the parent directly, with no notification
   * involved, must reach the same answer -- that is what makes a lost
   * notification survivable.
   */
  public void testPollingReachesTheSameAnswerWithoutAnyNotification()
      throws Exception {
    WorkflowProcessor parent = processorInState("Executing", "running");
    TaskProcessor child = attachChild(parent);

    // Set the state behind the parent's back, the way a missed notification
    // would leave things.
    child.getWorkflowInstance().setState(state("Failure", "done"));
    assertEquals("no notification, so nothing has happened yet",
        "Executing", parent.getWorkflowInstance().getState().getName());

    parent.nextState();

    assertEquals("recomputing still gets the right answer",
        "Failure", parent.getWorkflowInstance().getState().getName());
  }

  /**
   * A change bubbles to the top rather than stopping at the first parent.
   */
  public void testChangeBubblesToTheGrandparent() throws Exception {
    WorkflowProcessor grandparent = processorInState("Executing", "running");
    WorkflowProcessor parent = processorInState("Executing", "running");
    grandparent.getSubProcessors().add(parent);
    parent.getListeners().add(grandparent);
    TaskProcessor child = attachChild(parent);

    child.setState(state("Success", "done"));

    assertEquals("Success", parent.getWorkflowInstance().getState().getName());
    assertEquals("Success",
        grandparent.getWorkflowInstance().getState().getName());
  }

  /**
   * A processor announcing its own change must not try to react to itself.
   */
  public void testAProcessorDoesNotReactToItsOwnChange() throws Exception {
    WorkflowProcessor parent = processorInState("Executing", "running");
    RecordingListener listener = new RecordingListener();
    parent.getListeners().add(listener);

    parent.setState(state("Queued", "waiting"));

    assertEquals("Queued", parent.getWorkflowInstance().getState().getName());
    assertEquals("the change should still be announced upward",
        1, listener.changes);
  }

  // ---- helpers -----------------------------------------------------------

  private WorkflowState state(String name, String category) {
    return lifecycleManager.getDefaultLifecycle()
        .createState(name, category, "under test");
  }

  private WorkflowProcessor processorInState(String name, String category) {
    WorkflowInstance instance = new WorkflowInstance();
    instance.setParentChildWorkflow(new ParentChildWorkflow(new Graph()));
    TaskProcessor processor = new TaskProcessor(lifecycleManager, instance);
    processor.setSubProcessors(new Vector<WorkflowProcessor>());
    processor.getWorkflowInstance().setState(state(name, category));
    return processor;
  }

  private TaskProcessor attachChild(WorkflowProcessor parent) {
    TaskProcessor child = (TaskProcessor) processorInState("Executing",
        "running");
    parent.getSubProcessors().add(child);
    child.getListeners().add(parent);
    return child;
  }

  private static class RecordingListener implements WorkflowProcessorListener {

    private int changes;

    @Override
    public void notifyChange(WorkflowProcessor processor,
        ChangeType changeType) {
      changes++;
    }
  }
}
