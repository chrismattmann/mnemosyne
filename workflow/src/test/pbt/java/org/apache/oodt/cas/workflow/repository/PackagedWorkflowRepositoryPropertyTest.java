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

package org.apache.oodt.cas.workflow.repository;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

/**
 * Properties of {@link PackagedWorkflowRepository}, which reads the nesting
 * WEngine dialect of workflow policy.
 *
 * <p>Unlike {@link XMLWorkflowRepository} this one rewrites what it reads
 * before handing it out: a nested workflow becomes a generated redirector
 * task, a parallel workflow stops being a workflow and becomes an event over
 * its children, and every workflow becomes an event named after itself. Those
 * rewrites are the interesting part, so the properties here are mostly about
 * what survives them — a workflow that was declared is still startable, a task
 * that was declared still carries its configuration.
 *
 * <p>Each case writes a policy file of its own under a temporary directory and
 * deletes it afterwards. This class keeps its parsed policy per instance, so
 * unlike the older repository nothing leaks between cases.
 */
class PackagedWorkflowRepositoryPropertyTest {

  /** Makes every identifier a case writes unique, for readable failures. */
  private static final AtomicLong SEQUENCE = new AtomicLong();

  /** Characters an XML document, or a reader of one, treats specially. */
  private static final List<String> SIGNIFICANT =
      List.of("&", "<", ">", "\"", "'", "\\", "%", "é", "日");

  private static final String NO_OP =
      "org.apache.oodt.cas.workflow.examples.NoOpTask";

  private static String escaped(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;");
  }

  private static String drawSignificantText(TestCase tc, String label) {
    String around = tc.draw(
        text().minSize(0).maxSize(3).categories("Lu", "Ll", "Nd"),
        label + "Around");
    String significant = tc.draw(sampledFrom(SIGNIFICANT), label + "Char");
    return around + significant + around;
  }

  /** What one generated policy file declares. */
  private record Policy(Path directory, File file, String workflowId,
      List<String> taskIds) {
  }

  private static Path newDirectory() throws IOException {
    return Files.createTempDirectory("pbt-packaged");
  }

  /**
   * Writes a policy file declaring one sequential workflow of {@code taskCount}
   * tasks, each task defined once at the top level and referred to from inside
   * the workflow, which is how the dialect is meant to be written.
   *
   * @param configured
   *          whether each task carries a configuration block
   */
  private static Policy writeSequentialPolicy(int taskCount,
      boolean configured, String propertyName, String propertyValue)
      throws IOException {
    long serial = SEQUENCE.incrementAndGet();
    Path directory = newDirectory();
    String workflowId = "urn:pbt:seq-" + serial;
    List<String> taskIds = new ArrayList<>();

    StringBuilder xml = new StringBuilder(
        "<cas:workflows xmlns=\"http://oodt.jpl.nasa.gov/2.0/cas\" "
            + "xmlns:cas=\"http://oodt.jpl.nasa.gov/2.0/cas\" "
            + "xmlns:p=\"http://oodt.jpl.nasa.gov/2.0/cas/property\">\n");
    xml.append("<sequential id=\"").append(workflowId)
        .append("\" name=\"Sequential ").append(serial).append("\">\n");
    for (int i = 0; i < taskCount; i++) {
      taskIds.add("urn:pbt:task-" + serial + "-" + i);
      xml.append("<task id-ref=\"").append(taskIds.get(i)).append("\"/>\n");
    }
    xml.append("</sequential>\n");
    for (int i = 0; i < taskCount; i++) {
      xml.append("<task id=\"").append(taskIds.get(i)).append("\" name=\"Task ")
          .append(i).append("\" class=\"").append(NO_OP).append("\">\n");
      if (configured) {
        xml.append("<configuration>\n<property name=\"")
            .append(escaped(propertyName)).append("\" value=\"")
            .append(escaped(propertyValue)).append("\"/>\n</configuration>\n");
      }
      xml.append("</task>\n");
    }
    xml.append("</cas:workflows>\n");

    File file = directory.resolve("policy-" + serial + ".xml").toFile();
    Files.write(file.toPath(), xml.toString().getBytes(StandardCharsets.UTF_8));
    return new Policy(directory, file, workflowId, taskIds);
  }

