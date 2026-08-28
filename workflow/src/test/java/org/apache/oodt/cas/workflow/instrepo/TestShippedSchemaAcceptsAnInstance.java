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
import org.apache.oodt.commons.database.DatabaseConnectionBuilder;
import org.apache.oodt.commons.database.SqlScript;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

import javax.sql.DataSource;

import static org.junit.Assert.*;

/**
 * The workflow manager could not record a single instance on any database but
 * Oracle.
 *
 * workflow_instances declared workflow_instance_id as a plain int primary key
 * -- no DEFAULT, no IDENTITY, no AUTO_INCREMENT -- and addWorkflowInstance
 * does not supply one, so every insert was rejected with "Attempt to insert
 * null into a non-nullable column". Oracle worked because
 * workflow_oracle_create_sequences.sql supplies a sequence and a trigger;
 * every other backend got a workflow manager that started cleanly, accepted a
 * workflow definition and failed on the first instance. This is the schema a
 * RADiX-generated project points at.
 *
 * This runs against the shipped schema, not a fixture written for the test.
 */
public class TestShippedSchemaAcceptsAnInstance {

    private DataSource ds;
    private DataSourceWorkflowInstanceRepository repo;

    @Before
    public void setUp() throws Exception {
        File tempFile = File.createTempFile("foo", "bar");
        tempFile.deleteOnExit();
        String tmpDirPath = tempFile.getParentFile().getAbsolutePath();

        ds = DatabaseConnectionBuilder.buildDataSource("sa", "",
                "org.hsqldb.jdbcDriver",
                "jdbc:hsqldb:file:" + tmpDirPath + "/testShippedSchema;shutdown=true");

        // The schema as shipped, from src/main/resources.
        SqlScript schema = new SqlScript("src/main/resources/workflow.sql", ds);
        schema.loadScript();
        schema.execute();
        ds.getConnection().commit();

        repo = new DataSourceWorkflowInstanceRepository(ds, false, 20);
    }

    @After
    public void tearDown() throws Exception {
        ds.getConnection().close();
    }

    private static WorkflowInstance instance(String status) {
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
        inst.setStatus(status);
        inst.setStartDateTimeIsoStr("2026-01-01T00:00:00.000Z");
        inst.setEndDateTimeIsoStr("2026-01-01T01:00:00.000Z");
        inst.setCurrentTaskStartDateTimeIsoStr("2026-01-01T00:00:00.000Z");
        inst.setCurrentTaskEndDateTimeIsoStr("2026-01-01T01:00:00.000Z");
        inst.setSharedContext(new Metadata());
        return inst;
    }

    /**
     * The shipped file has to load at all. It declared workflow_instances
     * twice -- the second, a quoteFields alternative, was live SQL rather
     * than commented out -- so the script failed on the duplicate before
     * anything could use it.
     */
    @Test
    public void testTheShippedSchemaLoads() throws Exception {
        assertNotNull(ds.getConnection());
    }

    @Test
    public void testTheShippedSchemaAcceptsAnInstance() throws Exception {
        WorkflowInstance inst = instance("QUEUED");

        repo.addWorkflowInstance(inst);

        assertNotNull("no id was assigned", inst.getId());
        assertNotNull("the instance could not be read back",
                repo.getWorkflowInstanceById(inst.getId()));
    }

    /** Each instance gets its own id, which MAX(...) could not promise. */
    @Test
    public void testEveryInstanceGetsItsOwnId() throws Exception {
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < 10; i++) {
            WorkflowInstance inst = instance("QUEUED");
            repo.addWorkflowInstance(inst);
            ids.add(inst.getId());
        }

        assertEquals("instances were handed the same id", 10, ids.size());
        for (String id : ids) {
            assertNotNull(repo.getWorkflowInstanceById(id));
        }
    }

    /** And the id names that instance rather than whichever row is highest. */
    @Test
    public void testTheIdNamesTheInstanceThatWasStored() throws Exception {
        WorkflowInstance first = instance("FIRST");
        repo.addWorkflowInstance(first);
        WorkflowInstance second = instance("SECOND");
        repo.addWorkflowInstance(second);

        assertEquals("FIRST", repo.getWorkflowInstanceById(first.getId()).getStatus());
        assertEquals("SECOND", repo.getWorkflowInstanceById(second.getId()).getStatus());
    }
}
