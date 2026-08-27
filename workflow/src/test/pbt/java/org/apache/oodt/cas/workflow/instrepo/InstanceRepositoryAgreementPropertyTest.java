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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.HsqlWorkflowDatabase;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

/**
 * Properties stating that the three {@link WorkflowInstanceRepository}
 * implementations answer the same questions the same way.
 *
 * <p>Which one a deployment gets is a line in a properties file:
 * {@code MemoryWorkflowInstanceRepositoryFactory},
 * {@code DataSourceWorkflowInstanceRepositoryFactory} or
 * {@code LuceneWorkflowInstanceRepositoryFactory}. Everything above the
 * interface — the engine, the monitor, the command line, the cleaner tool —
 * is written once against the interface and has no way of knowing which it
 * got. Where the three disagree, whichever is in the minority is a deployment
 * on which that code does something different, and the disagreement is worth
 * more than any one of them stated on its own.
 *
 * <p>Paging is not stated here. All three are known to page differently, and
 * the differences are recorded against the implementations themselves.
 */
class InstanceRepositoryAgreementPropertyTest {

  private static final List<String> STATUSES =
      List.of("Queued", "Executing", "Finished");

  private static final List<Priority> PRIORITIES = List.of(Priority.LOW,
      Priority.MEDIUM, Priority.HIGH, Priority.getPriority(3.25));

  private static final int PAGE_SIZE = 3;

