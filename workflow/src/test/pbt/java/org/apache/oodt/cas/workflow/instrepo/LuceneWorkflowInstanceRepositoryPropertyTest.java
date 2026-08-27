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

package org.apache.oodt.cas.workflow.instrepo;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.commons.util.DateConvert;

/**
 * Properties of {@link LuceneWorkflowInstanceRepository}, the instance store
 * the workflow manager uses when it is configured against a Lucene index.
 *
 * <p>It answers the same questions as the database-backed repository — what is
 * running, under what status, how far through — so the properties here are
 * deliberately the same statements, and where the two answer differently that
 * difference is itself the finding.
 *
 * <p>Every case builds its index from nothing in a temporary directory of its
 * own and deletes it afterwards. That is not only isolation: this module is on
 * Lucene 10, which will not open an index written by Lucene 6, so an index
 * committed to the source tree would be unreadable rather than merely stale.
 */
class LuceneWorkflowInstanceRepositoryPropertyTest {

  /** A small alphabet of statuses, so that instances share them. */
  private static final List<String> STATUSES =
      List.of("Queued", "Executing", "Finished");

  /** The priorities a lifecycle can name, plus one off the scale. */
  private static final List<Priority> PRIORITIES = List.of(Priority.LOW,
      Priority.MEDIUM_LOW, Priority.MEDIUM, Priority.MEDIUM_HIGH,
      Priority.HIGH, Priority.getPriority(3.25));

  /**
   * Characters Lucene's query dialect, or a field name, might treat as
   * something other than text.
   */
  private static final List<String> SIGNIFICANT =
      List.of("*", "?", ":", "\\", "\"", "+", "-", "'", "é", "日");

  private static final int PAGE_SIZE = 3;

  /** The commands a caller has for changing what the repository holds. */
  private enum Command { ADD, UPDATE, REMOVE }

  private static WorkflowInstance instanceOf(String status, Priority priority,
      int timesBlocked, Metadata context) {
    Workflow workflow = new Workflow();
    workflow.setId("urn:oodt:aWorkflow");
    workflow.setName("A Workflow");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:oodt:aTask");
    task.setTaskName("A Task");
    task.setTaskInstanceClassName("org.apache.oodt.cas.workflow.examples.NoOpTask");
    List<WorkflowTask> tasks = new Vector<WorkflowTask>();
    tasks.add(task);
    workflow.setTasks(tasks);

    WorkflowInstance instance = new WorkflowInstance();
    instance.setWorkflow(workflow);
    instance.setCurrentTaskId("urn:oodt:aTask");
    instance.setStatus(status);
    instance.setPriority(priority);
    instance.setTimesBlocked(timesBlocked);
    instance.setSharedContext(context);
    return instance;
  }

