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

import static dev.hegel.Generators.doubles;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * Properties of {@link WorkflowProcessor} and its concrete subclasses — the
 * decisions the engine makes about what may run next, what a parent should
 * conclude from its children, and which of its children a composite offers.
 *
 * <p>None of this needs a thread. The querier calls
 * {@code getRunnableWorkflowProcessors} and {@code nextState} on processors it
 * already holds; both are pure functions of the processor tree's states, and
 * that is what is stated here. The engine's own loops are deliberately not
 * started: they never return.
 *
 * <p>States are set on the instance rather than through
 * {@link WorkflowProcessor#setState} when a property is arranging a fixture,
 * because {@code setState} notifies listeners and would run the very
 * transition the property is about to check.
 *
 * <p>The lifecycle is the shipped {@code wengine-lifecycle.xml}, which declares
 * no state transitions of its own, so the processors here follow the built-in
 * chain — the behaviour every lifecycle file written before transitions could
 * be declared still gets.
 */
class WorkflowProcessorPropertyTest {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  /** States a task offers itself from, per {@link TaskProcessor}. */
  private static final List<String> OFFERING_STATES =
      List.of("Loaded", "Queued", "PreConditionSuccess");

  /**
   * States a task can be asked about without first giving it a current task.
   *
   * <p>Blocked is left out because {@link TaskProcessor} reads the blocked
   * task's configuration to decide how long to wait, which needs a task on the
   * instance. It has a property of its own below.
   */
  private static final List<String> UNBLOCKED_STATES =
      List.of("Null", "Loaded", "Queued", "PreConditionSuccess",
          "ExecutionComplete", "PreConditionEval", "Executing", "Paused",
          "Failure", "Success", "Stopped");

  /** The category each of those states belongs to in the shipped lifecycle. */
  private static final Map<String, String> CATEGORY_OF = categories();

  private static Map<String, String> categories() {
    Map<String, String> of = new LinkedHashMap<String, String>();
    of.put("Null", "initial");
    of.put("Loaded", "initial");
    of.put("Queued", "waiting");
    of.put("Blocked", "waiting");
    of.put("PreConditionSuccess", "transition");
    of.put("ExecutionComplete", "transition");
    of.put("PreConditionEval", "running");
    of.put("Executing", "running");
    of.put("Paused", "holding");
    of.put("Failure", "done");
    of.put("Success", "done");
    of.put("Stopped", "done");
    return of;
  }

  private static WorkflowLifecycleManager lifecycleManager;

  private static synchronized WorkflowLifecycleManager lifecycle()
      throws Exception {
    if (lifecycleManager == null) {
      lifecycleManager = new WorkflowLifecycleManager(LIFECYCLE);
    }
    return lifecycleManager;
  }

  private static WorkflowState state(String name) throws Exception {
    WorkflowState state = lifecycle().getDefaultLifecycle()
        .createState(name, CATEGORY_OF.get(name), "set by a property");
    assertNotNull(state.getCategory(),
        "the shipped lifecycle does not place " + name + " in "
            + CATEGORY_OF.get(name));
    return state;
  }

  /** A task processor over a fresh instance, put straight into a state. */
  private static TaskProcessor taskProcessor(String id, String stateName)
      throws Exception {
    WorkflowInstance instance = new WorkflowInstance();
    instance.setId(id);
    instance.setPriority(Priority.getDefault());
    TaskProcessor processor = new TaskProcessor(lifecycle(), instance);
    instance.setState(state(stateName));
    return processor;
  }

  private static WorkflowInstance instanceOf(String id, double priority) {
    WorkflowInstance instance = new WorkflowInstance();
    instance.setId(id);
    instance.setPriority(Priority.getPriority(priority));
    return instance;
  }

  /**
   * A task with nothing to wait on offers itself exactly when it is in one of
   * the states it is written to offer itself from.
   *
   * <p>The querier's whole supply of work is what this returns. A task that
   * never offers itself never runs; one that offers itself while already
   * executing is dispatched twice.
   */
  @HegelTest(testCases = 30)
  void aTaskOffersItselfExactlyFromTheStatesItIsWrittenFor(TestCase tc)
      throws Exception {
    String stateName = tc.draw(sampledFrom(UNBLOCKED_STATES), "state");

    TaskProcessor processor = taskProcessor("task", stateName);
    List<TaskProcessor> runnable = processor.getRunnableWorkflowProcessors();

    boolean expected = OFFERING_STATES.contains(stateName);
    tc.note(stateName + " -> " + runnable.size() + " runnable");
    assertEquals(expected, runnable.contains(processor),
        "a task in state " + stateName
            + (expected ? " did not offer itself" : " offered itself"));
    assertTrue(runnable.size() <= 1,
        "a task offered " + runnable.size() + " processors to run");
  }

  /**
   * A task waits until every one of its prerequisites has succeeded.
   *
   * <p>Prerequisites are how a condition gates the task it guards: the
   * condition runs as an instance of its own and the task holds a reference to
   * it. A task that offers itself while a prerequisite is still running is a
   * task whose gate did nothing.
   */
  @HegelTest(testCases = 30)
  void aTaskWaitsUntilEveryPrerequisiteHasSucceeded(TestCase tc)
      throws Exception {
    List<String> prerequisiteStates = tc.draw(
        lists(sampledFrom(List.of("Success", "Failure", "Queued", "Executing")))
            .minSize(1).maxSize(4),
        "prerequisiteStates");

    TaskProcessor processor = taskProcessor("gated", "Queued");
    List<WorkflowProcessor> prerequisites = new Vector<WorkflowProcessor>();
    boolean allSucceeded = true;
    for (int i = 0; i < prerequisiteStates.size(); i++) {
      prerequisites.add(taskProcessor("pre" + i, prerequisiteStates.get(i)));
      allSucceeded &= "Success".equals(prerequisiteStates.get(i));
    }
    processor.setPrerequisites(prerequisites);

    tc.note(prerequisiteStates + " -> allSucceeded=" + allSucceeded);
    assertEquals(allSucceeded, processor.passedPreConditions(),
        "a task's preconditions were judged " + !allSucceeded
            + " for prerequisites in states " + prerequisiteStates);
    assertEquals(allSucceeded,
        processor.getRunnableWorkflowProcessors().contains(processor),
        "a task with prerequisites in states " + prerequisiteStates
            + " offered itself when it should not have, or did not when it "
            + "should");
  }

  /**
   * Declaring no prerequisites at all leaves a task free to run.
   *
   * <p>Most tasks have no conditions. Treating an empty gate as a closed one
   * would stop every workflow in a deployment.
   */
  @HegelTest(testCases = 25)
  void aTaskWithNoPrerequisitesIsNotGated(TestCase tc) throws Exception {
    String stateName = tc.draw(sampledFrom(OFFERING_STATES), "state");
    TaskProcessor processor = taskProcessor("free", stateName);

    assertTrue(processor.passedPreConditions(),
        "a task with no prerequisites was treated as gated");
    processor.setPrerequisites(null);
    assertTrue(processor.passedPreConditions(),
        "clearing a task's prerequisites left it gated");
    assertTrue(processor.getRunnableWorkflowProcessors().contains(processor),
        "an ungated task in state " + stateName + " did not offer itself");
  }

  /**
   * A parent reports success only when every child finished, failure as soon
   * as one child failed unexcused, and neither while a child is still working.
   *
   * <p>This is the backward status calculation the whole engine's reporting
   * rests on. A parent that reads a failed child as a success reports a
   * workflow as having completed when its work did not.
   */
  @HegelTest(testCases = 30)
  void aParentConcludesFromItsChildrenWhatTheyActuallyDid(TestCase tc)
      throws Exception {
    List<String> childStates = tc.draw(
        lists(sampledFrom(List.of("Success", "Failure", "Queued", "Executing")))
            .minSize(1).maxSize(5),
        "childStates");

    ParallelProcessor parent =
        new ParallelProcessor(lifecycle(), instanceOf("parent", 5.0));
    parent.getWorkflowInstance().setState(state("Executing"));
    for (int i = 0; i < childStates.size(); i++) {
      parent.getSubProcessors()
          .add(taskProcessor("child" + i, childStates.get(i)));
    }

    boolean anyFailed = childStates.contains("Failure");
    boolean allDone = true;
    boolean anyDone = false;
    for (String childState : childStates) {
      boolean done = "done".equals(CATEGORY_OF.get(childState));
      allDone &= done;
      anyDone |= done;
    }

    String expected;
    if (anyDone && anyFailed) {
      expected = "ResultsFailure";
    } else if (anyDone && allDone) {
      expected = "ResultsSuccess";
    } else {
      expected = "ResultsBail";
    }

    tc.note(childStates + " -> expecting " + expected);
    assertEquals(expected, parent.isDone().getName(),
        "a parent whose children are in states " + childStates
            + " reached the wrong conclusion");
  }

  /**
   * A failure that was excused does not fail the parent.
   *
   * <p>A workflow may declare that a particular sub-processor is allowed to
   * fail. Ignoring that declaration turns an optional step into a mandatory
   * one; honouring it for a sub-processor that was not excused hides a real
   * failure.
   */
  @HegelTest(testCases = 25)
  void anExcusedFailureDoesNotFailTheParent(TestCase tc) throws Exception {
    int failures = tc.draw(integers().min(1).max(3), "failures");
    int excused = tc.draw(integers().min(0).max(3), "excused");

    ParallelProcessor parent =
        new ParallelProcessor(lifecycle(), instanceOf("parent", 5.0));
    parent.getWorkflowInstance().setState(state("Executing"));

    List<String> failedIds = new ArrayList<String>();
    for (int i = 0; i < failures; i++) {
      TaskProcessor child = taskProcessor("failed" + i, "Failure");
      parent.getSubProcessors().add(child);
      failedIds.add(child.getWorkflowInstance().getId());
    }

    List<String> excuse = new Vector<String>();
    for (int i = 0; i < Math.min(excused, failures); i++) {
      excuse.add(failedIds.get(i));
    }
    parent.setExcusedSubProcessorIds(excuse);

    boolean allExcused = excuse.size() == failures;
    tc.note(failures + " failures, " + excuse.size() + " excused");
    assertEquals(allExcused ? "ResultsSuccess" : "ResultsFailure",
        parent.isDone().getName(),
        "a parent with " + failures + " failed children of which "
            + excuse.size() + " were excused reached the wrong conclusion");
  }

  /**
   * A sequential composite offers one child at a time, and it is the first
   * that is neither finished nor already executing.
   *
   * <p>That is what makes the workflow sequential. Offering two would run
   * steps concurrently that the author wrote in order; offering one that is
   * already executing would run it twice.
   */
  @HegelTest(testCases = 30)
  void aSequentialCompositeOffersTheFirstUnfinishedChild(TestCase tc)
      throws Exception {
    List<String> childStates = tc.draw(
        lists(sampledFrom(List.of("Success", "Failure", "Queued", "Executing",
            "Loaded"))).minSize(1).maxSize(5),
        "childStates");

    SequentialProcessor parent =
        new SequentialProcessor(lifecycle(), instanceOf("parent", 5.0));
    parent.getWorkflowInstance().setState(state("Executing"));
    List<WorkflowProcessor> children = new ArrayList<WorkflowProcessor>();
    for (int i = 0; i < childStates.size(); i++) {
      TaskProcessor child = taskProcessor("child" + i, childStates.get(i));
      parent.getSubProcessors().add(child);
      children.add(child);
    }

    int expectedIndex = -1;
    for (int i = 0; i < childStates.size(); i++) {
      String childState = childStates.get(i);
      if (!"done".equals(CATEGORY_OF.get(childState))
          && !"Executing".equals(childState)) {
        expectedIndex = i;
        break;
      }
    }

    List<WorkflowProcessor> offered = parent.getRunnableSubProcessors();
    tc.note(childStates + " -> offering index " + expectedIndex);
    if (expectedIndex == -1) {
      assertTrue(offered.isEmpty(),
          "a sequential composite whose children are all finished or running "
              + "offered " + offered.size() + " of them");
    } else {
      assertEquals(1, offered.size(),
          "a sequential composite offered " + offered.size()
              + " children at once");
      assertTrue(offered.get(0) == children.get(expectedIndex),
          "a sequential composite offered a child other than the first one "
              + "that is neither finished nor running");
    }
  }

  /**
   * A parallel composite offers every child it holds.
   *
   * <p>Its counterpart to the sequential rule: the tasks are independent, so
   * all of them are candidates and the runners decide how many actually start.
   */
  @HegelTest(testCases = 25)
  void aParallelCompositeOffersEveryChild(TestCase tc) throws Exception {
    List<String> childStates = tc.draw(
        lists(sampledFrom(List.of("Success", "Queued", "Executing", "Loaded")))
            .minSize(0).maxSize(5),
        "childStates");

    ParallelProcessor parent =
        new ParallelProcessor(lifecycle(), instanceOf("parent", 5.0));
    parent.getWorkflowInstance().setState(state("Executing"));
    for (int i = 0; i < childStates.size(); i++) {
      parent.getSubProcessors()
          .add(taskProcessor("child" + i, childStates.get(i)));
    }

    assertEquals(parent.getSubProcessors(), parent.getRunnableSubProcessors(),
        "a parallel composite did not offer every child it holds");
  }

  /**
   * A processor with nothing to wait on walks the built-in chain from Null to
   * Loaded to Queued and then stays put until its children do something.
   *
   * <p>Every lifecycle file written before transitions could be declared
   * depends on this chain, so a deployment upgrading into a version that
   * changed it would find its instances stuck or skipping states.
   */
  @HegelTest(testCases = 25)
  void theBuiltInChainMovesAnInstanceFromNullToQueued(TestCase tc)
      throws Exception {
    int extraSteps = tc.draw(integers().min(0).max(3), "extraSteps");

    TaskProcessor processor = taskProcessor("chained", "Null");

    processor.nextState();
    assertEquals("Loaded", processor.getWorkflowInstance().getState().getName(),
        "an instance in Null did not move to Loaded");
    assertEquals("initial",
        processor.getWorkflowInstance().getState().getCategory().getName(),
        "Loaded was placed in a stage that does not hold it");

    processor.nextState();
    assertEquals("Queued", processor.getWorkflowInstance().getState().getName(),
        "an instance in Loaded did not move to Queued");
    assertEquals("waiting",
        processor.getWorkflowInstance().getState().getCategory().getName(),
        "Queued was placed in a stage that does not hold it, so the instance "
            + "would be reported as being further behind than it is");

    for (int i = 0; i < extraSteps; i++) {
      processor.nextState();
      assertEquals("Queued",
          processor.getWorkflowInstance().getState().getName(),
          "a queued instance with no children moved on anyway after "
              + (i + 1) + " further steps");
    }
  }

  /**
   * A processor whose instance has no state at all is put into a holding state
   * rather than left as it was.
   *
   * <p>An instance read back from a repository that lost its status arrives
   * like this. It cannot be categorised, so it cannot be scheduled; saying so
   * is better than leaving it invisible.
   */
  @HegelTest(testCases = 20)
  void aProcessorWithNoStateIsPutIntoHolding(TestCase tc) throws Exception {
    int steps = tc.draw(integers().min(1).max(3), "steps");

    TaskProcessor processor = taskProcessor("stateless", "Null");
    processor.getWorkflowInstance().setState(null);

    for (int i = 0; i < steps; i++) {
      processor.nextState();
    }

    WorkflowState state = processor.getWorkflowInstance().getState();
    assertNotNull(state, "a processor with no state was left with none");
    assertEquals("Unknown", state.getName(),
        "a processor with no state was not moved to Unknown");
    assertEquals("holding", state.getCategory().getName(),
        "Unknown was placed in a stage that does not hold it");
  }

  /**
   * The helper's selections must agree with each other and with the states the
   * processors are actually in.
   *
   * <p>{@code isDone} is written entirely in terms of these: which children
   * failed, whether any is done, whether all are. A selection that silently
   * returns nothing makes {@code isDone} conclude that no child failed.
   */
  @HegelTest(testCases = 30)
  void theHelpersSelectionsAgreeWithTheStatesTheyAreSelectingOn(TestCase tc)
      throws Exception {
    List<String> childStates = tc.draw(
        lists(sampledFrom(new ArrayList<String>(CATEGORY_OF.keySet())))
            .minSize(0).maxSize(6),
        "childStates");
    String stateName = tc.draw(
        sampledFrom(new ArrayList<String>(CATEGORY_OF.keySet())), "stateName");
    String categoryName = tc.draw(
        sampledFrom(List.of("initial", "waiting", "running", "done", "holding",
            "transition")), "categoryName");

    WorkflowProcessorHelper helper = new WorkflowProcessorHelper(lifecycle());
    List<WorkflowProcessor> processors = new ArrayList<WorkflowProcessor>();
    for (int i = 0; i < childStates.size(); i++) {
      processors.add(taskProcessor("p" + i, childStates.get(i)));
    }

    int expectedByState = 0;
    int expectedByCategory = 0;
    boolean allInCategory = true;
    for (String childState : childStates) {
      if (childState.equals(stateName)) {
        expectedByState++;
      }
      if (categoryName.equals(CATEGORY_OF.get(childState))) {
        expectedByCategory++;
      } else {
        allInCategory = false;
      }
    }

    assertEquals(expectedByState,
        helper.getWorkflowProcessorsByState(processors, stateName).size(),
        "the helper selected the wrong number of processors in state "
            + stateName + " out of " + childStates);
    assertEquals(expectedByCategory,
        helper.getWorkflowProcessorsByCategory(processors, categoryName).size(),
        "the helper selected the wrong number of processors in category "
            + categoryName + " out of " + childStates);
    assertEquals(expectedByCategory > 0,
        helper.containsCategory(processors, categoryName),
        "the helper disagrees with its own selection about whether any "
            + "processor is in category " + categoryName);
    assertEquals(allInCategory,
        helper.allProcessorsSameCategory(processors, categoryName),
        "the helper disagrees with its own selection about whether every "
            + "processor is in category " + categoryName);
  }

  /**
   * Flattening a tree of processors yields exactly its leaves, each once.
   *
   * <p>Only leaves ever run: the composites above them do no work. A leaf lost
   * on the way down is a task that never runs, and one produced twice is a
   * task dispatched twice.
   */
  @HegelTest(testCases = 25)
  void flatteningATreeYieldsExactlyItsLeaves(TestCase tc) throws Exception {
    int breadth = tc.draw(integers().min(1).max(3), "breadth");
    List<Integer> depths = tc.draw(
        lists(integers().min(0).max(2)).minSize(breadth).maxSize(breadth),
        "depths");

    WorkflowProcessorHelper helper = new WorkflowProcessorHelper(lifecycle());
    ParallelProcessor root =
        new ParallelProcessor(lifecycle(), instanceOf("root", 5.0));
    root.getWorkflowInstance().setState(state("Executing"));

    Map<WorkflowProcessor, Integer> expected =
        new IdentityHashMap<WorkflowProcessor, Integer>();
    for (int branch = 0; branch < breadth; branch++) {
      WorkflowProcessor node = root;
      for (int level = 0; level < depths.get(branch); level++) {
        ParallelProcessor child = new ParallelProcessor(lifecycle(),
            instanceOf("b" + branch + "l" + level, 5.0));
        child.getWorkflowInstance().setState(state("Executing"));
        node.getSubProcessors().add(child);
        node = child;
      }
      TaskProcessor leaf = taskProcessor("leaf" + branch, "Queued");
      node.getSubProcessors().add(leaf);
      expected.put(leaf, 1);
    }

    Map<WorkflowProcessor, Integer> actual =
        new IdentityHashMap<WorkflowProcessor, Integer>();
    for (WorkflowProcessor leaf : helper.toTasks(root)) {
      Integer seen = actual.get(leaf);
      actual.put(leaf, seen == null ? 1 : seen + 1);
    }

    tc.note("depths " + depths + " -> " + actual.size() + " leaves");
    assertEquals(expected, actual,
        "flattening the tree did not yield exactly its leaves");
  }

  /**
   * Comparing two processors orders them exactly as their priorities do.
   *
   * <p>{@link WorkflowProcessor} is {@code Comparable}, and anything that
   * sorts processors without going through a {@code PrioritySorter} uses this.
   * A comparison that disagreed with the priorities would order the queue
   * differently depending on which route the caller took.
   */
  @HegelTest(testCases = 30)
  void comparingProcessorsOrdersThemAsTheirPrioritiesDo(TestCase tc)
      throws Exception {
    double first = tc.draw(doubles().min(0.0).max(10.0), "firstPriority");
    double second = tc.draw(doubles().min(0.0).max(10.0), "secondPriority");

    TaskProcessor a =
        new TaskProcessor(lifecycle(), instanceOf("a", first));
    TaskProcessor b =
        new TaskProcessor(lifecycle(), instanceOf("b", second));

    int expected = Priority.getPriority(first)
        .compareTo(Priority.getPriority(second));
    assertEquals(Integer.signum(expected), Integer.signum(a.compareTo(b)),
        "comparing processors at priorities " + first + " and " + second
            + " disagreed with comparing the priorities themselves");
    assertEquals(Integer.signum(-a.compareTo(b)), Integer.signum(b.compareTo(a)),
        "the comparison is not antisymmetric");
    assertEquals(0, a.compareTo(a),
        "a processor did not compare equal to itself");
  }

  /**
   * Setting a processor's state tells everything listening to it.
   *
   * <p>A parent listens to its children so that a child finishing is acted on
   * at once rather than on the querier's next pass. A notification that never
   * arrives costs a pass per level of nesting.
   */
  @HegelTest(testCases = 25)
  void settingAStateTellsEverythingListening(TestCase tc) throws Exception {
    int listeners = tc.draw(integers().min(1).max(3), "listeners");
    String stateName = tc.draw(sampledFrom(OFFERING_STATES), "state");

    TaskProcessor processor = taskProcessor("watched", "Null");
    final List<WorkflowProcessor> heard = new ArrayList<WorkflowProcessor>();
    for (int i = 0; i < listeners; i++) {
      processor.getListeners().add(new WorkflowProcessorListener() {
        @Override
        public void notifyChange(WorkflowProcessor changed,
            org.apache.oodt.cas.workflow.engine.ChangeType changeType) {
          heard.add(changed);
        }
      });
    }

    processor.setState(state(stateName));

    assertEquals(stateName, processor.getWorkflowInstance().getState().getName(),
        "setting the state did not change it");
    assertEquals(listeners, heard.size(),
        "setting a state reached " + heard.size() + " of " + listeners
            + " listeners");
    for (WorkflowProcessor changed : heard) {
      assertTrue(changed == processor,
          "a listener was told about a processor other than the one that "
              + "changed");
    }
  }

  /**
   * A blocked task offers itself again only once the wait its configuration
   * asks for has elapsed.
   *
   * <p>A task bails and is marked Blocked when something it needs is not ready
   * — a file not yet delivered, a resource not yet free. {@code BlockTimeElapse}
   * is how long it must wait before being retried; offering it sooner busies
   * the runners with work that will bail again, and never offering it again
   * loses the workflow.
   */
  @HegelTest(testCases = 25)
  void aBlockedTaskOffersItselfOnlyAfterItsBlockTimeHasElapsed(TestCase tc)
      throws Exception {
    int blockMinutes = tc.draw(integers().min(1).max(5), "blockMinutes");
    int waitedMinutes = tc.draw(integers().min(0).max(10), "waitedMinutes");

    WorkflowTask task = new WorkflowTask();
    task.setTaskId("blocked-task");
    task.setTaskName("Blocked Task");
    task.setTaskInstanceClassName(
        "org.apache.oodt.cas.workflow.examples.NoOpTask");
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("BlockTimeElapse", String.valueOf(blockMinutes));
    task.setTaskConfig(config);

    Graph graph = new Graph();
    graph.setExecutionType("task");
    graph.setTask(task);
    ParentChildWorkflow workflow = new ParentChildWorkflow(graph);
    workflow.setId("task-workflow-blocked");
    workflow.getTasks().add(task);

    WorkflowInstance instance = instanceOf("blocked", 5.0);
    instance.setParentChildWorkflow(workflow);
    instance.setCurrentTaskId(task.getTaskId());
    TaskProcessor processor = new TaskProcessor(lifecycle(), instance);

    WorkflowState blocked = state("Blocked");
    blocked.setStartTime(new Date(System.currentTimeMillis()
        - waitedMinutes * 60L * 1000L));
    instance.setState(blocked);

    boolean expected = waitedMinutes >= blockMinutes;
    tc.note("waited " + waitedMinutes + " of " + blockMinutes + " minutes");
    assertEquals(expected,
        processor.getRunnableWorkflowProcessors().contains(processor),
        "a task blocked for " + waitedMinutes + " minutes with a "
            + blockMinutes + "-minute block time was "
            + (expected ? "not offered" : "offered") + " for retry");
  }
}
