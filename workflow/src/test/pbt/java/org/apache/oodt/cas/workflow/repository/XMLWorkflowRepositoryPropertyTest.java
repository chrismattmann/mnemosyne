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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
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
 * Properties of {@link XMLWorkflowRepository}, which reads workflow
 * definitions out of a directory of policy files.
 *
 * <p>This is how a deployment declares what it can run: a directory holding
 * {@code tasks.xml}, {@code conditions.xml}, {@code events.xml} and one
 * {@code *.workflow.xml} per workflow, named in the manager's configuration.
 * The properties here are about the round trip from that directory back out
 * through the repository's lookups — what was written down is what is offered.
 *
 * <p>Each case writes a policy directory of its own under a temporary
 * directory and deletes it afterwards, and every identifier a case writes is
 * unique to that case, because this class holds its parsed policy in static
 * fields shared by every repository in the JVM.
 */
class XMLWorkflowRepositoryPropertyTest {

  /** Makes every identifier a case writes unique within the JVM. */
  private static final AtomicLong SEQUENCE = new AtomicLong();

  /** Characters an XML document, or a reader of one, treats specially. */
  private static final List<String> SIGNIFICANT =
      List.of("&", "<", ">", "\"", "'", "\\", "%", "é", "日");

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

  /** What one generated policy directory declares. */
  private record Policy(Path directory, List<String> workflowIds,
      List<String> taskIds, String eventName) {
  }

  /**
   * Writes a policy directory declaring {@code workflowCount} workflows, each
   * holding one task of its own, all reachable through a single event. Every
   * identifier carries a serial number, so nothing this directory declares
   * collides with anything another case declared.
   */
  private static Policy writePolicy(int workflowCount, String taskConfigName,
      String taskConfigValue) throws IOException {
    long serial = SEQUENCE.incrementAndGet();
    Path directory = Files.createTempDirectory("pbt-xmlpolicy");
    List<String> workflowIds = new ArrayList<>();
    List<String> taskIds = new ArrayList<>();

    StringBuilder tasks = new StringBuilder(
        "<cas:tasks xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\">\n");
    StringBuilder events = new StringBuilder(
        "<cas:workflowevents xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\">\n");
    String eventName = "event-" + serial;
    events.append("<event name=\"").append(eventName).append("\">\n");

    for (int i = 0; i < workflowCount; i++) {
      String workflowId = "urn:pbt:workflow-" + serial + "-" + i;
      String taskId = "urn:pbt:task-" + serial + "-" + i;
      workflowIds.add(workflowId);
      taskIds.add(taskId);

      tasks.append("<task id=\"").append(taskId).append("\" name=\"Task ")
          .append(i).append("\" class=\"")
          .append("org.apache.oodt.cas.workflow.examples.NoOpTask\">\n")
          .append("<conditions/>\n<configuration>\n")
          .append("<property name=\"").append(escaped(taskConfigName))
          .append("\" value=\"").append(escaped(taskConfigValue))
          .append("\"/>\n</configuration>\n</task>\n");

      String workflowFile = "<cas:workflow "
          + "xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\" name=\"Workflow "
          + i + "\" id=\"" + workflowId + "\">\n<tasks>\n<task id=\"" + taskId
          + "\"/>\n</tasks>\n</cas:workflow>\n";
      Files.write(directory.resolve("w" + serial + "-" + i + ".workflow.xml"),
          workflowFile.getBytes(StandardCharsets.UTF_8));

      events.append("<workflow id=\"").append(workflowId).append("\"/>\n");
    }

    tasks.append("</cas:tasks>\n");
    events.append("</event>\n</cas:workflowevents>\n");
    Files.write(directory.resolve("tasks.xml"),
        tasks.toString().getBytes(StandardCharsets.UTF_8));
    Files.write(directory.resolve("events.xml"),
        events.toString().getBytes(StandardCharsets.UTF_8));
    Files.write(directory.resolve("conditions.xml"),
        ("<cas:conditions xmlns:cas=\"http://oodt.jpl.nasa.gov/1.0/cas\"/>\n")
            .getBytes(StandardCharsets.UTF_8));

    return new Policy(directory, workflowIds, taskIds, eventName);
  }

