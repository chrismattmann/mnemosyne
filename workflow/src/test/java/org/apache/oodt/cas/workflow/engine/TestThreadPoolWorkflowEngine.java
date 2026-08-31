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


package org.apache.oodt.cas.workflow.engine;

//OODT imports
import org.apache.commons.io.FileUtils;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.instrepo.LuceneWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.instrepo.MemoryWorkflowInstanceRepository;
import EDU.oswego.cs.dl.util.concurrent.PooledExecutor;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowStatus;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.commons.util.DateConvert;

//JDK imports
import java.io.File;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.LogManager;

//Junit imports
import junit.framework.TestCase;

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * Test suite for the ThreadPoolWorkflowEngine.
 * </p>.
 */
public class TestThreadPoolWorkflowEngine extends TestCase {

    public TestThreadPoolWorkflowEngine() {
        // suppress WARNING level and below because we don't want
        // the warning message where we test if start date time is AFTER
        // end date time below
        LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
    }

    public void testCurrentTaskWallClockTime() {
        // at first, there is no start date time
        WorkflowInstance inst = new WorkflowInstance();
        WorkflowTask task = new WorkflowTask();
        task.setTaskId("urn:oodt:testTask");
        ParentChildWorkflow workflow = new ParentChildWorkflow(new Graph());
        workflow.getTasks().add(task);
        inst.setParentChildWorkflow(workflow);
        inst.setCurrentTaskId("urn:oodt:testTask");
        assertEquals(0.0, ThreadPoolWorkflowEngine
            .getCurrentTaskWallClockMinutes(inst));

        // now set start date time, and assert that wall clock minutes > 0.
        // The start is put a minute in the past rather than at "now": elapsed
        // time is measured from the start to the current instant, so a start
        // of exactly now can land in the same millisecond as the measurement
        // and legitimately yield 0.0. That made this assertion fail whenever
        // the JVM was warm enough to execute both in under a millisecond.
        inst.setCurrentTaskStartDateTimeIsoStr(DateConvert
                .isoFormat(new Date(System.currentTimeMillis() - 60000)));
        assertTrue(ThreadPoolWorkflowEngine
                .getCurrentTaskWallClockMinutes(inst) > 0.0);

        // set end date time to "" and make sure wall clock mins still greater
        // than 0
        inst.setCurrentTaskEndDateTimeIsoStr("");
        assertTrue(ThreadPoolWorkflowEngine
                .getCurrentTaskWallClockMinutes(inst) > 0.0);

        // set the end date time, compute it, and make sure it stays the same
        String endDateTimeIsoStr = DateConvert.isoFormat(new Date());
        inst.setCurrentTaskEndDateTimeIsoStr(endDateTimeIsoStr);
        double wallClockMins = ThreadPoolWorkflowEngine
                .getCurrentTaskWallClockMinutes(inst);
        assertEquals(wallClockMins, ThreadPoolWorkflowEngine
            .getCurrentTaskWallClockMinutes(inst));
        assertEquals(wallClockMins, ThreadPoolWorkflowEngine
            .getCurrentTaskWallClockMinutes(inst));

        // set the start date time after the end date time
        // make sure that the wall cock time is 0.0
        inst.setCurrentTaskStartDateTimeIsoStr(DateConvert
                .isoFormat(new Date()));
        assertEquals(0.0, ThreadPoolWorkflowEngine
            .getCurrentTaskWallClockMinutes(inst));

    }

    /**
     * WorkflowEngine.shutdown() defaults to doing nothing, and this engine --
     * the one you get when no factory is named, so the one most deployments
     * run -- did not override it. Its PooledExecutor therefore outlived every
     * shutdown of the manager that owned it, which is the thread pool
     * AvroRpcWorkflowManager.shutdown() was supposed to be releasing.
     *
     * The pool is private and the class exposes nothing about it, so this
     * reads the field directly. It is our own class, so no module opens are
     * involved.
     */
    public void testShutdownStopsTheWorkerPool() throws Exception {
        ThreadPoolWorkflowEngine engine = new ThreadPoolWorkflowEngine(
                new MemoryWorkflowInstanceRepository(20), 10, 4, 1, 5L, false, null);

        java.lang.reflect.Field poolField =
                ThreadPoolWorkflowEngine.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        PooledExecutor pool = (PooledExecutor) poolField.get(engine);
        assertNotNull(pool);
        assertFalse("the pool was already shut down before the test ran",
                pool.isTerminatedAfterShutdown());

        engine.shutdown();

        assertTrue("shutdown() left the worker pool running",
                pool.isTerminatedAfterShutdown());
    }

    /** shutting down twice is harmless: hooks and callers both do it. */
    public void testShuttingDownTwiceIsHarmless() {
        ThreadPoolWorkflowEngine engine = new ThreadPoolWorkflowEngine(
                new MemoryWorkflowInstanceRepository(20), 10, 4, 1, 5L, false, null);
        engine.shutdown();
        engine.shutdown();
    }