  private static Metadata contextOf(Map<String, String> entries) {
    Metadata context = new Metadata();
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      context.addMetadata(entry.getKey(), entry.getValue());
    }
    return context;
  }

  private static WorkflowInstance drawInstance(TestCase tc, String label,
      Map<String, String> contextOut) {
    String status = tc.draw(sampledFrom(STATUSES), label + "Status");
    Priority priority = tc.draw(sampledFrom(PRIORITIES), label + "Priority");
    int timesBlocked = tc.draw(integers().min(0).max(3), label + "TimesBlocked");
    int contextSize = tc.draw(integers().min(0).max(2), label + "ContextSize");
    for (int i = 0; i < contextSize; i++) {
      contextOut.put("Key" + tc.draw(integers().min(0).max(3), label + "Key" + i),
          "Value" + tc.draw(integers().min(0).max(3), label + "Val" + i));
    }
    return instanceOf(status, priority, timesBlocked, contextOf(contextOut));
  }

  private static String drawSignificantText(TestCase tc, String label) {
    String around = tc.draw(
        text().minSize(0).maxSize(3).categories("Lu", "Ll", "Nd"),
        label + "Around");
    String significant = tc.draw(sampledFrom(SIGNIFICANT), label + "Char");
    return around + significant + around;
  }

  private static Set<String> idsOf(List instances) {
    Set<String> ids = new HashSet<>();
    if (instances != null) {
      for (Object each : instances) {
        ids.add(((WorkflowInstance) each).getId());
      }
    }
    return ids;
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

  /**
   * A repository pointed at a directory holding no index yet says it is
   * holding nothing.
   *
   * <p>This is the first thing the workflow manager does on a clean install:
   * it constructs the repository over a configured directory and the monitor
   * asks how many instances there are before anything has run. There is
   * nothing to report and nothing has gone wrong, so the answer is zero.
   */
  @HegelTest(testCases = 20)
  void aRepositoryOverAnEmptyDirectoryReportsNoInstances(TestCase tc)
      throws Exception {
    String status = tc.draw(sampledFrom(STATUSES), "status");
    Path index = Files.createTempDirectory("pbt-lucene-empty");
    try {
      LuceneWorkflowInstanceRepository repository =
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE);

      assertEquals(0, repository.getNumWorkflowInstances(),
          "a repository over an empty directory does not report zero");
      assertEquals(0, repository.getNumWorkflowInstancesByStatus(status),
          "a repository over an empty directory does not report zero for "
              + status);
    } finally {
      deleteRecursively(index);
    }
  }

  /**
   * A sequence of stores, updates and removals leaves the repository holding
   * exactly what the same sequence would leave in a map, and reporting the
   * same statuses.
   *
   * <p>The first command is always a store, because there is nothing to say
   * about an index that has never been written and
   * {@code aRepositoryOverAnEmptyDirectoryReportsNoInstances} says it
   * separately.
   */
  @HegelTest(testCases = 25)
  void aSequenceOfCommandsLeavesTheRepositoryHoldingWhatAMapWouldHold(
      TestCase tc) throws Exception {
    int commandCount = tc.draw(integers().min(1).max(6), "commandCount");
    Path index = Files.createTempDirectory("pbt-lucene-commands");
    try {
      LuceneWorkflowInstanceRepository repository =
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE);
      Map<String, String> model = new LinkedHashMap<>();

      for (int step = 0; step < commandCount; step++) {
        Command command = step == 0 ? Command.ADD
            : tc.draw(sampledFrom(List.of(Command.values())), "command" + step);
        List<String> known = new ArrayList<>(model.keySet());

        if (command == Command.ADD || known.isEmpty()) {
          Map<String, String> context = new LinkedHashMap<>();
          WorkflowInstance instance = drawInstance(tc, "add" + step, context);
          repository.addWorkflowInstance(instance);
          assertNotNull(instance.getId(),
              "a stored instance was given no identifier");
          model.put(instance.getId(), instance.getStatus());
        } else {
          String id = known.get(tc.draw(
              integers().min(0).max(known.size() - 1), "pick" + step));
          if (command == Command.REMOVE) {
            WorkflowInstance doomed = repository.getWorkflowInstanceById(id);
            assertNotNull(doomed,
                "an instance stored earlier cannot be read back at step " + step);
            repository.removeWorkflowInstance(doomed);
            model.remove(id);
          } else {
            Map<String, String> context = new LinkedHashMap<>();
            WorkflowInstance replacement =
                drawInstance(tc, "upd" + step, context);
            replacement.setId(id);
            repository.updateWorkflowInstance(replacement);
            model.put(id, replacement.getStatus());
          }
        }

        assertEquals(model.size(), repository.getNumWorkflowInstances(),
            "after step " + step + " the repository counts a different number "
                + "of instances than were stored");
        assertEquals(model.keySet(), idsOf(repository.getWorkflowInstances()),
            "after step " + step + " the repository lists different instances "
                + "than were stored");
        for (Map.Entry<String, String> entry : model.entrySet()) {
          WorkflowInstance stored =
              repository.getWorkflowInstanceById(entry.getKey());
          assertNotNull(stored, "instance " + entry.getKey()
              + " was stored but cannot be read back");
          assertEquals(entry.getValue(), stored.getStatus(),
              "instance " + entry.getKey() + " came back with another status");
        }
        for (String status : STATUSES) {
          int expected = 0;
          for (String each : model.values()) {
            if (each.equals(status)) {
              expected++;
            }
          }
          assertEquals(expected,
              repository.getNumWorkflowInstancesByStatus(status),
              "after step " + step + " the repository counts a different "
                  + "number of " + status + " instances");
        }
      }
    } finally {
      deleteRecursively(index);
    }
  }

  /**
   * What a stored instance says about itself is what it said when it was
   * stored: its status, its priority, how many times it was blocked, when it
   * started and ended, the workflow it belongs to and the shared context.
   */
  @HegelTest(testCases = 25)
  void aStoredInstanceReadsBackAsItWasStored(TestCase tc) throws Exception {
    Map<String, String> context = new LinkedHashMap<>();
    WorkflowInstance instance = drawInstance(tc, "inst", context);
    long startMillis =
        tc.draw(integers().min(0).max(1_000_000), "startSeconds") * 1000L;
    instance.setStartDate(new Date(startMillis));
    instance.setEndDate(new Date(startMillis + 60_000L));
    String startIso = instance.getStartDateTimeIsoStr();
    String endIso = instance.getEndDateTimeIsoStr();

    Path index = Files.createTempDirectory("pbt-lucene-roundtrip");
    try {
      LuceneWorkflowInstanceRepository repository =
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE);
      repository.addWorkflowInstance(instance);

      WorkflowInstance stored =
          repository.getWorkflowInstanceById(instance.getId());

      assertNotNull(stored, "the instance just stored cannot be read back");
      assertEquals(instance.getStatus(), stored.getStatus(),
          "the status changed in storage");
      assertEquals(instance.getPriority().getValue(),
          stored.getPriority().getValue(), "the priority changed in storage");
      assertEquals(instance.getTimesBlocked(), stored.getTimesBlocked(),
          "the blocked count changed in storage");
      assertEquals(startIso, stored.getStartDateTimeIsoStr(),
          "the start date changed in storage");
      assertEquals(endIso, stored.getEndDateTimeIsoStr(),
          "the end date changed in storage");
      assertEquals("urn:oodt:aWorkflow", stored.getWorkflow().getId(),
          "the instance came back attached to another workflow");
      assertEquals("A Workflow", stored.getWorkflow().getName(),
          "the workflow was renamed in storage");
      for (Map.Entry<String, String> entry : context.entrySet()) {
        assertEquals(entry.getValue(),
            stored.getSharedContext().getMetadata(entry.getKey()),
            "shared context entry " + entry.getKey() + " changed in storage");
      }
    } finally {
      deleteRecursively(index);
    }
  }

  /**
   * When the current task started and ended survives storage too.
   *
   * <p>These are stated apart from the rest of the round trip because they are
   * held differently: an instance keeps them on the task it names as current,
   * not on itself, while the index writes them as fields of the instance
   * document. The monitor shows them beside the instance's own dates, so a
   * caller has no reason to expect one pair to survive and the other not to.
   */
  @HegelTest(testCases = 25)
  void whenTheCurrentTaskRanSurvivesStorage(TestCase tc) throws Exception {
    Map<String, String> context = new LinkedHashMap<>();
    WorkflowInstance instance = drawInstance(tc, "inst", context);
    long startMillis =
        tc.draw(integers().min(0).max(1_000_000), "startSeconds") * 1000L;
    instance.setCurrentTaskStartDateTimeIsoStr(
        DateConvert.isoFormat(new Date(startMillis)));
    instance.setCurrentTaskEndDateTimeIsoStr(
        DateConvert.isoFormat(new Date(startMillis + 1000L)));
    String taskStart = instance.getCurrentTaskStartDateTimeIsoStr();
    String taskEnd = instance.getCurrentTaskEndDateTimeIsoStr();
    assertNotNull(taskStart, "the fixture failed to set a current task start");

    Path index = Files.createTempDirectory("pbt-lucene-taskdates");
    try {
      LuceneWorkflowInstanceRepository repository =
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE);
      repository.addWorkflowInstance(instance);

      WorkflowInstance stored =
          repository.getWorkflowInstanceById(instance.getId());

      assertNotNull(stored, "the instance just stored cannot be read back");
      assertEquals(taskStart, stored.getCurrentTaskStartDateTimeIsoStr(),
          "the current task's start date was lost in storage");
      assertEquals(taskEnd, stored.getCurrentTaskEndDateTimeIsoStr(),
          "the current task's end date was lost in storage");
    } finally {
      deleteRecursively(index);
    }
  }

  /**
   * Text a search index might read as syntax is still text when it comes back,
   * both as a status the repository is asked to search by and as a shared
   * context entry it only stores.
   */
  @HegelTest(testCases = 25)
  void textWithLuceneSignificantCharactersSurvivesARoundTrip(TestCase tc)
      throws Exception {
    String status = drawSignificantText(tc, "status");
    String key = "Key" + drawSignificantText(tc, "key");
    String value = drawSignificantText(tc, "value");
    Metadata context = new Metadata();
    context.addMetadata(key, value);
    WorkflowInstance instance =
        instanceOf(status, Priority.getDefault(), 0, context);

    Path index = Files.createTempDirectory("pbt-lucene-text");
    try {
      LuceneWorkflowInstanceRepository repository =
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE);
      repository.addWorkflowInstance(instance);

      WorkflowInstance stored =
          repository.getWorkflowInstanceById(instance.getId());
      assertNotNull(stored,
          "an instance holding index-significant text could not be read back");
      assertEquals(status, stored.getStatus(), "the status changed in storage");
      assertEquals(value, stored.getSharedContext().getMetadata(key),
          "the shared context entry changed in storage");
      assertEquals(1, repository.getNumWorkflowInstancesByStatus(status),
          "the instance was not found under the status it was stored with");
    } finally {
      deleteRecursively(index);
    }
  }

  /**
   * The pages of a repository are a partition of what it holds: every stored
   * instance appears on exactly one page, no page holds anything that was not
   * stored, and no page holds more than a page-size.
   */
  @HegelTest(testCases = 25)
  void thePagesPartitionTheStoredInstances(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(1).max(8), "count");
    Path index = Files.createTempDirectory("pbt-lucene-paging");
    try {
      LuceneWorkflowInstanceRepository repository =
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE);
      Set<String> stored = new HashSet<>();
      for (int i = 0; i < count; i++) {
        Map<String, String> context = new LinkedHashMap<>();
        WorkflowInstance instance = drawInstance(tc, "inst" + i, context);
        repository.addWorkflowInstance(instance);
        stored.add(instance.getId());
      }

      int totalPages = (count - 1) / PAGE_SIZE + 1;
      List<String> seen = new ArrayList<>();
      for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
        WorkflowInstancePage page = repository.getPagedWorkflows(pageNum);
        assertEquals(pageNum, page.getPageNum(),
            "page " + pageNum + " reports itself as page " + page.getPageNum());
        assertEquals(totalPages, page.getTotalPages(),
            "page " + pageNum + " disagrees about how many pages there are");
        assertTrue(page.getPageWorkflows().size() <= PAGE_SIZE,
            "page " + pageNum + " holds more instances than a page holds");
        for (Object each : page.getPageWorkflows()) {
          seen.add(((WorkflowInstance) each).getId());
        }
      }

      assertEquals(stored, new HashSet<>(seen),
          "the pages between them hold " + seen.size() + " of the " + count
              + " instances stored");
      assertEquals(seen.size(), new HashSet<>(seen).size(),
          "an instance appears on more than one page: " + seen);
      assertEquals(totalPages, repository.getLastPage().getPageNum(),
          "the last page is not the last page there is");
    } finally {
      deleteRecursively(index);
    }
  }

  /**
   * A directory the repository has written to is one it can be reopened over.
   *
   * <p>This is the whole reason to keep instances in an index rather than in
   * memory: the manager is restarted and the record of what it was running is
   * still there. A second repository over the same directory stands in for the
   * restart.
   */
  @HegelTest(testCases = 20)
  void anIndexWrittenByOneRepositoryIsReadableByTheNext(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(1).max(4), "count");
    Path index = Files.createTempDirectory("pbt-lucene-reopen");
    try {
      LuceneWorkflowInstanceRepository writer =
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE);
      Map<String, String> expected = new LinkedHashMap<>();
      for (int i = 0; i < count; i++) {
        Map<String, String> context = new LinkedHashMap<>();
        WorkflowInstance instance = drawInstance(tc, "inst" + i, context);
        writer.addWorkflowInstance(instance);
        expected.put(instance.getId(), instance.getStatus());
      }

      LuceneWorkflowInstanceRepository reader =
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE);

      assertEquals(count, reader.getNumWorkflowInstances(),
          "a repository reopened over the index counts something else");
      assertEquals(expected.keySet(), idsOf(reader.getWorkflowInstances()),
          "a repository reopened over the index lists something else");
      for (Map.Entry<String, String> entry : expected.entrySet()) {
        WorkflowInstance stored =
            reader.getWorkflowInstanceById(entry.getKey());
        assertNotNull(stored, "instance " + entry.getKey()
            + " is missing after reopening the index");
        assertEquals(entry.getValue(), stored.getStatus(),
            "instance " + entry.getKey()
                + " changed status when the index was reopened");
      }
      assertTrue(new File(index.toString()).isDirectory(),
          "the index directory went away");
    } finally {
      deleteRecursively(index);
    }
  }
}
