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

package org.apache.oodt.cas.workflow.system;

import org.apache.commons.io.FileUtils;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.util.AvroTypeFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import junit.framework.TestCase;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TestAvroRpcWorkflowManager extends TestCase{

    private static final int WM_PORT = 65527;

    private AvroRpcWorkflowManager wmgr;

    private String luceneCatLoc;

    private static final Logger LOG = Logger
            .getLogger(TestAvroRpcWorkflowManager.class.getName());
    
    /**
     * {@link #startWorkflow()} fires an event of type "long". This event is associated with 2 instances of "LongWorkflow". Therefore, we should check if the
     * number of workflow instances are 2 when asserting.
     */
    @Test
    public void testGetWorkflowInstances() throws InterruptedException {
        Thread.sleep(5000);

        Vector workflowInsts = null;

        try {
            List list = AvroTypeFactory.getWorkflowInstances(wmgr.getWorkflowInstances());
            workflowInsts = new Vector();
            for (Object o : list) {
                workflowInsts.add(o);
            }
        } catch (Exception e) {

            e.printStackTrace();
        }

        assertNotNull(workflowInsts);

        assertEquals(2, workflowInsts.size());
    }


    /**
     * An instance the repository has no model for does not fail the listing.
     *
     * <p>
     * The queue engine builds workflows of its own while a run is going -- one
     * per condition, one per task it wraps -- and the manager's repository has
     * never seen them. Listing by status used to answer that by registering
     * the instance's own workflow, which is rejected when it carries no tasks,
     * and the failure was rethrown: one instance nobody could describe emptied
     * the entire response.
     * </p>
     *
     * <p>
     * Every caller then read that as "nothing is running". DRAT's Proteus
     * decides whether the mappers have finished exactly that way, and an empty
     * answer told it to reduce while they were still running.
     * </p>
     */
    @Test
    public void testAnInstanceWithNoModelDoesNotEmptyTheListing() throws Exception {
        // An instance whose workflow this repository cannot describe: an id
        // that was never defined, and no tasks to define it from.
        org.apache.oodt.cas.workflow.structs.WorkflowInstance orphan =
                new org.apache.oodt.cas.workflow.structs.WorkflowInstance();
        org.apache.oodt.cas.workflow.structs.Workflow unknown =
                new org.apache.oodt.cas.workflow.structs.Workflow();
        unknown.setId("pre-cond-workflow|urn:test:Gate|urn:test:Cond");
        unknown.setName("Condition Workflow-Cond");
        // No tasks: this is what addWorkflow rejects, and what the engine's
        // own condition workflows look like once read back from a repository.
        unknown.setTasks(new java.util.Vector());
        orphan.setWorkflow(unknown);
        orphan.setCurrentTaskId("urn:test:Cond");
        orphan.setSharedContext(new Metadata());
        orphan.setStatus("Success");
        // Written into the same repository the manager reads: the fixture
        // points the engine at this Lucene index.
        org.apache.oodt.cas.workflow.instrepo.LuceneWorkflowInstanceRepository repo =
                new org.apache.oodt.cas.workflow.instrepo.LuceneWorkflowInstanceRepository(
                        luceneCatLoc, 20);
        repo.addWorkflowInstance(orphan);
        repo.release();

        List<org.apache.oodt.cas.workflow.structs.WorkflowInstance> byStatus =
                AvroTypeFactory.getWorkflowInstances(
                        wmgr.getWorkflowInstancesByStatus("Success"));

        assertNotNull("the listing failed outright", byStatus);
        boolean sawTheOrphan = false;
        for (org.apache.oodt.cas.workflow.structs.WorkflowInstance wi : byStatus) {
            if (orphan.getId().equals(wi.getId())) {
                sawTheOrphan = true;
            }
        }
        assertTrue("an instance with no model was dropped from the listing",
                sawTheOrphan);
    }


    /**
     * Clearing works whatever repository the deployment configured.
     *
     * <p>
     * The point of asking the manager rather than the store: a caller does
     * not have to know where instances live. DRAT had to know, and got it
     * wrong -- its web application deleted a directory that only exists for
     * one of the three implementations, found nothing, and reported a reset
     * that had not happened.
     * </p>
     */
    /**
     * A run in progress does not make the instances unclearable.
     *
     * <p>
     * This refused while anything was executing, which read as caution and
     * behaved as a trap: the engine picks up every instance that is not done
     * when it starts, so a manager restarted after a crash reported all of
     * them as executing and nothing could clear them -- the one moment the
     * operation exists for. force is the caller saying they mean it.
     * </p>
     */
    @Test
    public void testClearingWithForceWorksEvenWithARunInProgress() throws Exception {
        assertFalse("the fixture should have something executing",
                wmgr.getExecutingWorkflowInstanceIds().isEmpty());

        assertTrue(wmgr.clearWorkflowInstances(true));

        assertEquals("instances survived a forced clear", 0,
                wmgr.getNumWorkflowInstances());
    }

    @Before
    public void setUp() throws Exception {
        startAvroRpcWorkflowManager();
        startWorkflow();
    }

    @After
    public void tearDown() throws Exception {
        wmgr.shutdown();
        if (luceneCatLoc != null) {
            // Best effort: the index is this test's alone, and a leftover
            // temp directory is not worth failing a test over.
            FileUtils.deleteQuietly(new File(luceneCatLoc));
        }
    }

    private void startWorkflow() {
        try (WorkflowManagerClient client =
                     new AvroRpcWorkflowManagerClient(new URL("http://localhost:" + WM_PORT))) {
            Metadata metadata = new Metadata();
            // Hold the task for 20 seconds at least            
            metadata.addMetadata("numSeconds", String.valueOf(30));
            client.sendEvent("long", metadata);
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    private void startAvroRpcWorkflowManager() {
        URL ulr = TestAvroRpcWorkflowManager.class.getResource("/workflow.properties");
        System.setProperty("java.util.logging.config.file", new File(
                "./src/main/resources/logging.properties").getAbsolutePath());

        try {
            FileInputStream fileInputStream = new FileInputStream(ulr.getPath());
            System.getProperties().load(
                    fileInputStream);

        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }

        // A fresh index directory per test rather than one fixed /tmp/repo
        // reused by all of them. The old fixture deleted that directory in
        // setUp, which raced the previous test's manager: its engine threads
        // were still writing to the index as the delete walked it, and
        // deleteDirectory failed with "Unable to delete directory". Only one
        // test in this class ever ran per JVM, so the race had nowhere to
        // show until a second was added. Nothing here needs the directory to
        // be shared, and an unshared one needs no deleting.
        try {
            luceneCatLoc = Files.createTempDirectory("wmgr-repo").toFile()
                    .getCanonicalPath();
            LOG.log(Level.INFO, "Lucene instance repository: [" + luceneCatLoc + "]");
        } catch (Exception e) {
            fail(e.getMessage());
        }

        System.setProperty("workflow.engine.instanceRep.factory",
                        "org.apache.oodt.cas.workflow.instrepo.LuceneWorkflowInstanceRepositoryFactory");
        System.setProperty("org.apache.oodt.cas.workflow.instanceRep.lucene.idxPath",
                        luceneCatLoc);

        try {
            System.setProperty("org.apache.oodt.cas.workflow.repo.dirs", "file://"
                    + new File("./src/main/resources/examples").getCanonicalPath());
            System.setProperty("org.apache.oodt.cas.workflow.lifecycle.filePath",
                    new File("./src/main/resources/examples/workflow-lifecycle.xml")
                            .getCanonicalPath());
        } catch (Exception e) {
            fail(e.getMessage());
        }

        try {
            wmgr = new AvroRpcWorkflowManager(WM_PORT);
        } catch (Exception e) {
            fail(e.getMessage());
        }

    }

    /**
     * The hook was an anonymous Thread registered in the constructor and
     * never removed, so it outlived the manager it was there to close: every
     * manager built in a JVM -- which is every test that starts one -- left
     * one behind holding a strong reference to it, and at exit they all ran
     * against servers shut down long before.
     *
     * The field is private, so this reads it directly; it is our own class,
     * so no module opens are involved. Before the fix there was no field to
     * read, because the hook was unreachable from anywhere -- which is the
     * defect.
     */
    @Test
    public void testShutdownDeregistersItsShutdownHook() throws Exception {
        java.lang.reflect.Field field =
                AvroRpcWorkflowManager.class.getDeclaredField("shutdownHook");
        field.setAccessible(true);

        Thread hook = (Thread) field.get(wmgr);
        assertNotNull("no shutdown hook was registered", hook);

        assertTrue(wmgr.shutdown());

        assertNull("the manager still points at a hook it has already run",
                field.get(wmgr));
        assertFalse("the shutdown hook was left registered with the runtime",
                Runtime.getRuntime().removeShutdownHook(hook));
    }

    /** shutting down twice is harmless; tearDown does it after every test. */
    @Test
    public void testShuttingDownTwiceIsHarmless() {
        assertTrue(wmgr.shutdown());
        assertFalse(wmgr.shutdown());
    }

    /**
     * A page's size is written on the way out and was never read back, so
     * every page crossing the wire arrived carrying the field's initial -1
     * however large the page actually was. Asserted here over a real RPC
     * rather than at the factory, because that is where it was observed.
     */
    @Test
    public void testAPagedResultCarriesItsPageSizeOverRpc() throws Exception {
        // Waits for the engine to have started something. setUp fires the
        // event and returns; asking straight away raced it two ways. An empty
        // repository answers with blankPage(), whose page size is 0, so the
        // assertion below failed on its own timing -- and while instances
        // were being written, an id could be listed by the paging query and
        // not yet readable, which is the null that used to come back through
        // the factory as a NullPointerException.
        org.apache.oodt.cas.workflow.structs.WorkflowInstancePage page = null;
        long deadline = System.currentTimeMillis() + 30000L;
        do {
            page = AvroTypeFactory.getWorkflowInstancePage(wmgr.getFirstPage());
            if (page != null && page.getPageWorkflows() != null
                    && !page.getPageWorkflows().isEmpty()) {
                break;
            }
            Thread.sleep(100L);
        } while (System.currentTimeMillis() < deadline);

        assertNotNull(page);
        assertNotNull("no instance was started within 30s",
                page.getPageWorkflows());
        assertFalse("no instance was started within 30s",
                page.getPageWorkflows().isEmpty());
        assertTrue("the page size did not survive the RPC: " + page.getPageSize(),
                page.getPageSize() > 0);
    }


    /**
     * Both shutdown hooks run at once, and the manager still stops.
     *
     * <p>
     * This class registers one hook and {@code WorkflowManagerStarter}
     * registers another that calls {@link AvroRpcWorkflowManager#shutdown()};
     * the JVM runs every hook concurrently. Unsynchronised, both read the
     * same non-null server before either cleared it and both closed it, one
     * of them inside Jetty's lifecycle stop while the other blocked on that
     * monitor -- a manager with a closed port and a process that would not
     * exit. Here the two paths are driven together deliberately: exactly one
     * reports having done the shutdown, and both return.
     * </p>
     */
    @Test
    public void testConcurrentShutdownHooksBothReturn() throws Exception {
        java.lang.reflect.Field field =
                AvroRpcWorkflowManager.class.getDeclaredField("shutdownHook");
        field.setAccessible(true);
        final Thread hook = (Thread) field.get(wmgr);
        assertNotNull("no shutdown hook was registered", hook);
        // Registered for the real exit; this test runs it by hand instead.
        Runtime.getRuntime().removeShutdownHook(hook);

        final java.util.concurrent.CountDownLatch go =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger closedIt =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger hookClosed =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(2);

        Thread viaShutdown = new Thread(new Runnable() {
            public void run() {
                try {
                    go.await();
                    if (wmgr.shutdown()) {
                        closedIt.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }
        });
        Thread viaHook = new Thread(new Runnable() {
            public void run() {
                try {
                    go.await();
                    java.lang.reflect.Method m = AvroRpcWorkflowManager.class
                            .getDeclaredMethod("shutdownInternal");
                    m.setAccessible(true);
                    if (Boolean.TRUE.equals(m.invoke(wmgr))) {
                        hookClosed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }
        });

        viaShutdown.start();
        viaHook.start();
        go.countDown();

        assertTrue("a shutdown hook never returned -- the manager cannot be "
                + "stopped", done.await(60, java.util.concurrent.TimeUnit.SECONDS));
        viaShutdown.join(5000L);
        viaHook.join(5000L);
        assertFalse("the shutdown thread is still running", viaShutdown.isAlive());
        assertFalse("the hook thread is still running", viaHook.isAlive());
        assertEquals("more than one caller closed the same server", 1,
                closedIt.get() + hookClosed.get());
        assertFalse("the manager is still serving after shutdown",
                wmgr.shutdown());
    }

}
