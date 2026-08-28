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
        org.apache.oodt.cas.workflow.structs.WorkflowInstancePage page =
                AvroTypeFactory.getWorkflowInstancePage(wmgr.getFirstPage());

        assertNotNull(page);
        assertTrue("the page size did not survive the RPC: " + page.getPageSize(),
                page.getPageSize() > 0);
    }

}