  /** An instance whose identifiers suit all three stores. */
  private static WorkflowInstance instanceOf(String status, Priority priority,
      Metadata context) {
    Workflow workflow = new Workflow();
    workflow.setId("1");
    workflow.setName("Test Workflow");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("1");
    task.setTaskName("Test Task");
    task.setTaskInstanceClassName(
        "org.apache.oodt.cas.workflow.examples.NoOpTask");
    List<WorkflowTask> tasks = new Vector<WorkflowTask>();
    tasks.add(task);
    workflow.setTasks(tasks);

    WorkflowInstance instance = new WorkflowInstance();
    instance.setWorkflow(workflow);
    instance.setCurrentTaskId("1");
    instance.setStatus(status);
    instance.setPriority(priority);
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

  /** How many of {@code values} equal {@code value}. */
  private static int countOf(List<String> values, String value) {
    int count = 0;
    for (String each : values) {
      if (each.equals(value)) {
        count++;
      }
    }
    return count;
  }

  /** Names a repository in an assertion message. */
  private static String nameOf(WorkflowInstanceRepository repository) {
    return repository.getClass().getSimpleName();
  }

  /**
   * The three repositories agree about what they are holding.
   *
   * <p>The same instances are stored in all three, and then all three are
   * asked the four questions the monitor and the engine ask: how many
   * instances there are, how many are in a given status, what the instances
   * are, and which instances are in a given status. The in-memory repository
   * is the reference, because it is the plainest of the three and the one the
   * existing suites already state properties about.
   */
  @HegelTest(testCases = 25)
  void theThreeRepositoriesAgreeAboutWhatTheyHold(TestCase tc)
      throws Exception {
    // At least one, because an index that has never been written cannot be
    // read at all; that is stated against the Lucene repository itself.
    int count = tc.draw(integers().min(1).max(5), "count");
    List<String> statuses = new ArrayList<>();
    List<Priority> priorities = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      statuses.add(tc.draw(sampledFrom(STATUSES), "status" + i));
      priorities.add(tc.draw(sampledFrom(PRIORITIES), "priority" + i));
    }

    Path index = Files.createTempDirectory("pbt-agreement");
    HsqlWorkflowDatabase database =
        HsqlWorkflowDatabase.withGeneratedInstanceIds();
    try {
      MemoryWorkflowInstanceRepository memory =
          new MemoryWorkflowInstanceRepository(PAGE_SIZE);
      DataSourceWorkflowInstanceRepository jdbc =
          new DataSourceWorkflowInstanceRepository(database.dataSource(), false,
              PAGE_SIZE);
      LuceneWorkflowInstanceRepository lucene =
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE);
      List<WorkflowInstanceRepository> repositories =
          List.of(memory, jdbc, lucene);

      for (int i = 0; i < count; i++) {
        for (WorkflowInstanceRepository repository : repositories) {
          repository.addWorkflowInstance(
              instanceOf(statuses.get(i), priorities.get(i), new Metadata()));
        }
      }

      for (WorkflowInstanceRepository repository : repositories) {
        assertEquals(count, repository.getNumWorkflowInstances(),
            nameOf(repository) + " counts a different number of instances "
                + "than were stored in it");

        List held = repository.getWorkflowInstances();
        assertNotNull(held,
            nameOf(repository) + " answers nothing at all when asked what "
                + "instances it holds");
        assertEquals(count, held.size(),
            nameOf(repository) + " lists a different number of instances "
                + "than were stored in it");

        for (String status : STATUSES) {
          int expected = countOf(statuses, status);
          assertEquals(expected,
              repository.getNumWorkflowInstancesByStatus(status),
              nameOf(repository) + " counts a different number of " + status
                  + " instances than were stored in it");
          List byStatus = repository.getWorkflowInstancesByStatus(status);
          assertNotNull(byStatus,
              nameOf(repository) + " answers nothing at all when asked which "
                  + "instances are " + status);
          assertEquals(expected, byStatus.size(),
              nameOf(repository) + " lists a different number of " + status
                  + " instances than it counts");
        }
      }
    } finally {
      database.close();
      deleteRecursively(index);
    }
  }

  /**
   * The three repositories agree about what an instance says when it is read
   * back: its status and its priority.
   *
   * <p>Priority is what the engine's sorters order the run queue by, and it is
   * already known to be lost over the manager's RPC interface. Whether it
   * survives storage is a separate question with a separate answer per store,
   * which is exactly what this states.
   */
  @HegelTest(testCases = 25)
  void theThreeRepositoriesAgreeAboutWhatAnInstanceSaysWhenReadBack(
      TestCase tc) throws Exception {
    String status = tc.draw(sampledFrom(STATUSES), "status");
    Priority priority = tc.draw(sampledFrom(PRIORITIES), "priority");
    Map<String, String> context = new LinkedHashMap<>();
    int contextSize = tc.draw(integers().min(0).max(2), "contextSize");
    for (int i = 0; i < contextSize; i++) {
      context.put("Key" + tc.draw(integers().min(0).max(3), "key" + i),
          "Value" + tc.draw(integers().min(0).max(3), "val" + i));
    }

    Path index = Files.createTempDirectory("pbt-agreement-read");
    HsqlWorkflowDatabase database =
        HsqlWorkflowDatabase.withGeneratedInstanceIds();
    try {
      List<WorkflowInstanceRepository> repositories = List.of(
          new MemoryWorkflowInstanceRepository(PAGE_SIZE),
          new DataSourceWorkflowInstanceRepository(database.dataSource(), false,
              PAGE_SIZE),
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE));

      for (WorkflowInstanceRepository repository : repositories) {
        WorkflowInstance instance =
            instanceOf(status, priority, contextOf(context));
        repository.addWorkflowInstance(instance);

        WorkflowInstance stored =
            repository.getWorkflowInstanceById(instance.getId());
        assertNotNull(stored, nameOf(repository)
            + " cannot read back the instance just stored in it");
        assertEquals(status, stored.getStatus(),
            nameOf(repository) + " changed the status in storage");
        assertEquals(priority.getValue(), stored.getPriority().getValue(),
            nameOf(repository) + " changed the priority in storage");
        for (Map.Entry<String, String> entry : context.entrySet()) {
          assertEquals(entry.getValue(),
              stored.getSharedContext().getMetadata(entry.getKey()),
              nameOf(repository) + " changed shared context entry "
                  + entry.getKey() + " in storage");
        }
      }
    } finally {
      database.close();
      deleteRecursively(index);
    }
  }

  /**
   * The three repositories agree that updating an instance none of them has
   * seen stores nothing.
   *
   * <p>An engine can outlive the manager's record of an instance — the cleaner
   * removes finished instances while an engine is still reporting on one — and
   * a record that reappeared on a status update would be a workflow nobody is
   * running, showing in the monitor for ever.
   */
  @HegelTest(testCases = 25)
  void theThreeRepositoriesAgreeThatUpdatingAStrangerStoresNothing(TestCase tc)
      throws Exception {
    String status = tc.draw(sampledFrom(STATUSES), "status");
    String strangerId = String.valueOf(
        tc.draw(integers().min(5000).max(9999), "strangerId"));

    Path index = Files.createTempDirectory("pbt-agreement-stranger");
    HsqlWorkflowDatabase database =
        HsqlWorkflowDatabase.withGeneratedInstanceIds();
    try {
      List<WorkflowInstanceRepository> repositories = List.of(
          new MemoryWorkflowInstanceRepository(PAGE_SIZE),
          new DataSourceWorkflowInstanceRepository(database.dataSource(), false,
              PAGE_SIZE),
          new LuceneWorkflowInstanceRepository(index.toString(), PAGE_SIZE));

      for (WorkflowInstanceRepository repository : repositories) {
        // Something has to be stored first: an index that has never been
        // written cannot be read at all, which is stated separately.
        repository.addWorkflowInstance(
            instanceOf(status, Priority.getDefault(), new Metadata()));

        WorkflowInstance stranger =
            instanceOf(status, Priority.getDefault(), new Metadata());
        stranger.setId(strangerId);
        repository.updateWorkflowInstance(stranger);

        assertEquals(1, repository.getNumWorkflowInstances(),
            nameOf(repository) + " stored an instance it had never seen when "
                + "asked to update it");
        assertNull(repository.getWorkflowInstanceById(strangerId),
            nameOf(repository) + " can read back an instance it was only ever "
                + "asked to update");
      }
    } finally {
      database.close();
      deleteRecursively(index);
    }
  }
}