  /**
   * Writes a policy file declaring one parallel workflow whose children are
   * {@code childCount} sequential workflows of one task each.
   */
  private static Policy writeParallelPolicy(int childCount,
      List<String> childIdsOut) throws IOException {
    long serial = SEQUENCE.incrementAndGet();
    Path directory = newDirectory();
    String workflowId = "urn:pbt:par-" + serial;
    List<String> taskIds = new ArrayList<>();

    StringBuilder xml = new StringBuilder(
        "<cas:workflows xmlns=\"http://oodt.jpl.nasa.gov/2.0/cas\" "
            + "xmlns:cas=\"http://oodt.jpl.nasa.gov/2.0/cas\" "
            + "xmlns:p=\"http://oodt.jpl.nasa.gov/2.0/cas/property\">\n");
    xml.append("<parallel id=\"").append(workflowId)
        .append("\" name=\"Parallel ").append(serial).append("\">\n");
    for (int i = 0; i < childCount; i++) {
      String childId = "urn:pbt:child-" + serial + "-" + i;
      String taskId = "urn:pbt:ptask-" + serial + "-" + i;
      childIdsOut.add(childId);
      taskIds.add(taskId);
      xml.append("<sequential id=\"").append(childId).append("\" name=\"Child ")
          .append(i).append("\">\n<task id-ref=\"").append(taskId)
          .append("\"/>\n</sequential>\n");
    }
    xml.append("</parallel>\n");
    for (String taskId : taskIds) {
      xml.append("<task id=\"").append(taskId).append("\" name=\"").append(
          taskId).append("\" class=\"").append(NO_OP)
          .append("\"><configuration/></task>\n");
    }
    xml.append("</cas:workflows>\n");

    File file = directory.resolve("policy-" + serial + ".xml").toFile();
    Files.write(file.toPath(), xml.toString().getBytes(StandardCharsets.UTF_8));
    return new Policy(directory, file, workflowId, taskIds);
  }