    /**
     * Lucene hands back a copy. A PGE status write updates that copy;
     * the worker still says STARTED. The next metadata persist of the
     * worker puts STARTED back over PGE EXEC.
     */
    public void testUpdateMetadataClobbersUnsyncedPgeStatus() throws Exception {
        File idx = luceneIdxDir();
        LuceneWorkflowInstanceRepository repo =
                new LuceneWorkflowInstanceRepository(idx.getAbsolutePath(), 20);
        ThreadPoolWorkflowEngine engine = new ThreadPoolWorkflowEngine(
                repo, 10, 4, 1, 5L, false, null);
        try {
            WorkflowInstance workerInst = putStartedWorker(engine, repo);

            WorkflowInstance loaded = repo.getWorkflowInstanceById(workerInst.getId());
            loaded.setStatus("PGE EXEC");
            repo.updateWorkflowInstance(loaded);
            assertEquals("PGE EXEC",
                    repo.getWorkflowInstanceById(workerInst.getId()).getStatus());

            Metadata met = new Metadata();
            met.addMetadata("PGETask_Done", "1");
            assertTrue(engine.updateMetadata(workerInst.getId(), met));

            assertEquals("STARTED",
                    repo.getWorkflowInstanceById(workerInst.getId()).getStatus());
        } finally {
            engine.shutdown();
            FileUtils.deleteQuietly(idx);
        }
    }

    /**
     * Stamp the worker before the metadata persist and PGE EXEC survives.
     */
    public void testSyncExecutingStatusSurvivesMetadataPersist() throws Exception {
        File idx = luceneIdxDir();
        LuceneWorkflowInstanceRepository repo =
                new LuceneWorkflowInstanceRepository(idx.getAbsolutePath(), 20);
        ThreadPoolWorkflowEngine engine = new ThreadPoolWorkflowEngine(
                repo, 10, 4, 1, 5L, false, null);
        try {
            WorkflowInstance workerInst = putStartedWorker(engine, repo);

            WorkflowInstance loaded = repo.getWorkflowInstanceById(workerInst.getId());
            loaded.setStatus("PGE EXEC");
            repo.updateWorkflowInstance(loaded);
            engine.syncExecutingStatus(workerInst.getId(), "PGE EXEC");

            Metadata met = new Metadata();
            met.addMetadata("PGETask_Done", "12");
            met.addMetadata("PGETask_Total", "666");
            assertTrue(engine.updateMetadata(workerInst.getId(), met));

            WorkflowInstance after = repo.getWorkflowInstanceById(workerInst.getId());
            assertEquals("PGE EXEC", after.getStatus());
            assertEquals("12", after.getSharedContext().getMetadata("PGETask_Done"));
            assertEquals("666", after.getSharedContext().getMetadata("PGETask_Total"));
        } finally {
            engine.shutdown();
            FileUtils.deleteQuietly(idx);
        }
    }

    public void testSyncExecutingStatusUnknownInstanceIsHarmless() {
        ThreadPoolWorkflowEngine engine = new ThreadPoolWorkflowEngine(
                new MemoryWorkflowInstanceRepository(20), 10, 4, 1, 5L, false, null);
        try {
            engine.syncExecutingStatus("no-such-instance", "PGE EXEC");
            engine.syncExecutingStatus(null, "PGE EXEC");
            engine.syncExecutingStatus("id", null);
        } finally {
            engine.shutdown();
        }
    }

    private static File luceneIdxDir() throws Exception {
        File idx = new File(File.createTempFile("bogus", "txt").getParentFile(),
                "w1-pge-exec-" + System.nanoTime());
        assertTrue("could not create " + idx, idx.mkdirs());
        return idx;
    }

    /**
     * A live worker whose instance says STARTED, persisted to Lucene so
     * a subsequent get returns a copy rather than the worker object.
     */
    private static WorkflowInstance putStartedWorker(
            ThreadPoolWorkflowEngine engine,
            LuceneWorkflowInstanceRepository repo) throws Exception {
        WorkflowInstance inst = new WorkflowInstance();
        ParentChildWorkflow workflow = new ParentChildWorkflow(new Graph());
        workflow.setId("urn:oodt:testWorkflow");
        workflow.setName("test.workflow");
        WorkflowTask task = new WorkflowTask();
        task.setTaskId("urn:oodt:testTask");
        task.setTaskName("test");
        task.setTaskInstanceClassName("org.apache.oodt.cas.workflow.examples.NoOpTask");
        workflow.getTasks().add(task);
        inst.setParentChildWorkflow(workflow);
        inst.setCurrentTaskId(task.getTaskId());
        inst.setStatus(WorkflowStatus.STARTED);
        inst.setSharedContext(new Metadata());
        repo.addWorkflowInstance(inst);

        IterativeWorkflowProcessorThread worker =
                new IterativeWorkflowProcessorThread(inst, repo, null);
        java.lang.reflect.Field mapField =
                ThreadPoolWorkflowEngine.class.getDeclaredField("workerMap");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap map = (ConcurrentHashMap) mapField.get(engine);
        map.put(inst.getId(), worker);
        return inst;
    }

}
