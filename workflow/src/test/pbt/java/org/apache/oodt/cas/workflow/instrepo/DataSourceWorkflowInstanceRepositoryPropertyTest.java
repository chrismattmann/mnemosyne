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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.HsqlWorkflowDatabase;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

/**
 * Properties of {@link DataSourceWorkflowInstanceRepository}, the instance
 * store the workflow manager uses when it is configured against a database.
 *
 * <p>This is the record of every workflow the manager has ever run: the engine
 * writes to it on each state change, the monitor and the command line read
 * back over it, and unlike the in-memory repository it is expected to survive
 * a restart. Nothing here was covered before, so these properties start from
 * the plainest statements — an instance that was stored can be read back, the
 * counts agree with what was added — and go on to paging and to text a
 * database is fussy about.
 *
 * <p>The database is HSQLDB loaded from {@code src/test/resources/workflow.sql},
 * the same schema {@code TestWorkflowDataSourceRepository} uses, one throwaway
 * database per test case so that cases cannot see each other's rows.
 */
class DataSourceWorkflowInstanceRepositoryPropertyTest {

  /** A small alphabet of statuses, so that instances share them. */
  private static final List<String> STATUSES =
      List.of("Queued", "Executing", "Finished");

  /** The priorities a lifecycle can name, plus one off the scale. */
  private static final List<Priority> PRIORITIES = List.of(Priority.LOW,
      Priority.MEDIUM_LOW, Priority.MEDIUM, Priority.MEDIUM_HIGH,
      Priority.HIGH, Priority.getPriority(3.25));

  /**
   * Characters a database, or the string-concatenated SQL this repository
   * builds, might treat as something other than text.
   */
  private static final List<String> SIGNIFICANT =
      List.of("'", "\"", "%", "_", "\\", "--", ";", "+", "é", "日");

  /** Instance repositories are constructed with a page size; this is ours. */
  private static final int PAGE_SIZE = 3;

  /**
   * A single command in a generated sequence. The repository has no history to
   * speak of beyond these four, and applying a sequence of them to the
   * repository and to a map at the same time is what states that the
   * repository behaves like a map.
   */
  private enum Command { ADD, UPDATE, REMOVE, CLEAR }

  /** What the model remembers about one instance. */
  private record Recorded(String status, double priority, int timesBlocked,
      Map<String, String> context) {
  }

  private static DataSourceWorkflowInstanceRepository repositoryOver(
      HsqlWorkflowDatabase database) {
    return new DataSourceWorkflowInstanceRepository(database.dataSource(),
        false, PAGE_SIZE);
  }

