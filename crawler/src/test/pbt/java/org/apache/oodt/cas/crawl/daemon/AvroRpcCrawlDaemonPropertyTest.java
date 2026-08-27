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

package org.apache.oodt.cas.crawl.daemon;

import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.oodt.cas.crawl.ProductCrawler;
import org.apache.oodt.cas.metadata.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Properties for {@link AvroRpcCrawlDaemon} reached through
 * {@link AvroRpcCrawlDaemonController} over a real Avro RPC connection.
 *
 * <p>The daemon's remote surface is six read-only status calls and a stop. It
 * is what an operator has to decide whether a crawler is alive, whether it is
 * making progress and how long a pass is taking — so what matters is that the
 * numbers it reports are consistent with each other and keep being served
 * while the daemon is in the middle of a crawl.
 *
 * <p>The crawler is a stub that does no work and touches no file manager: the
 * daemon's own bookkeeping is what is under test, not what a crawl finds.
 *
 * <p>The daemon runs on a port the operating system chose. Its crawl loop
 * blocks the thread that calls {@code startCrawling}, so it is started on a
 * daemon thread and asked to stop in the teardown; the wait interval is kept
 * short so that the loop notices and closes the server rather than leaving a
 * bound port behind for the rest of the run.
 */
class AvroRpcCrawlDaemonPropertyTest {

  /** Seconds the daemon sleeps between crawls. */
  private static final int WAIT_INTERVAL = 1;

  /** A crawler that records that it ran and does nothing else. */
  private static final class CountingCrawler extends ProductCrawler {
    private final AtomicInteger crawls = new AtomicInteger();

    @Override
    public void crawl() {
      crawls.incrementAndGet();
    }

