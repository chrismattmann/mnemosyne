/**
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

package org.apache.oodt.cas.crawl.daemon;

//OODT imports
import org.apache.oodt.cas.crawl.ProductCrawler;
import org.apache.oodt.cas.metadata.Metadata;

//JDK imports
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogManager;

//JUnit imports
import junit.framework.TestCase;

/**
 * The crawl daemon answering over Avro, for real.
 *
 * A transport port that only compiles is not a port. This starts the daemon,
 * connects a controller to it over a socket, and asks it the six questions the
 * XML-RPC controller always asked.
 *
 * @author mattmann
 */
public class TestAvroRpcCrawlDaemon extends TestCase {

    private AvroRpcCrawlDaemon daemon;

    private Thread daemonThread;

    private int port;

    public TestAvroRpcCrawlDaemon() {
        LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // An ephemeral port rather than a fixed one. Test classes in this
        // codebase have shared hard-coded ports before, and the ones that ran
        // later could not bind.
        ServerSocket probe = new ServerSocket(0);
        this.port = probe.getLocalPort();
        probe.close();

        this.daemon = new AvroRpcCrawlDaemon(1, new CountingCrawler(), port);
        this.daemonThread = new Thread(new Runnable() {
            public void run() {
                daemon.startCrawling();
            }
        });
        this.daemonThread.setDaemon(true);
        this.daemonThread.start();

        // Wait for the port to answer rather than assume it does.
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            try {
                new Socket("localhost", port).close();
                return;
            } catch (Exception notYet) {
                Thread.sleep(50);
            }
        }
        fail("the crawl daemon never started listening on port " + port);
    }

    @Override
    protected void tearDown() throws Exception {
        if (daemon != null) {
            daemon.stop();
        }
        if (daemonThread != null) {
            daemonThread.interrupt();
            daemonThread.join(5000);
        }
        super.tearDown();
    }

    public void testControllerReachesTheDaemon() throws Exception {
        AvroRpcCrawlDaemonController controller =
            new AvroRpcCrawlDaemonController("http://localhost:" + port);
        try {
            assertTrue("the daemon should report itself running",
                controller.isRunning());
            assertEquals("the wait interval it was constructed with",
                1, controller.getWaitInterval());
        } finally {
            controller.close();
        }
    }

    /**
     * The counters move, which is what shows the answers come from the running
     * daemon rather than from a default.
     */
    public void testCountersAreReportedFromTheDaemon() throws Exception {
        AvroRpcCrawlDaemonController controller =
            new AvroRpcCrawlDaemonController("http://localhost:" + port);
        try {
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline
                   && controller.getNumCrawls() < 1) {
                Thread.sleep(100);
            }
            assertTrue("the daemon should have crawled at least once",
                controller.getNumCrawls() >= 1);
            assertTrue("and reported the time spent doing it",
                controller.getMilisCrawling() >= 0);
        } finally {
            controller.close();
        }
    }

    /**
     * Stopping is the one call that changes anything, so it is the one worth
     * following end to end.
     */
    public void testStopIsObeyed() throws Exception {
        AvroRpcCrawlDaemonController controller =
            new AvroRpcCrawlDaemonController("http://localhost:" + port);
        try {
            assertTrue(controller.isRunning());
            controller.stop();
            assertFalse("the daemon should have been told to stop",
                controller.isRunning());
        } finally {
            controller.close();
        }
    }

    /**
     * The average is a division by the number of crawls, and the XML-RPC
     * daemon performed it before there had been any, answering NaN.
     */
    public void testAverageBeforeAnyCrawlIsNotNaN() {
        AvroRpcCrawlDaemon fresh = new AvroRpcCrawlDaemon(1, null, 0);
        assertEquals(0.0, fresh.getAverageCrawlTime(), 0.0001);
    }

    /** A crawler that does nothing but let the passes be counted. */
    private static class CountingCrawler extends ProductCrawler {

        private final AtomicInteger crawls = new AtomicInteger();

        @Override
        public void crawl() {
            crawls.incrementAndGet();
        }

        @Override
        protected boolean passesPreconditions(File product) {
            return true;
        }

        @Override
        protected Metadata getMetadataForProduct(File product) {
            return new Metadata();
        }

        @Override
        protected File renameProduct(File product, Metadata productMetadata) {
            return product;
        }
    }
}
