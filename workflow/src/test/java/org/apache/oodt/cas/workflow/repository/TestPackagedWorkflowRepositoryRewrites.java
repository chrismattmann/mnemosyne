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

package org.apache.oodt.cas.workflow.repository;

//OODT imports
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

//JDK imports
import java.io.File;
import java.util.Collections;
import java.util.List;

//JUnit imports
import junit.framework.TestCase;

/**
 * The model rewriting {@link PackagedWorkflowRepository} performs on load.
 *
 * The engine this repository feeds runs a flat workflow of tasks and enforces
 * conditions only on tasks. The XML dialect says more than that, so the
 * repository reshapes what it read into something the engine can execute. The
 * workflows it hands out are deliberately not shaped like the file that was
 * parsed, which surprises people, so the four rewrites are pinned down here.
 *
 * These double as executable documentation for the Packaged Workflow Repository
 * wiki page; if one of them starts failing, that page is wrong.
 *
 * @author mattmann
 */
public class TestPackagedWorkflowRepositoryRewrites extends TestCase {

  private static final String HELLO_GOODBYE =
      "src/main/resources/examples/wengine/hello-goodbye.xml";

  private static final String GRANULE_MAPS =
      "src/main/resources/examples/wengine/GranuleMaps.xml";

  private PackagedWorkflowRepository helloGoodbye;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.helloGoodbye = repositoryFor(HELLO_GOODBYE);
  }

  // ---- rewrite 1: every workflow id is an event --------------------------

  /**
   * There is no event declaration in this dialect. A workflow written in the
   * file is started by sending an event named after it.
   */
  public void testWorkflowIdsFromTheFileAreEvents() throws Exception {
    List events = helloGoodbye.getRegisteredEvents();

    assertTrue(events.contains("urn:oodt:SayHelloAndGoodBye"));
    assertTrue("even a dissolved parallel workflow keeps its event",
        events.contains("urn:oodt:TestParallel"));
  }

  /**
   * A generated wrapper is a workflow like any other: listed, and startable by
   * an event of its own.
   *
   * It used not to be. Wrappers are built while computeEvents is already
   * iterating a snapshot taken before they existed, so nothing came back to
   * register an event for them, and getWorkflows and getRegisteredEvents
   * disagreed about which workflows existed.
   */
  public void testGeneratedWrappersAreWorkflowsAndEvents() throws Exception {
    String wrapperId = null;
    for (Object workflow : helloGoodbye.getWorkflows()) {
      if (((Workflow) workflow).getId().startsWith("parallel-")) {
        wrapperId = ((Workflow) workflow).getId();
      }
    }

    assertNotNull("the wrapper is in the workflow map", wrapperId);
    assertTrue("and has an event of its own",
        helloGoodbye.getRegisteredEvents().contains(wrapperId));
    assertEquals("which starts exactly it", 1,
        helloGoodbye.getWorkflowsForEvent(wrapperId).size());
  }

  /**
   * With that fixed, the two maps agree: everything listed as a workflow can
   * be started.
   */
  public void testEveryWorkflowIsStartable() throws Exception {
    List events = helloGoodbye.getRegisteredEvents();
    for (Object workflow : helloGoodbye.getWorkflows()) {
      String id = ((Workflow) workflow).getId();
      assertTrue("workflow " + id + " should be startable as an event",
          events.contains(id));
    }
  }

  /**
   * The headline surprise. A parallel workflow is removed from the workflow
   * map, so it cannot be looked up by the id written in the file.
   */
  public void testParallelWorkflowIsNotRetrievableById() throws Exception {
    assertNull("a parallel workflow is dissolved into its children",
        helloGoodbye.getWorkflowById("urn:oodt:TestParallel"));
  }

  /**
   * Its children are registered under its event instead, so firing the event
   * starts all of them at once. Here: the nested sequential workflow, and the
   * bare task, which gets a workflow of its own.
   */
  public void testParallelChildrenAreRegisteredUnderItsEvent()
      throws Exception {
    List children = helloGoodbye
        .getWorkflowsForEvent("urn:oodt:TestParallel");

    assertNotNull(children);
    assertEquals(2, children.size());

    boolean foundNestedWorkflow = false;
    for (Object child : children) {
      if ("urn:oodt:SayHelloAndGoodBye".equals(((Workflow) child).getId())) {
        foundNestedWorkflow = true;
      }
    }
    assertTrue("the nested sequential workflow should be started directly",
        foundNestedWorkflow);
  }

  /**
   * A bare task inside a parallel block cannot be started on its own, so the
   * repository generates a single-task workflow to carry it.
   */
  public void testBareTaskInParallelIsWrappedInAGeneratedWorkflow()
      throws Exception {
    Workflow generated = null;
    for (Object child : helloGoodbye
        .getWorkflowsForEvent("urn:oodt:TestParallel")) {
      if (((Workflow) child).getId().startsWith("parallel-")) {
        generated = (Workflow) child;
      }
    }

    assertNotNull("the bare task should have been given a workflow",
        generated);
    assertEquals(1, generated.getTasks().size());
    assertEquals("urn:oodt:IntensiveTask",
        ((WorkflowTask) generated.getTasks().get(0)).getTaskId());
  }

  // ---- rewrite 4: workflow conditions are hoisted ------------------------

  /**
   * The engine enforces conditions only on tasks, so conditions written on a
   * workflow are moved into a generated task placed first.
   */
  public void testWorkflowConditionsAreHoistedIntoALeadingTask()
      throws Exception {
    PackagedWorkflowRepository repo = repositoryFor(GRANULE_MAPS);
    Workflow granuleMaps = repo.getWorkflowById("urn:npp:GranuleMaps");

    assertNotNull(granuleMaps);
    assertEquals("the workflow keeps its conditions for reporting",
        3, granuleMaps.getConditions().size());

    WorkflowTask first = (WorkflowTask) granuleMaps.getTasks().get(0);
    assertEquals("the same conditions are carried by the leading task",
        3, first.getConditions().size());
  }

  // ---- more than one file -------------------------------------------------

  /**
   * The repository is pointed at a directory, so more than one file is the
   * normal case, and the second file must not damage what the first produced.
   *
   * computeEvents rebuilds a workflow's task list from its graph's children.
   * A generated wrapper has its task set directly and its graph has no
   * children at all, so a second pass over one clears the task and rebuilds
   * nothing, leaving an empty workflow and losing the task.
   */
  public void testGeneratedWrapperSurvivesASecondFile() throws Exception {
    PackagedWorkflowRepository repo = repositoryForBoth();

    Workflow wrapper = null;
    for (Object w : repo.getWorkflowsForEvent("urn:oodt:TestParallel")) {
      if (((Workflow) w).getId().startsWith("parallel-")) {
        wrapper = (Workflow) w;
      }
    }

    assertNotNull("the wrapper should still exist", wrapper);
    assertEquals("and must not have been emptied by the second file's pass",
        1, wrapper.getTasks().size());
  }

  /**
   * Nothing should be registered twice because there happened to be two files.
   */
  public void testWorkflowsAreNotDuplicatedAcrossFiles() throws Exception {
    PackagedWorkflowRepository repo = repositoryForBoth();

    List children = repo.getWorkflowsForEvent("urn:oodt:TestParallel");
    assertEquals("one nested workflow and one wrapped task, once each",
        2, children.size());
  }

  // ---- definitions are shared across the parse ---------------------------

  /**
   * A task declared once is reachable by id-ref, which is most of the point of
   * the dialect.
   */
  public void testDefinitionsAreRegisteredForReferenceById() throws Exception {
    assertNotNull(helloGoodbye.getWorkflowTaskById("urn:oodt:HelloWorld"));
    assertNotNull(
        helloGoodbye.getWorkflowConditionById("urn:oodt:TrueCondition"));
  }

  /**
   * Conditions carry the timeout and optional flags through the rewrite; the
   * engine needs both and neither survives by accident.
   */
  public void testConditionAttributesSurviveLoading() throws Exception {
    assertEquals(30L, helloGoodbye
        .getWorkflowConditionById("urn:oodt:TimeoutCondition")
        .getTimeoutSeconds());
    assertTrue(helloGoodbye
        .getWorkflowConditionById("urn:oodt:OptionalCondition").isOptional());
  }

  /**
   * Both example files at once, which is what pointing the repository at a
   * directory does.
   */
  private PackagedWorkflowRepository repositoryForBoth() throws Exception {
    return new PackagedWorkflowRepository(java.util.Arrays.asList(
        new File(HELLO_GOODBYE), new File(GRANULE_MAPS)));
  }

  private PackagedWorkflowRepository repositoryFor(String path)
      throws Exception {
    return new PackagedWorkflowRepository(
        Collections.singletonList(new File(path)));
  }
}