  /**
   * An instance of the workflow the seed data declares. The workflow and the
   * current task have to carry numeric identifiers, because the repository
   * writes them into SQL unquoted and the columns holding them are integers.
   */
  private static WorkflowInstance instanceOf(String status, Priority priority,
      int timesBlocked, Metadata context) {
    Workflow workflow = new Workflow();
    workflow.setId("1");
    workflow.setName("Test Workflow");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("1");
    task.setTaskName("Test Task");
    List<WorkflowTask> tasks = new Vector<WorkflowTask>();
    tasks.add(task);
    workflow.setTasks(tasks);

    WorkflowInstance instance = new WorkflowInstance();
    instance.setWorkflow(workflow);
    instance.setCurrentTaskId("1");
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

  /** Draws an instance and the model's record of it. */
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

  /** A string that certainly holds a character SQL might read as syntax. */
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

  /**
   * The INSERT the repository writes is one the shipped schema will accept.
   *
   * <p>{@code addWorkflowInstance} names every column of {@code
   * workflow_instances} except {@code workflow_instance_id}, then reads {@code
   * MAX(workflow_instance_id)} back to learn which row it just wrote. That
   * only works if the database fills the identifier in. Oracle deployments get
   * that from {@code workflow_oracle_create_sequences.sql}, which ships a
   * trigger for the column; the generic {@code workflow.sql} that every other
   * database is pointed at declares it as a plain {@code int NOT NULL PRIMARY
   * KEY} with no default, and this test resource is a copy of that. Nothing
   * else in this repository can be reached until an instance can be stored, so
   * this is stated first and against the schema as shipped.
   */
  @HegelTest(testCases = 20)
  void theShippedSchemaAcceptsAnInstance(TestCase tc) throws Exception {
    Map<String, String> context = new LinkedHashMap<>();
    WorkflowInstance instance = drawInstance(tc, "inst", context);
    HsqlWorkflowDatabase database = HsqlWorkflowDatabase.seeded();
    try {
      DataSourceWorkflowInstanceRepository repository =
          repositoryOver(database);

      repository.addWorkflowInstance(instance);

      assertEquals(1, repository.getNumWorkflowInstances(),
          "the instance was not stored");
    } finally {
      database.close();
    }
  }

  /**
   * A sequence of stores, updates, removals and clears leaves the repository
   * holding exactly what the same sequence would leave in a map.
   *
   * <p>This is the whole of what a caller asks the repository for: the engine
   * adds an instance, updates it on every state change, and the cleaner
   * removes it when it finishes. Stating them one at a time misses what
   * happens when they are interleaved — an update after a removal, a store
   * after a clear — which is exactly the traffic the manager generates.
   */
  @HegelTest(testCases = 30)
  void aSequenceOfCommandsLeavesTheRepositoryHoldingWhatAMapWouldHold(
      TestCase tc) throws Exception {
    int commandCount = tc.draw(integers().min(1).max(8), "commandCount");
    HsqlWorkflowDatabase database =
        HsqlWorkflowDatabase.withGeneratedInstanceIds();
    try {
      DataSourceWorkflowInstanceRepository repository =
          repositoryOver(database);
      Map<String, Recorded> model = new LinkedHashMap<>();

      for (int step = 0; step < commandCount; step++) {
        Command command = tc.draw(sampledFrom(List.of(Command.values())),
            "command" + step);
        List<String> known = new ArrayList<>(model.keySet());

        if (command == Command.CLEAR) {
          repository.clearWorkflowInstances();
          model.clear();
        } else if (command == Command.ADD || known.isEmpty()) {
          Map<String, String> context = new LinkedHashMap<>();
          WorkflowInstance instance = drawInstance(tc, "add" + step, context);
          repository.addWorkflowInstance(instance);
          assertNotNull(instance.getId(),
              "a stored instance was given no identifier");
          model.put(instance.getId(), new Recorded(instance.getStatus(),
              instance.getPriority().getValue(), instance.getTimesBlocked(),
              context));
        } else {
          String id = known.get(tc.draw(
              integers().min(0).max(known.size() - 1), "pick" + step));
          if (command == Command.REMOVE) {
            WorkflowInstance doomed = repository.getWorkflowInstanceById(id);
            repository.removeWorkflowInstance(doomed);
            model.remove(id);
          } else {
            Map<String, String> context = new LinkedHashMap<>();
            WorkflowInstance replacement =
                drawInstance(tc, "upd" + step, context);
            replacement.setId(id);
            repository.updateWorkflowInstance(replacement);
            model.put(id, new Recorded(replacement.getStatus(),
                replacement.getPriority().getValue(),
                replacement.getTimesBlocked(), context));
          }
        }

        assertEquals(model.size(), repository.getNumWorkflowInstances(),
            "after step " + step + " the repository counts a different number "
                + "of instances than were stored");
        assertEquals(model.keySet(), idsOf(repository.getWorkflowInstances()),
            "after step " + step + " the repository lists different instances "
                + "than were stored");
        for (Map.Entry<String, Recorded> entry : model.entrySet()) {
          WorkflowInstance stored =
              repository.getWorkflowInstanceById(entry.getKey());
          assertNotNull(stored, "instance " + entry.getKey()
              + " was stored but cannot be read back");
          assertEquals(entry.getValue().status(), stored.getStatus(),
              "instance " + entry.getKey() + " came back with another status");
        }
      }
    } finally {
      database.close();
    }
  }

  /**
   * What a stored instance says about itself is what it said when it was
   * stored: its status, its priority, how many times it was blocked, when it
   * started and ended, and the shared context the tasks pass between
   * themselves.
   *
   * <p>An instance is written once and read many times, by a monitor deciding
   * what to display and by a sorter deciding what to run next. Priority in
   * particular is already known to be lost when an instance travels over the
   * manager's RPC interface, so whether the database keeps it is worth
   * knowing separately.
   */
  @HegelTest(testCases = 30)
  void aStoredInstanceReadsBackAsItWasStored(TestCase tc) throws Exception {
    Map<String, String> context = new LinkedHashMap<>();
    WorkflowInstance instance = drawInstance(tc, "inst", context);
    long startMillis = tc.draw(
        integers().min(0).max(1_000_000), "startSeconds") * 1000L;
    instance.setStartDate(new Date(startMillis));
    instance.setEndDate(new Date(startMillis + 60_000L));
    String startIso = instance.getStartDateTimeIsoStr();
    String endIso = instance.getEndDateTimeIsoStr();

    HsqlWorkflowDatabase database =
        HsqlWorkflowDatabase.withGeneratedInstanceIds();
    try {
      DataSourceWorkflowInstanceRepository repository =
          repositoryOver(database);
      repository.addWorkflowInstance(instance);

      WorkflowInstance stored =
          repository.getWorkflowInstanceById(instance.getId());

      assertNotNull(stored, "the instance just stored cannot be read back");
      assertEquals(instance.getStatus(), stored.getStatus(),
          "the status changed in storage");
      assertEquals(instance.getPriority().getValue(),
          stored.getPriority().getValue(),
          "the priority changed in storage");
      assertEquals(instance.getTimesBlocked(), stored.getTimesBlocked(),
          "the blocked count changed in storage");
      assertEquals(startIso, stored.getStartDateTimeIsoStr(),
          "the start date changed in storage");
      assertEquals(endIso, stored.getEndDateTimeIsoStr(),
          "the end date changed in storage");
      assertEquals("1", stored.getWorkflow().getId(),
          "the instance came back attached to another workflow");
      for (Map.Entry<String, String> entry : context.entrySet()) {
        assertEquals(entry.getValue(),
            stored.getSharedContext().getMetadata(entry.getKey()),
            "shared context entry " + entry.getKey() + " changed in storage");
      }
    } finally {
      database.close();
    }
  }

  /**
   * Text a database might read as syntax is still text when it comes back.
   *
   * <p>A status is the name of a state in a lifecycle file somebody wrote, and
   * the shared context is metadata that came off a product, so both are
   * arbitrary strings as far as this class is concerned. It builds its SQL by
   * concatenating them into a string, which is where a quote stops being text.
   */
  @HegelTest(testCases = 30)
  void textWithSqlSignificantCharactersSurvivesAStatusRoundTrip(TestCase tc)
      throws Exception {
    String status = drawSignificantText(tc, "status");
    WorkflowInstance instance =
        instanceOf(status, Priority.getDefault(), 0, new Metadata());

    HsqlWorkflowDatabase database =
        HsqlWorkflowDatabase.withGeneratedInstanceIds();
    try {
      DataSourceWorkflowInstanceRepository repository =
          repositoryOver(database);
      repository.addWorkflowInstance(instance);

      WorkflowInstance stored =
          repository.getWorkflowInstanceById(instance.getId());
      assertNotNull(stored, "an instance whose status holds "
          + "database-significant text could not be read back");
      assertEquals(status, stored.getStatus(), "the status changed in storage");
      assertEquals(1, repository.getNumWorkflowInstancesByStatus(status),
          "the instance was not found under the status it was stored with");
    } finally {
      database.close();
    }
  }

  /**
   * The same, for the shared context. Its values are URL-encoded on the way
   * into the database and decoded on the way out; its keys are not.
   */
  @HegelTest(testCases = 30)
  void textWithSqlSignificantCharactersSurvivesASharedContextRoundTrip(
      TestCase tc) throws Exception {
    String key = "Key" + drawSignificantText(tc, "key");
    String value = drawSignificantText(tc, "value");
    Metadata context = new Metadata();
    context.addMetadata(key, value);
    WorkflowInstance instance = instanceOf("Executing", Priority.getDefault(),
        0, context);

    HsqlWorkflowDatabase database =
        HsqlWorkflowDatabase.withGeneratedInstanceIds();
    try {
      DataSourceWorkflowInstanceRepository repository =
          repositoryOver(database);
      repository.addWorkflowInstance(instance);

      WorkflowInstance stored =
          repository.getWorkflowInstanceById(instance.getId());
      assertNotNull(stored, "an instance whose shared context holds "
          + "database-significant text could not be read back");
      assertEquals(value, stored.getSharedContext().getMetadata(key),
          "the shared context entry changed in storage");
    } finally {
      database.close();
    }
  }

  /**
   * The pages of a repository are a partition of what it holds: every stored
   * instance appears on exactly one page, no page holds anything that was not
   * stored, and no page holds more than a page-size.
   *
   * <p>Paging is the only way the monitor and the command line read the
   * instance list; an instance that falls between two pages is invisible to
   * both, however healthy the repository looks by its counts.
   */
  @HegelTest(testCases = 30)
  void thePagesPartitionTheStoredInstances(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(1).max(9), "count");
    HsqlWorkflowDatabase database =
        HsqlWorkflowDatabase.withGeneratedInstanceIds();
    try {
      DataSourceWorkflowInstanceRepository repository =
          repositoryOver(database);
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
    } finally {
      database.close();
    }
  }