  private static XMLWorkflowRepository repositoryOver(Policy policy) {
    return new XMLWorkflowRepository(
        Collections.singletonList(policy.directory().toUri().toString()));
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

  private static Set<String> workflowIdsOf(List workflows) {
    Set<String> ids = new HashSet<>();
    if (workflows != null) {
      for (Object each : workflows) {
        ids.add(((Workflow) each).getId());
      }
    }
    return ids;
  }

  /**
   * Every workflow the policy directory declares can be looked up by the
   * identifier it was declared under, is listed among the repository's
   * workflows, and carries the task it was declared with.
   *
   * <p>This is the whole purpose of the class. A workflow that is written down
   * and then not offered cannot be started, and the deployment has no other
   * way to say what it can run.
   */
  @HegelTest(testCases = 25)
  void everyDeclaredWorkflowIsOfferedWithTheTaskItDeclares(TestCase tc)
      throws Exception {
    int workflowCount = tc.draw(integers().min(1).max(4), "workflowCount");
    Policy policy = writePolicy(workflowCount, "aProperty", "aValue");
    try {
      XMLWorkflowRepository repository = repositoryOver(policy);
      Set<String> listed = workflowIdsOf(repository.getWorkflows());

      for (int i = 0; i < workflowCount; i++) {
        String workflowId = policy.workflowIds().get(i);
        Workflow found = repository.getWorkflowById(workflowId);
        assertNotNull(found,
            "the declared workflow " + workflowId + " is not offered");
        assertEquals("Workflow " + i, found.getName(),
            "the workflow was renamed on the way out of the policy file");
        assertTrue(listed.contains(workflowId),
            "the declared workflow " + workflowId
                + " is not among the repository's workflows");

        List tasks = repository.getTasksByWorkflowId(workflowId);
        assertNotNull(tasks, "the workflow came back with no task list");
        assertEquals(1, tasks.size(),
            "the workflow came back with " + tasks.size() + " tasks, not one");
        assertEquals(policy.taskIds().get(i),
            ((WorkflowTask) tasks.get(0)).getTaskId(),
            "the workflow came back holding another task");
      }
    } finally {
      deleteRecursively(policy.directory());
    }
  }

  /**
   * The event the policy declares reaches exactly the workflows declared under
   * it. An event name is how anything outside the manager starts a workflow,
   * so this is the only route from the outside world into everything above.
   */
  @HegelTest(testCases = 25)
  void anEventReachesTheWorkflowsDeclaredUnderIt(TestCase tc) throws Exception {
    int workflowCount = tc.draw(integers().min(1).max(4), "workflowCount");
    Policy policy = writePolicy(workflowCount, "aProperty", "aValue");
    try {
      XMLWorkflowRepository repository = repositoryOver(policy);

      List reached = repository.getWorkflowsForEvent(policy.eventName());
      assertNotNull(reached, "the declared event reaches nothing at all");
      assertEquals(new HashSet<>(policy.workflowIds()), workflowIdsOf(reached),
          "the event reaches workflows other than those declared under it");
      assertTrue(repository.getRegisteredEvents().contains(policy.eventName()),
          "the declared event is not among the registered events");
    } finally {
      deleteRecursively(policy.directory());
    }
  }

  /**
   * A task's configuration comes back out of the policy file as it was written
   * into it, including text XML gives a meaning of its own.
   *
   * <p>Configuration values are file paths, e-mail addresses, shell fragments
   * and metadata keys, written by whoever wrote the policy. A value that comes
   * back changed is a task configured with something nobody wrote.
   */
  @HegelTest(testCases = 25)
  void aTaskConfigurationSurvivesThePolicyFile(TestCase tc) throws Exception {
    String name = "prop" + drawSignificantText(tc, "name");
    String value = drawSignificantText(tc, "value");
    Policy policy = writePolicy(1, name, value);
    try {
      XMLWorkflowRepository repository = repositoryOver(policy);
      String taskId = policy.taskIds().get(0);

      WorkflowTask task = repository.getWorkflowTaskById(taskId);
      assertNotNull(task, "the declared task is not offered");
      assertNotNull(task.getTaskConfig(),
          "the declared task came back with no configuration");
      assertEquals(value, task.getTaskConfig().getProperty(name),
          "the configuration property changed on the way out of the file");
    } finally {
      deleteRecursively(policy.directory());
    }
  }

  /**
   * An identifier the policy never declared is simply absent from every
   * lookup, rather than an error.
   *
   * <p>The manager looks up whatever identifier a client sends it, and clients
   * send identifiers from stale monitors, old scripts and typing mistakes. A
   * lookup that fails outright takes the manager's request handling down with
   * it; one that answers with nothing is answered.
   */
  @HegelTest(testCases = 25)
  void aLookupOfAnUndeclaredIdentifierIsSimplyAbsent(TestCase tc)
      throws Exception {
    int workflowCount = tc.draw(integers().min(1).max(3), "workflowCount");
    Policy policy = writePolicy(workflowCount, "aProperty", "aValue");
    String stranger = "urn:pbt:never-declared-"
        + tc.draw(integers().min(0).max(99), "stranger");
    try {
      XMLWorkflowRepository repository = repositoryOver(policy);

      assertNull(repository.getWorkflowById(stranger),
          "an undeclared identifier found a workflow");
      assertNull(repository.getWorkflowTaskById(stranger),
          "an undeclared identifier found a task");
      assertNull(repository.getTaskById(stranger),
          "an undeclared identifier found a task");
      assertNull(repository.getConditionsByTaskId(stranger),
          "an undeclared identifier found conditions");
      assertNull(repository.getConfigurationByTaskId(stranger),
          "an undeclared identifier found a task configuration");
    } finally {
      deleteRecursively(policy.directory());
    }
  }

  /**
   * A repository offers the workflows in the directories it was given, and no
   * others.
   *
   * <p>The seed directories are a constructor argument, which is a promise
   * that two repositories built over two directories are two repositories.
   * Everything that reads policy at run time depends on it: the manager builds
   * one repository per configured policy path, and a test harness builds one
   * per fixture.
   */
  @HegelTest(testCases = 20)
  void aRepositoryOffersOnlyWhatItsOwnDirectoriesDeclare(TestCase tc)
      throws Exception {
    int firstCount = tc.draw(integers().min(1).max(2), "firstCount");
    int secondCount = tc.draw(integers().min(1).max(2), "secondCount");
    Policy first = writePolicy(firstCount, "aProperty", "aValue");
    Policy second = writePolicy(secondCount, "aProperty", "aValue");
    try {
      repositoryOver(first);
      XMLWorkflowRepository secondRepository = repositoryOver(second);

      for (String foreign : first.workflowIds()) {
        assertNull(secondRepository.getWorkflowById(foreign),
            "a repository over " + second.directory()
                + " offers workflow " + foreign
                + ", which only " + first.directory() + " declares");
      }
      assertEquals(new HashSet<>(second.workflowIds()),
          workflowIdsOf(secondRepository.getWorkflows()),
          "a repository lists workflows other than the ones its own "
              + "directory declares");
    } finally {
      deleteRecursively(first.directory());
      deleteRecursively(second.directory());
    }
  }
}
