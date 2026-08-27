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

package org.apache.oodt.cas.workflow.structs;

import static dev.hegel.Generators.doubles;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.oodt.cas.workflow.engine.processor.TaskProcessor;
import org.apache.oodt.cas.workflow.engine.processor.WorkflowProcessor;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;

/**
 * Properties of the three {@link PrioritySorter} implementations, which decide
 * the order in which the workflow engine hands runnable work to its runners.
 *
 * <p>A sorter is the only thing standing between a deployment and starvation:
 * whichever processor a sorter puts first is the one that gets a slot, so a
 * sorter that drops a candidate loses work outright, and one that orders
 * against its own stated policy quietly inverts the scheduling the operator
 * configured.
 *
 * <p>Each sorter is driven over a list of real {@link WorkflowProcessor}s —
 * {@link TaskProcessor}s over hand-built {@link WorkflowInstance}s — rather
 * than over stubs, because {@code sort} reaches through the processor to the
 * instance for both the priority and the creation date and a stub would be
 * asserting the test's own arithmetic.
 *
 * <p>The lifecycle is the shipped {@code wengine-lifecycle.xml}, read once:
 * constructing a processor needs one, and parsing it per case would dominate
 * a run that otherwise touches no I/O at all.
 */
class PrioritySorterPropertyTest {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  /** One second, in milliseconds; creation dates are spaced in these. */
  private static final long SECOND_MILLIS = 1000L;

  private static WorkflowLifecycleManager lifecycleManager;

  private static synchronized WorkflowLifecycleManager lifecycle()
      throws Exception {
    if (lifecycleManager == null) {
      lifecycleManager = new WorkflowLifecycleManager(LIFECYCLE);
    }
    return lifecycleManager;
  }

  /**
   * A processor over an instance with the given priority and creation date.
   *
   * <p>{@link TaskProcessor} is used because it is concrete and its
   * constructor does nothing beyond what {@link WorkflowProcessor}'s does;
   * none of the sorters look at anything but the instance.
   */
  private static WorkflowProcessor processorOf(double priority, Date created)
      throws Exception {
    WorkflowInstance instance = new WorkflowInstance();
    instance.setId("inst-" + priority + "-"
        + (created == null ? "none" : String.valueOf(created.getTime())));
    instance.setPriority(Priority.getPriority(priority));
    instance.setStartDate(created);
    return new TaskProcessor(lifecycle(), instance);
  }

  /**
   * Builds the candidate list a property will sort. Priorities and creation
   * offsets are drawn independently so that the two orderings disagree, which
   * is the only situation in which the three sorters differ at all.
   */
  private static List<WorkflowProcessor> candidates(TestCase tc, int size)
      throws Exception {
    List<Double> priorities =
        tc.draw(lists(doubles().min(0.0).max(10.0)).minSize(size).maxSize(size),
            "priorities");
    List<Integer> ages =
        tc.draw(lists(integers().min(0).max(600)).minSize(size).maxSize(size),
            "ageSeconds");
    long now = System.currentTimeMillis();
    List<WorkflowProcessor> processors = new ArrayList<WorkflowProcessor>();
    for (int i = 0; i < size; i++) {
      processors.add(processorOf(priorities.get(i),
          new Date(now - ages.get(i) * SECOND_MILLIS)));
    }
    return processors;
  }

  /** Counts each element by identity, so duplicates and losses both show. */
  private static Map<WorkflowProcessor, Integer> census(
      List<WorkflowProcessor> processors) {
    Map<WorkflowProcessor, Integer> counts =
        new IdentityHashMap<WorkflowProcessor, Integer>();
    for (WorkflowProcessor processor : processors) {
      Integer seen = counts.get(processor);
      counts.put(processor, seen == null ? 1 : seen + 1);
    }
    return counts;
  }

  private static List<PrioritySorter> sorters() {
    List<PrioritySorter> all = new ArrayList<PrioritySorter>();
    all.add(new HighestPrioritySorter());
    all.add(new FILOPrioritySorter());
    // A zero boost makes this one a pure priority sort, which is the only
    // shape in which its answer does not depend on the wall clock.
    all.add(new HighestFIFOPrioritySorter(60, 0.0, 10.0));
    return all;
  }