  /**
   * Walking the pages by hand and asking for them by number reach the same
   * instances. A caller browsing the monitor uses the first and next links;
   * one that knows which page it wants asks for it by number. The two are
   * different code paths onto the same list.
   */
  @HegelTest(testCases = 30)
  void walkingThePagesReachesTheSameInstancesAsAskingForThemByNumber(
      TestCase tc) throws Exception {
    int count = tc.draw(integers().min(1).max(9), "count");
    HsqlWorkflowDatabase database =
        HsqlWorkflowDatabase.withGeneratedInstanceIds();
    try {
      DataSourceWorkflowInstanceRepository repository =
          repositoryOver(database);
      for (int i = 0; i < count; i++) {
        Map<String, String> context = new LinkedHashMap<>();
        repository.addWorkflowInstance(drawInstance(tc, "inst" + i, context));
      }
      int totalPages = (count - 1) / PAGE_SIZE + 1;

      Set<String> byNumber = new HashSet<>();
      for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
        byNumber.addAll(idsOf(repository.getPagedWorkflows(pageNum)
            .getPageWorkflows()));
      }

      Set<String> byWalking = new HashSet<>();
      WorkflowInstancePage page = repository.getFirstPage();
      assertEquals(1, page.getPageNum(), "the first page is not page one");
      for (int guard = 0; guard <= totalPages; guard++) {
        byWalking.addAll(idsOf(page.getPageWorkflows()));
        if (page.isLastPage()) {
          break;
        }
        page = repository.getNextPage(page);
      }

      assertEquals(byNumber, byWalking,
          "walking the pages and asking for them by number disagree");
      assertEquals(totalPages, repository.getLastPage().getPageNum(),
          "the last page is not the last page there is");
    } finally {
      database.close();
    }
  }
}
