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

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.commons.database.DatabaseConnectionBuilder;
import org.apache.oodt.commons.database.SqlScript;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;
import java.util.Set;

import javax.sql.DataSource;

import static org.junit.Assert.*;

/**
 * Paging over {@link DataSourceWorkflowInstanceRepository}. There was no
 * harness for this class at all, which is how the arithmetic below stayed
 * wrong: every page boundary skipped one instance, so with four instances at
 * a page size of three the fourth appeared on no page and page 2 came back
 * empty while {@code getTotalPages()} still counted it.
 * {@code InstanceRepoCleaner.cleanRepository} walks exactly these pages.
 */
public class TestDataSourceWorkflowInstancePaging {

  /** four instances over a page size of three: two pages, one boundary. */
  private static final int INSTANCE_COUNT = 4;
  private static final int PAGE_SIZE = 3;

  private DataSource ds;
  private String tmpDirPath;
  private final List<String> seededIds = new ArrayList<String>();

  @Before
  public void setUp() throws Exception {
    File tempFile = File.createTempFile("foo", "bar");
    tempFile.deleteOnExit();
    tmpDirPath = tempFile.getParentFile().getAbsolutePath();

    ds = DatabaseConnectionBuilder.buildDataSource("sa", "",
        "org.hsqldb.jdbcDriver", "jdbc:hsqldb:file:" + tmpDirPath
            + "/testInstPaging;shutdown=true");
    SqlScript schema = new SqlScript("src/test/resources/workflow.sql", ds);
    schema.loadScript();
    schema.execute();
    ds.getConnection().commit();

    seedInstances();
  }

  @After
  public void tearDown() throws Exception {
    ds.getConnection().close();
  }

  /**
   * Seeds instances through addWorkflowInstance, the real path.
   *
   * This used to write the rows straight through JDBC, because that method
   * omitted workflow_instance_id from its INSERT and the schema's plain
   * NOT NULL primary key rejected it. That was #137, and it is fixed, so the
   * workaround is gone and the test exercises the path a caller takes.
   *
   * Every instance points at workflow 1, which the schema seeds along with
   * its tasks.
   */
  private void seedInstances() throws Exception {
    DataSourceWorkflowInstanceRepository repo = repo();
    for (int i = 1; i <= INSTANCE_COUNT; i++) {
      WorkflowInstance inst = new WorkflowInstance();
      Workflow w = new Workflow();
      w.setId("1");
      w.setName("Test Workflow");
      WorkflowTask task = new WorkflowTask();
      task.setTaskId("1");
      task.setTaskName("Test Task");
      Vector<WorkflowTask> tasks = new Vector<WorkflowTask>();
      tasks.add(task);
      w.setTasks(tasks);
      inst.setWorkflow(w);
      inst.setCurrentTaskId("1");
      inst.setStatus("QUEUED");
      inst.setStartDateTimeIsoStr("2026-01-0" + i + "T00:00:00.000Z");
      inst.setEndDateTimeIsoStr("2026-01-0" + i + "T01:00:00.000Z");
      inst.setCurrentTaskStartDateTimeIsoStr("2026-01-0" + i + "T00:00:00.000Z");
      inst.setCurrentTaskEndDateTimeIsoStr("2026-01-0" + i + "T01:00:00.000Z");
      inst.setSharedContext(new Metadata());
      repo.addWorkflowInstance(inst);
      seededIds.add(inst.getId());
    }
  }

  private DataSourceWorkflowInstanceRepository repo() {
    return new DataSourceWorkflowInstanceRepository(ds, false, PAGE_SIZE);
  }

  private static List<String> idsOn(WorkflowInstancePage page) {
    List<String> ids = new ArrayList<String>();
    if (page == null || page.getPageWorkflows() == null) {
      return ids;
    }
    for (Object o : page.getPageWorkflows()) {
      ids.add(((WorkflowInstance) o).getId());
    }
    return ids;
  }

  @Test
  public void testTheFirstPageIsFull() throws Exception {
    WorkflowInstancePage first = repo().getFirstPage();

    assertNotNull(first);
    assertEquals(2, first.getTotalPages());
    assertEquals(PAGE_SIZE, idsOn(first).size());
  }

  /**
   * The page after the first used to come back empty: the cursor was moved
   * onto the page's first row and then advanced past it before anything was
   * read.
   */
  @Test
  public void testTheLastPageHoldsTheRemainder() throws Exception {
    DataSourceWorkflowInstanceRepository repo = repo();
    WorkflowInstancePage second = repo.getPagedWorkflows(2);

    assertNotNull(second);
    assertEquals("the last page came back empty", 1, idsOn(second).size());
  }

  /** and no instance falls between the two pages. */
  @Test
  public void testEveryInstanceAppearsOnSomePage() throws Exception {
    DataSourceWorkflowInstanceRepository repo = repo();

    Set<String> seen = new HashSet<String>();
    for (int page = 1; page <= 2; page++) {
      seen.addAll(idsOn(repo.getPagedWorkflows(page)));
    }

    assertEquals("an instance appears on no page at all", INSTANCE_COUNT,
        seen.size());
    for (String id : seededIds) {
      assertTrue("instance " + id + " is on no page", seen.contains(id));
    }
  }

  /** and none appears twice. */
  @Test
  public void testNoInstanceAppearsOnTwoPages() throws Exception {
    DataSourceWorkflowInstanceRepository repo = repo();

    List<String> all = new ArrayList<String>();
    all.addAll(idsOn(repo.getPagedWorkflows(1)));
    all.addAll(idsOn(repo.getPagedWorkflows(2)));

    assertEquals("an instance was returned on more than one page",
        all.size(), new HashSet<String>(all).size());
  }

  /** walking forward with getNextPage covers the same ground. */
  @Test
  public void testWalkingForwardCoversEveryInstance() throws Exception {
    DataSourceWorkflowInstanceRepository repo = repo();

    Set<String> seen = new HashSet<String>();
    WorkflowInstancePage page = repo.getFirstPage();
    seen.addAll(idsOn(page));
    while (!page.isLastPage()) {
      page = repo.getNextPage(page);
      seen.addAll(idsOn(page));
    }

    assertEquals(INSTANCE_COUNT, seen.size());
  }
}
