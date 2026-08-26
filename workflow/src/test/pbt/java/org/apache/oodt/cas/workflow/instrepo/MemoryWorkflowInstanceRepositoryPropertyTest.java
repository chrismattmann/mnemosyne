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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;

/**
 * Properties of {@link MemoryWorkflowInstanceRepository}, the instance store
 * used when no database is configured.
 *
 * <p>It is the workflow manager's record of everything currently running: the
 * engine writes a status to it on every state change and the monitor reads
 * back over it. The paging behaviour it inherits from
 * {@link AbstractPaginatibleInstanceRepository} is stated here too, since that
 * class is abstract and this is the in-memory implementation of it.
 */
class MemoryWorkflowInstanceRepositoryPropertyTest {

  /** A small alphabet of statuses, so that instances share them. */
  private static final List<String> STATUSES =
      List.of("Queued", "Executing", "Finished");

  private static WorkflowInstance instanceWithStatus(String status) {
    WorkflowInstance instance = new WorkflowInstance();
    instance.setStatus(status);
    return instance;
  }

  /** Adds {@code count} instances with drawn statuses, returning them. */
  private static List<WorkflowInstance> populate(TestCase tc,
      MemoryWorkflowInstanceRepository repository, int count)
      throws InstanceRepositoryException {
    List<WorkflowInstance> added = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      WorkflowInstance instance =
          instanceWithStatus(tc.draw(sampledFrom(STATUSES), "status" + i));
      repository.addWorkflowInstance(instance);
      added.add(instance);
    }
    return added;
  }

  /**
   * An added instance is given an identifier and can be fetched back under it,
   * and no two instances are given the same one. The engine adds an instance
   * and then refers to it by the identifier it was handed for the rest of the
   * run, so a repeated identifier is one workflow's record overwriting
   * another's.
   */
  @HegelTest
  void everyAddedInstanceIsRetrievableUnderAnIdentifierOfItsOwn(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(0).max(8), "count");
    MemoryWorkflowInstanceRepository repository =
        new MemoryWorkflowInstanceRepository(3);

    List<WorkflowInstance> added = populate(tc, repository, count);

    Set<String> ids = new HashSet<>();
    for (WorkflowInstance instance : added) {
      assertNotNull(instance.getId(), "an added instance was given no id");
      assertTrue(ids.add(instance.getId()),
          "two instances were both filed under " + instance.getId());
      assertSame(instance, repository.getWorkflowInstanceById(instance.getId()),
          "the instance filed under " + instance.getId() + " is not the one added");
    }
    assertEquals(count, repository.getNumWorkflowInstances(),
        "the repository holds a different number of instances than were added");
    assertEquals(count, repository.getWorkflowInstances().size(),
        "the repository listed a different number of instances than it counts");
  }

  /**
   * Asking for an identifier the repository was never given answers with
   * nothing rather than failing. The manager looks up whatever identifier a
   * client sends it, and clients send stale ones.
   */
  @HegelTest
  void anUnknownIdentifierIsSimplyAbsent(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(0).max(4), "count");
    MemoryWorkflowInstanceRepository repository =
        new MemoryWorkflowInstanceRepository(3);
    populate(tc, repository, count);

    assertNull(repository.getWorkflowInstanceById("urn:nothing:0"),
        "an identifier that was never issued found an instance");
  }

  /**
   * The instances of each status, taken together, are all the instances and
   * nothing more, and the counts agree with the lists. The monitor shows a
   * count per status beside a list per status, and the two are read through
   * different methods.
   */
  @HegelTest
  void theStatusesPartitionTheInstances(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(0).max(8), "count");
    MemoryWorkflowInstanceRepository repository =
        new MemoryWorkflowInstanceRepository(3);
    List<WorkflowInstance> added = populate(tc, repository, count);

    int seen = 0;
    for (String status : STATUSES) {
      List byStatus = repository.getWorkflowInstancesByStatus(status);
      assertEquals(byStatus.size(),
          repository.getNumWorkflowInstancesByStatus(status),
          "the count and the list disagree for " + status);
      for (Object each : byStatus) {
        WorkflowInstance instance = (WorkflowInstance) each;
        assertEquals(status, instance.getStatus(),
            "an instance filed under " + status + " reports "
                + instance.getStatus());
        assertTrue(added.contains(instance),
            "an instance nobody added was listed under " + status);
      }
      seen += byStatus.size();
    }
    assertEquals(count, seen,
        "the statuses account for " + seen + " of " + count + " instances");
  }

  /**
   * Updating an instance replaces the record under its identifier and leaves
   * the count alone. Every state change an engine makes arrives this way.
   */
  @HegelTest
  void updatingReplacesTheRecordUnderThatIdentifier(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(1).max(6), "count");
    String newStatus = tc.draw(sampledFrom(STATUSES), "newStatus");
    MemoryWorkflowInstanceRepository repository =
        new MemoryWorkflowInstanceRepository(3);
    List<WorkflowInstance> added = populate(tc, repository, count);
    WorkflowInstance target =
        added.get(tc.draw(integers().min(0).max(count - 1), "pick"));

    WorkflowInstance replacement = instanceWithStatus(newStatus);
    replacement.setId(target.getId());
    repository.updateWorkflowInstance(replacement);

    assertSame(replacement, repository.getWorkflowInstanceById(target.getId()),
        "the update did not take");
    assertEquals(count, repository.getNumWorkflowInstances(),
        "the update changed how many instances there are");
  }

  /**
   * Updating an instance the repository does not know about does not add it.
   * An engine may outlive the manager's memory of an instance, and a record
   * that reappeared on a status update would be a workflow nobody is running.
   */
  @HegelTest
  void updatingAnUnknownInstanceAddsNothing(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(0).max(4), "count");
    MemoryWorkflowInstanceRepository repository =
        new MemoryWorkflowInstanceRepository(3);
    populate(tc, repository, count);

    WorkflowInstance stranger =
        instanceWithStatus(tc.draw(sampledFrom(STATUSES), "status"));
    stranger.setId("urn:never:added");
    repository.updateWorkflowInstance(stranger);

    assertEquals(count, repository.getNumWorkflowInstances(),
        "updating an unknown instance added it");
    assertNull(repository.getWorkflowInstanceById("urn:never:added"),
        "an unknown instance was stored by an update");
  }

  /**
   * Removing an instance removes that one and leaves the rest, and clearing
   * removes all of them. The cleaner tool removes finished instances one at a
   * time from a live repository.
   */
  @HegelTest
  void removingTakesOutOneInstanceAndClearingTakesOutAll(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(1).max(6), "count");
    MemoryWorkflowInstanceRepository repository =
        new MemoryWorkflowInstanceRepository(3);
    List<WorkflowInstance> added = populate(tc, repository, count);
    WorkflowInstance target =
        added.get(tc.draw(integers().min(0).max(count - 1), "pick"));

    repository.removeWorkflowInstance(target);

    assertEquals(count - 1, repository.getNumWorkflowInstances(),
        "removing one instance changed the count by something else");
    assertNull(repository.getWorkflowInstanceById(target.getId()),
        "the removed instance is still there");
    for (WorkflowInstance instance : added) {
      if (instance != target) {
        assertNotNull(repository.getWorkflowInstanceById(instance.getId()),
            "removing " + target.getId() + " also removed "
                + instance.getId());
      }
    }

    assertTrue(repository.clearWorkflowInstances(), "clearing reported failure");
    assertEquals(0, repository.getNumWorkflowInstances(),
        "clearing left instances behind");
  }

  /**
   * A repository holding instances has a first page, and that page holds
   * instances it is holding — at most a page-size of them, each one of the
   * instances that were added.
   *
   * <p>This is how the monitor lists what is running: it asks for the first
   * page and walks on from there. A repository that cannot produce a page
   * cannot be browsed at all, however many instances it is tracking.
   */
  @HegelTest
  void aRepositoryWithInstancesHasAFirstPageOfThem(TestCase tc)
      throws Exception {
    int pageSize = tc.draw(integers().min(1).max(4), "pageSize");
    int count = tc.draw(integers().min(1).max(8), "count");
    MemoryWorkflowInstanceRepository repository =
        new MemoryWorkflowInstanceRepository(pageSize);
    List<WorkflowInstance> added = populate(tc, repository, count);

    WorkflowInstancePage page = repository.getFirstPage();

    assertNotNull(page, "a repository holding " + count
        + " instances produced no first page");
    assertEquals(1, page.getPageNum(), "the first page is not numbered one");
    assertTrue(page.getPageWorkflows().size() <= pageSize,
        "the page holds more instances than a page holds");
    assertFalse(page.getPageWorkflows().isEmpty(),
        "the first page of a non-empty repository is empty");
    for (Object each : page.getPageWorkflows()) {
      assertTrue(added.contains(each),
          "the page holds an instance nobody added");
    }
  }

  /**
   * An empty repository pages to a blank page rather than to nothing. The
   * monitor asks for a page before it knows whether anything is running.
   */
  @HegelTest
  void anEmptyRepositoryPagesToABlankPage(TestCase tc) throws Exception {
    int pageNum = tc.draw(integers().min(-2).max(4), "pageNum");
    MemoryWorkflowInstanceRepository repository =
        new MemoryWorkflowInstanceRepository(3);

    WorkflowInstancePage page = repository.getPagedWorkflows(pageNum);

    assertNotNull(page, "an empty repository produced no page at all");
    assertEquals(0, page.getPageNum(), "a blank page is numbered " + page.getPageNum());
    assertEquals(0, page.getTotalPages(),
        "a blank page claims " + page.getTotalPages() + " pages exist");
    assertTrue(page.getPageWorkflows().isEmpty(),
        "a blank page holds instances");
  }
}
