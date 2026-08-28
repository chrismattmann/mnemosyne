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

import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

import static org.junit.Assert.*;

/**
 * paginateWorkflows built its list from keySet(), which holds instance ids,
 * and the comparator cast each element to WorkflowInstance -- so paging over
 * this repository always threw ClassCastException. getFirstPage catches it
 * and hands back null, so nothing surfaced: a page came back empty, which is
 * indistinguishable from "no instances match", and any UI or CLI paging over
 * the repository showed an empty list for a repository that was not empty.
 */
public class TestMemoryWorkflowInstanceRepositoryPaging {

    private static WorkflowInstance instance(String id, String startTime) {
        WorkflowInstance inst = new WorkflowInstance();
        inst.setId(id);
        Workflow w = new Workflow();
        w.setId("urn:test:wf");
        w.setName("Test");
        WorkflowTask task = new WorkflowTask();
        task.setTaskId("urn:test:task");
        Vector<WorkflowTask> tasks = new Vector<WorkflowTask>();
        tasks.add(task);
        w.setTasks(tasks);
        inst.setWorkflow(w);
        inst.setCurrentTaskId("urn:test:task");
        inst.setStartDateTimeIsoStr(startTime);
        inst.setStatus("QUEUED");
        return inst;
    }

    private static MemoryWorkflowInstanceRepository repoWith(int count)
            throws Exception {
        MemoryWorkflowInstanceRepository repo =
                new MemoryWorkflowInstanceRepository(3);
        for (int i = 0; i < count; i++) {
            repo.addWorkflowInstance(
                    instance("inst-" + i, "2026-01-0" + (i + 1) + "T00:00:00.000Z"));
        }
        return repo;
    }

    /** The counterexample: one instance, and getFirstPage returns null. */
    @Test
    public void testARepositoryWithOneInstanceHasAFirstPage() throws Exception {
        WorkflowInstancePage page = repoWith(1).getFirstPage();

        assertNotNull("getFirstPage returned null for a non-empty repository",
                page);
        assertNotNull(page.getPageWorkflows());
        assertEquals(1, page.getPageWorkflows().size());
    }

    /** getLastPage dereferenced that null. */
    @Test
    public void testARepositoryWithInstancesHasALastPage() throws Exception {
        assertNotNull(repoWith(4).getLastPage());
    }

    /** Every instance appears on some page. */
    @Test
    public void testEveryInstanceAppearsOnSomePage() throws Exception {
        MemoryWorkflowInstanceRepository repo = repoWith(7);

        Set<String> seen = new HashSet<String>();
        WorkflowInstancePage page = repo.getFirstPage();
        assertNotNull(page);
        for (int guard = 0; guard < 20; guard++) {
            for (Object o : page.getPageWorkflows()) {
                seen.add(((WorkflowInstance) o).getId());
            }
            if (page.isLastPage()) {
                break;
            }
            page = repo.getNextPage(page);
        }

        assertEquals("an instance appears on no page", 7, seen.size());
    }

    /** An empty repository still reports a blank first page rather than null. */
    @Test
    public void testAnEmptyRepositoryStillAnswers() throws Exception {
        assertNotNull(repoWith(0).getFirstPage());
    }
}