    @Override
    protected boolean passesPreconditions(File product) {
      return false;
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

  private CountingCrawler crawler;
  private AvroRpcCrawlDaemon daemon;
  private Thread daemonThread;
  private AvroRpcCrawlDaemonController controller;
  private int port;

  // ---------------------------------------------------------------- fixtures

  @BeforeEach
  void startDaemon() throws Exception {
    crawler = new CountingCrawler();
    port = ephemeralPort();
    daemon = new AvroRpcCrawlDaemon(WAIT_INTERVAL, crawler, port);

    daemonThread = new Thread(daemon::startCrawling, "crawl-daemon-pbt");
    daemonThread.setDaemon(true);
    daemonThread.start();

    awaitListening(port);
    controller = new AvroRpcCrawlDaemonController("http://localhost:" + port);
  }

  @AfterEach
  void stopDaemon() throws Exception {
    try {
      if (controller != null) {
        controller.close();
      }
    } finally {
      if (daemon != null) {
        daemon.stop();
      }
      if (daemonThread != null) {
        daemonThread.interrupt();
        daemonThread.join(Duration.ofSeconds(10).toMillis());
      }
      controller = null;
      daemon = null;
      daemonThread = null;
    }
  }

  /**
   * Asks the operating system for a port nobody is using and hands it back.
   *
   * <p>A hard-coded port is how a suite ends up failing on a machine that
   * happens to run something else; the daemon's own default, 9999, is exactly
   * such a number.
   */
  private static int ephemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  /**
   * Waits for the daemon's server to accept connections.
   *
   * <p>The controller's constructor connects once and throws if it cannot, so
   * pointing it at a port that is not listening yet would fail the fixture
   * rather than the property.
   */
  private static void awaitListening(int daemonPort) throws Exception {
    long deadline = System.currentTimeMillis() + 15_000L;
    while (System.currentTimeMillis() < deadline) {
      try (Socket probe = new Socket("localhost", daemonPort)) {
        return;
      } catch (IOException notYet) {
        Thread.sleep(50L);
      }
    }
    throw new IllegalStateException(
        "the crawl daemon never started listening on " + daemonPort);
  }

  // -------------------------------------------------------------- properties

  /**
   * The wait interval a daemon was configured with must be the wait interval
   * it reports, however many times it is asked.
   *
   * <p>An operator reads this to know how stale a listing can be. It is also
   * the one piece of the daemon's state that never changes, so a reading that
   * drifts is a reading that came from somewhere else.
   */
  @HegelTest(testCases = 12)
  void theWaitIntervalReportedIsTheOneConfigured(TestCase tc) throws Exception {
    int reads = tc.draw(integers().min(1).max(5), "reads");
    for (int i = 0; i < reads; i++) {
      assertEquals(WAIT_INTERVAL, controller.getWaitInterval(),
          "the daemon reports a wait interval it was not given");
    }
  }

  /**
   * A running daemon's crawl count and time spent crawling must never go
   * backwards, and the count must not run ahead of the crawls that have
   * actually happened.
   *
   * <p>These are the only progress indicators there are. A count that jumps
   * backwards makes any rate computed from two readings meaningless.
   */
  @HegelTest(testCases = 12)
  void progressCountersNeverGoBackwards(TestCase tc) throws Exception {
    int reads = tc.draw(integers().min(2).max(5), "reads");

    int lastCrawls = -1;
    int lastMillis = -1;
    for (int i = 0; i < reads; i++) {
      int crawls = controller.getNumCrawls();
      int millis = controller.getMilisCrawling();
      assertTrue(crawls >= lastCrawls,
          "the crawl count went backwards: " + lastCrawls + " then " + crawls);
      assertTrue(millis >= lastMillis,
          "the time spent crawling went backwards: " + lastMillis + " then " + millis);
      assertTrue(crawls <= crawler.crawls.get(),
          "the daemon reports more crawls than the crawler was asked to make");
      lastCrawls = crawls;
      lastMillis = millis;
    }
  }

  /**
   * The average crawl time must be consistent with the totals it is derived
   * from: never negative, and never longer than the whole time spent crawling.
   *
   * <p>Stated as a bound rather than an equality because the daemon is still
   * crawling while the property reads it, so the three numbers are three
   * separate observations of a moving state. The bound holds for every
   * interleaving; an equality would only hold for a stopped daemon.
   */
  @HegelTest(testCases = 12)
  void theAverageCrawlTimeIsBoundedByTheTotal(TestCase tc) throws Exception {
    int reads = tc.draw(integers().min(1).max(4), "reads");

    for (int i = 0; i < reads; i++) {
      double average = controller.getAverageCrawlTime();
      int millis = controller.getMilisCrawling();
      assertTrue(average >= 0.0, "the average crawl time is negative: " + average);
      assertFalse(Double.isNaN(average), "the average crawl time is not a number");
      assertTrue(average <= millis + 1.0,
          "the average crawl time " + average
              + " is longer than the whole time spent crawling " + millis);
    }
  }

  /**
   * A daemon must report itself running until it is asked to stop, and must
   * report itself stopped from the moment it is.
   *
   * <p>This is how a shutdown is confirmed. A daemon that still says it is
   * running after acknowledging a stop leaves an operator waiting on
   * something that has already finished.
   */
  @HegelTest(testCases = 8)
  void stoppingOverRpcIsVisibleOverRpc(TestCase tc) throws Exception {
    int readsBefore = tc.draw(integers().min(1).max(3), "readsBefore");
    int readsAfter = tc.draw(integers().min(1).max(3), "readsAfter");

    assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
      /* this property stops what it talks to, so it brings its own daemon
         rather than leaving the shared one stopped for the cases after it */
      CountingCrawler ownCrawler = new CountingCrawler();
      int ownPort = ephemeralPort();
      AvroRpcCrawlDaemon ownDaemon = new AvroRpcCrawlDaemon(WAIT_INTERVAL, ownCrawler, ownPort);
      Thread ownThread = new Thread(ownDaemon::startCrawling, "crawl-daemon-pbt-stop");
      ownThread.setDaemon(true);
      ownThread.start();
      awaitListening(ownPort);

      try (AvroRpcCrawlDaemonController own =
          new AvroRpcCrawlDaemonController("http://localhost:" + ownPort)) {
        for (int i = 0; i < readsBefore; i++) {
          assertTrue(own.isRunning(), "a crawling daemon reports itself not running");
        }

        own.stop();

        for (int i = 0; i < readsAfter; i++) {
          assertFalse(own.isRunning(),
              "a daemon that acknowledged a stop still reports itself running");
        }
      } finally {
        ownDaemon.stop();
        ownThread.interrupt();
        ownThread.join(Duration.ofSeconds(10).toMillis());
      }
    });
  }
}
