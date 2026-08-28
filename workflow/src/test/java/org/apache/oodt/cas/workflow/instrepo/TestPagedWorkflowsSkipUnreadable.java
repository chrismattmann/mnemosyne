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
import org.apache.oodt.cas.workflow.util.AvroTypeFactory;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import static org.junit.Assert.*;

/**
 * A page whose ids no longer resolve.
 *
 * paginateWorkflows lists instance ids and getPagedWorkflows reads each one
 * back. An instance written or removed between the two -- ordinary while an
 * engine is starting work -- meant getWorkflowInstanceById returned null and
 * a null went into the page. AvroTypeFactory then dereferenced it while
 * serialising, so paging the instances during ingest intermittently took the
 * workflow manager's RPC down with a NullPointerException that has no
 * declared error to be marshalled into.
 *
 * This pins the mechanism rather than the timing: it makes the lookup return
 * null on purpose, which the race does by accident.
 */
public class TestPagedWorkflowsSkipUnreadable {

    private static WorkflowInstance instance(String id) {
        WorkflowInstance inst = new WorkflowInstance();
        inst.setId(id);
        Workflow w = new Workflow();
        w.setId("urn:test:wf");
        w.setName("Test");
        WorkflowTask task = new WorkflowTask();
        task.setTaskId("urn:test:task");
        List<WorkflowTask> tasks = new Vector<WorkflowTask>();
        tasks.add(task);
        w.setTasks(tasks);
        inst.setWorkflow(w);
        inst.setCurrentTaskId("urn:test:task");
        inst.setStartDateTimeIsoStr("2026-01-01T00:00:00.000Z");
        inst.setStatus("QUEUED");
        return inst;
    }

    /**
     * A repository where one instance has gone by the time it is read.
     *
     * The id is chosen after the fact because addWorkflowInstance assigns its
     * own, so an id set before adding does not survive.
     */
    private static final class RacingRepository
            extends MemoryWorkflowInstanceRepository {

        private String unreadable;

        private RacingRepository() {
            super(10);
        }

        @Override
        public WorkflowInstance getWorkflowInstanceById(String workflowInstId)
                throws org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException {
            if (unreadable != null && unreadable.equals(workflowInstId)) {
                return null;
            }
            return super.getWorkflowInstanceById(workflowInstId);
        }
    }

    /** Three instances, the middle one unreadable. */
    private static RacingRepository repositoryWithOneGone() throws Exception {
        RacingRepository repo = new RacingRepository();
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            WorkflowInstance inst = instance("inst-" + i);
            repo.addWorkflowInstance(inst);
            ids.add(inst.getId());
        }
        repo.unreadable = ids.get(1);
        return repo;
    }

    @Test
    public void testAPageNeverContainsANullInstance() throws Exception {
        RacingRepository repo = repositoryWithOneGone();

        WorkflowInstancePage page = repo.getFirstPage();

        assertNotNull(page);
        assertNotNull(page.getPageWorkflows());
        for (Object inst : page.getPageWorkflows()) {
            assertNotNull("a null instance reached the page", inst);
        }
        assertEquals("the readable instances should still be there",
                2, page.getPageWorkflows().size());
    }

    /** And such a page serialises, rather than failing the whole call. */
    @Test
    public void testSuchAPageCanStillBeSerialised() throws Exception {
        RacingRepository repo = repositoryWithOneGone();

        assertNotNull(AvroTypeFactory.getAvroWorkflowInstancePage(repo.getFirstPage()));
    }

    /**
     * The serialisation boundary is guarded on its own account, since a null
     * arriving there is what took the RPC down.
     */
    @Test
    public void testTheFactorySkipsANullInstance() {
        List<WorkflowInstance> instances = new ArrayList<WorkflowInstance>();
        instances.add(instance("a"));
        instances.add(null);
        instances.add(instance("b"));

        assertEquals(2, AvroTypeFactory.getAvroWorkflowInstances(instances).size());
    }

    /** and a single null does not throw either. */
    @Test
    public void testTheFactoryToleratesASingleNull() {
        assertNull(AvroTypeFactory.getAvroWorkflowInstance(null));
    }
}