  private static PackagedWorkflowRepository repositoryOver(Policy policy)
      throws InstantiationException {
    return new PackagedWorkflowRepository(
        Collections.singletonList(policy.file()));
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
          throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException failure)
          throws IOException {
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static List<String> taskIdsOf(Workflow workflow) {
    List<String> ids = new ArrayList<>();
    if (workflow != null && workflow.getTasks() != null) {
      for (Object each : workflow.getTasks()) {
        ids.add(((WorkflowTask) each).getTaskId());
      }
    }
    return ids;
  }

  /**
   * A sequential workflow is offered under the identifier it was declared
   * with, holding the tasks it referred to, in the order it referred to them.
   *
   * <p>The order is the point of a sequential workflow: the engine runs the
   * task list in the order it finds it, and the file is the only statement of
   * what that order should be.
   */
  @HegelTest(testCases = 25)
  void aSequentialWorkflowKeepsItsTasksAndTheirOrder(TestCase tc)
      throws Exception {
    int taskCount = tc.draw(integers().min(1).max(4), "taskCount");
    Policy policy = writeSequentialPolicy(taskCount, true, "aProp", "aValue");
    try {
      PackagedWorkflowRepository repository = repositoryOver(policy);

      Workflow found = repository.getWorkflowById(policy.workflowId());
      assertNotNull(found, "the declared workflow " + policy.workflowId()
          + " is not offered");
      assertEquals(policy.taskIds(), taskIdsOf(found),
          "the workflow came back holding other tasks, or in another order");
      assertEquals(policy.taskIds(),
          taskIdsOf(repository.getWorkflowByName(found.getName())),
          "looking the workflow up by name found something else");
    } finally {
      deleteRecursively(policy.directory());
    }
  }

  /**
   * Every workflow the repository offers can be started by an event named
   * after it.
   *
   * <p>There is no separate event declaration in this dialect — a workflow's
   * identifier is its event — so a workflow that is listed but has no event is
   * a workflow that can be seen and not run. The repository generates
   * workflows of its own while rewriting the file, and those are listed
   * alongside the declared ones, so the statement is over everything listed
   * rather than over what the file happens to say.
   */
  @HegelTest(testCases = 25)
  void everyOfferedWorkflowIsStartableByAnEventOfItsOwnIdentifier(TestCase tc)
      throws Exception {
    int taskCount = tc.draw(integers().min(1).max(3), "taskCount");
    boolean parallel = tc.draw(booleans(), "parallel");
    List<String> children = new ArrayList<>();
    Policy policy = parallel ? writeParallelPolicy(taskCount, children)
        : writeSequentialPolicy(taskCount, true, "aProp", "aValue");
    try {
      PackagedWorkflowRepository repository = repositoryOver(policy);

      List offered = repository.getWorkflows();
      assertNotNull(offered, "the repository offers nothing at all");
      Set<String> events = new HashSet<>();
      for (Object each : repository.getRegisteredEvents()) {
        events.add((String) each);
      }

      for (Object each : offered) {
        Workflow workflow = (Workflow) each;
        assertTrue(events.contains(workflow.getId()),
            "workflow " + workflow.getId()
                + " is offered but is not a registered event");
        List started = repository.getWorkflowsForEvent(workflow.getId());
        assertNotNull(started, "the event named after workflow "
            + workflow.getId() + " starts nothing");
        boolean startsItself = false;
        for (Object started1 : started) {
          if (workflow.getId().equals(((Workflow) started1).getId())) {
            startsItself = true;
          }
        }
        assertTrue(startsItself, "the event named after workflow "
            + workflow.getId() + " does not start it");
      }
    } finally {
      deleteRecursively(policy.directory());
    }
  }

  /**
   * A parallel workflow's event starts its children, and the parallel workflow
   * itself is not offered as something to start.
   *
   * <p>This is the rewrite the class documents: a parallel block is not
   * something the engine can run, so it is replaced by an event over the
   * workflows inside it. Anything reading the repository — the monitor, the
   * command line — sees the children and not the block.
   */
  @HegelTest(testCases = 25)
  void aParallelWorkflowsEventStartsItsChildrenInsteadOfIt(TestCase tc)
      throws Exception {
    int childCount = tc.draw(integers().min(1).max(3), "childCount");
    List<String> children = new ArrayList<>();
    Policy policy = writeParallelPolicy(childCount, children);
    try {
      PackagedWorkflowRepository repository = repositoryOver(policy);

      assertNull(repository.getWorkflowById(policy.workflowId()),
          "the parallel block is still offered as a workflow");

      Set<String> started = new HashSet<>();
      for (Object each : repository.getWorkflowsForEvent(policy.workflowId())) {
        started.add(((Workflow) each).getId());
      }
      assertEquals(new HashSet<>(children), started,
          "the parallel block's event starts something other than its "
              + "children");
    } finally {
      deleteRecursively(policy.directory());
    }
  }

  /**
   * A task's configuration comes back out of the policy file as it was written
   * into it, including text XML gives a meaning of its own.
   */
  @HegelTest(testCases = 25)
  void aTaskConfigurationSurvivesThePolicyFile(TestCase tc) throws Exception {
    String name = "prop" + drawSignificantText(tc, "name");
    String value = drawSignificantText(tc, "value");
    Policy policy = writeSequentialPolicy(1, true, name, value);
    try {
      PackagedWorkflowRepository repository = repositoryOver(policy);
      String taskId = policy.taskIds().get(0);

      WorkflowTask task = repository.getWorkflowTaskById(taskId);
      assertNotNull(task, "the declared task is not offered");
      assertNotNull(task.getTaskConfig(),
          "the declared task came back with no configuration");
      assertEquals(value, task.getTaskConfig().getProperty(name),
          "the configuration property changed on the way out of the file");
      assertEquals(value,
          repository.getConfigurationByTaskId(taskId).getProperty(name),
          "the configuration looked up by task id differs from the task's own");
    } finally {
      deleteRecursively(policy.directory());
    }
  }

  /**
   * A task declared without a configuration block has an empty configuration,
   * and an identifier the policy never declared is simply absent.
   *
   * <p>Most tasks in the shipped examples carry no configuration at all, and
   * the manager looks up whatever identifier a client sends it. Neither is an
   * error; both are questions with the answer "nothing".
   */
  @HegelTest(testCases = 25)
  void aTaskWithoutConfigurationAndAnUndeclaredIdentifierBothAnswerNothing(
      TestCase tc) throws Exception {
    int taskCount = tc.draw(integers().min(1).max(3), "taskCount");
    Policy policy = writeSequentialPolicy(taskCount, false, "unused", "unused");
    String stranger = "urn:pbt:never-declared-"
        + tc.draw(integers().min(0).max(99), "stranger");
    try {
      PackagedWorkflowRepository repository = repositoryOver(policy);

      assertNull(repository.getWorkflowById(stranger),
          "an undeclared identifier found a workflow");
      assertNull(repository.getWorkflowTaskById(stranger),
          "an undeclared identifier found a task");
      assertTrue(repository.getConditionsByTaskId(stranger).isEmpty(),
          "an undeclared identifier found conditions");
      assertNotNull(repository.getConfigurationByTaskId(stranger),
          "an undeclared identifier failed the task configuration lookup "
              + "instead of answering with nothing");

      String declared = policy.taskIds().get(0);
      assertNotNull(repository.getConfigurationByTaskId(declared),
          "a task declared without a configuration block failed the "
              + "configuration lookup instead of answering with an empty one");
    } finally {
      deleteRecursively(policy.directory());
    }
  }
}