  /**
   * Sorting rearranges the candidates and does nothing else: every sorter must
   * leave the list holding exactly the processors it was given, each once.
   *
   * <p>The engine passes the sorted list straight to its runners, so a
   * candidate a sorter dropped is work that never runs and is never reported
   * as not having run; a candidate it duplicated is a task dispatched twice.
   * Identity is used rather than equality because two distinct instances may
   * legitimately carry the same priority and creation date.
   */
  @HegelTest(testCases = 30)
  void sortingIsAPermutationOfTheCandidates(TestCase tc) throws Exception {
    int size = tc.draw(integers().min(0).max(12), "size");
    List<WorkflowProcessor> original = candidates(tc, size);
    Map<WorkflowProcessor, Integer> before = census(original);

    for (PrioritySorter sorter : sorters()) {
      List<WorkflowProcessor> candidates =
          new ArrayList<WorkflowProcessor>(original);
      sorter.sort(candidates);
      tc.note(sorter.getClass().getSimpleName() + " sorted " + size);
      assertEquals(size, candidates.size(),
          sorter.getClass().getSimpleName() + " changed the number of "
              + "candidates the engine will consider");
      assertEquals(before, census(candidates),
          sorter.getClass().getSimpleName() + " did not return the same "
              + "candidates it was given");
    }
  }

  /**
   * Sorting twice must leave the list where the first sort left it.
   *
   * <p>The querier sorts on every pass over the same live candidates. A sorter
   * whose answer moves when nothing else has would reorder the queue under a
   * deployment for no reason, and any starvation that produced would be
   * invisible from the outside.
   */
  @HegelTest(testCases = 30)
  void sortingIsIdempotent(TestCase tc) throws Exception {
    int size = tc.draw(integers().min(0).max(10), "size");
    List<WorkflowProcessor> original = candidates(tc, size);

    for (PrioritySorter sorter : sorters()) {
      List<WorkflowProcessor> once = new ArrayList<WorkflowProcessor>(original);
      sorter.sort(once);
      List<WorkflowProcessor> twice = new ArrayList<WorkflowProcessor>(once);
      sorter.sort(twice);
      for (int i = 0; i < size; i++) {
        assertTrue(once.get(i) == twice.get(i),
            sorter.getClass().getSimpleName()
                + " moved candidate " + i + " on a second sort of an "
                + "already-sorted list");
      }
    }
  }

  /**
   * {@link HighestPrioritySorter} must leave the candidates in non-increasing
   * priority order.
   *
   * <p>That is the whole of what the class promises, and an operator raising
   * a workflow's priority is relying on exactly this.
   */
  @HegelTest(testCases = 30)
  void highestPrioritySorterOrdersByDescendingPriority(TestCase tc)
      throws Exception {
    int size = tc.draw(integers().min(0).max(12), "size");
    List<WorkflowProcessor> candidates = candidates(tc, size);

    new HighestPrioritySorter().sort(candidates);

    for (int i = 1; i < candidates.size(); i++) {
      double previous =
          candidates.get(i - 1).getWorkflowInstance().getPriority().getValue();
      double current =
          candidates.get(i).getWorkflowInstance().getPriority().getValue();
      assertTrue(previous >= current,
          "candidate " + i + " has priority " + current + " but follows one "
              + "with priority " + previous);
    }
  }

  /**
   * {@link HighestFIFOPrioritySorter} configured with no boost must behave as
   * a plain descending priority sort.
   *
   * <p>The boost is what makes this sorter interesting, but it is also what
   * makes it depend on the wall clock. With the boost switched off the class
   * still has to honour the priority it was handed, and a deployment that sets
   * {@code boostAmount} to zero to disable ageing is entitled to exactly that.
   */
  @HegelTest(testCases = 30)
  void highestFifoWithoutABoostIsAPriorityOrdering(TestCase tc)
      throws Exception {
    int size = tc.draw(integers().min(0).max(12), "size");
    List<WorkflowProcessor> candidates = candidates(tc, size);

    new HighestFIFOPrioritySorter(60, 0.0, 10.0).sort(candidates);

    for (int i = 1; i < candidates.size(); i++) {
      double previous =
          candidates.get(i - 1).getWorkflowInstance().getPriority().getValue();
      double current =
          candidates.get(i).getWorkflowInstance().getPriority().getValue();
      assertTrue(previous >= current,
          "with the boost disabled, candidate " + i + " at priority " + current
              + " follows one at priority " + previous);
    }
  }

  /**
   * With a boost configured and every candidate at the same base priority,
   * {@link HighestFIFOPrioritySorter} must put the candidate that has been
   * waiting longest first.
   *
   * <p>That is what the boost exists for: an instance that has sat in the
   * queue accrues priority so that a steady stream of equally-important work
   * cannot starve it. The candidates here are spaced minutes apart and share a
   * base priority, so the boost is the only thing separating them and the
   * expected order is unambiguous.
   */
  @HegelTest(testCases = 25)
  void highestFifoBoostFavoursTheLongestWaiting(TestCase tc) throws Exception {
    int size = tc.draw(integers().min(2).max(8), "size");
    List<Integer> ageMinutes =
        tc.draw(lists(integers().min(1).max(180)).minSize(size).maxSize(size),
            "ageMinutes");
    // Distinct ages, so the expected order has no ties to argue about.
    List<Integer> distinct = new ArrayList<Integer>();
    for (Integer age : ageMinutes) {
      if (!distinct.contains(age)) {
        distinct.add(age);
      }
    }
    tc.assume(distinct.size() >= 2);

    long now = System.currentTimeMillis();
    List<WorkflowProcessor> candidates = new ArrayList<WorkflowProcessor>();
    for (Integer age : distinct) {
      candidates.add(
          processorOf(1.0, new Date(now - age * 60L * SECOND_MILLIS)));
    }

    // 60s between boosts, 1.0 per boost, capped well above anything reachable
    // in three hours of waiting.
    new HighestFIFOPrioritySorter(60, 1.0, 1000.0).sort(candidates);

    for (int i = 1; i < candidates.size(); i++) {
      Date previous = candidates.get(i - 1).getWorkflowInstance().getStartDate();
      Date current = candidates.get(i).getWorkflowInstance().getStartDate();
      assertTrue(previous.getTime() <= current.getTime(),
          "candidate created at " + current + " was placed after one created "
              + "at " + previous + ", so the newer instance is being "
              + "preferred over the one that has waited longer");
    }
  }

  /**
   * {@link FILOPrioritySorter} must put the most recently created instance
   * first.
   *
   * <p>The class is named for first-in-last-out and its javadoc says so in
   * words: "The first ones to get processed are the most recently created
   * instances." That is the only reason to choose it over the FIFO sorter
   * beside it, so it is the contract a deployment configuring it is relying
   * on.
   */
  @HegelTest(testCases = 30)
  void filoSorterPutsTheMostRecentlyCreatedFirst(TestCase tc) throws Exception {
    int size = tc.draw(integers().min(2).max(10), "size");
    List<Integer> ages =
        tc.draw(lists(integers().min(0).max(600)).minSize(size).maxSize(size),
            "ageSeconds");
    List<Integer> distinct = new ArrayList<Integer>();
    for (Integer age : ages) {
      if (!distinct.contains(age)) {
        distinct.add(age);
      }
    }
    tc.assume(distinct.size() >= 2);

    long now = System.currentTimeMillis();
    List<WorkflowProcessor> candidates = new ArrayList<WorkflowProcessor>();
    for (Integer age : distinct) {
      candidates.add(processorOf(1.0, new Date(now - age * SECOND_MILLIS)));
    }

    new FILOPrioritySorter().sort(candidates);

    for (int i = 1; i < candidates.size(); i++) {
      Date previous = candidates.get(i - 1).getWorkflowInstance().getStartDate();
      Date current = candidates.get(i).getWorkflowInstance().getStartDate();
      assertTrue(previous.getTime() >= current.getTime(),
          "a first-in-last-out sorter placed the instance created at "
              + current + " after the one created at " + previous);
    }
  }

  /**
   * A candidate whose instance never recorded a creation date must not stop
   * the boost sorter from ordering the rest.
   *
   * <p>An instance loaded from a repository that lost its start date, or one
   * built by a caller that never set one, reaches the engine like any other.
   * {@link HighestFIFOPrioritySorter} catches the failure and treats such a
   * candidate as having waited no time at all, which is a defensible reading;
   * what matters to the engine is that the queue still comes back sorted and
   * whole.
   */
  @HegelTest(testCases = 25)
  void aCandidateWithNoCreationDateDoesNotBreakTheBoostSorter(TestCase tc)
      throws Exception {
    int dated = tc.draw(integers().min(0).max(6), "dated");
    int undated = tc.draw(integers().min(1).max(3), "undated");

    long now = System.currentTimeMillis();
    List<WorkflowProcessor> candidates = new ArrayList<WorkflowProcessor>();
    for (int i = 0; i < dated; i++) {
      candidates.add(processorOf(5.0, new Date(now - i * SECOND_MILLIS)));
    }
    for (int i = 0; i < undated; i++) {
      candidates.add(processorOf(5.0, null));
    }
    Map<WorkflowProcessor, Integer> before = census(candidates);

    new HighestFIFOPrioritySorter(60, 1.0, 100.0).sort(candidates);

    assertEquals(before, census(candidates),
        "a candidate with no creation date cost the queue a candidate");
  }
}
