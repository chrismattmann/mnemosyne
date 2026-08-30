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

package org.apache.oodt.pcs.tools;

import org.apache.oodt.pcs.health.PCSHealthMonitorReport;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A health monitor pointed at services that are not running is the normal case
 * -- it is the case the tool exists to report on. It used to be the case that
 * hung it: the constructor built a resource manager client, that client retried
 * for thirty seconds, and the servlet holding the lock blocked every other
 * request for the whole of it.
 */
public class TestPCSHealthMonitor {

  /** Nothing is listening on these. That is the point. */
  private static final String DEAD_FM = "http://localhost:1/";
  private static final String DEAD_WM = "http://localhost:2/";
  private static final String DEAD_RM = "http://localhost:3/";

  private static final long GENEROUS_CEILING_MILLIS = 10000L;

  private static final String DOWN = "DOWN";

  @Test
  public void constructionDoesNotContactAnyService() throws Exception {
    long started = System.currentTimeMillis();

    PCSHealthMonitor monitor = newMonitor();

    long elapsed = System.currentTimeMillis() - started;
    assertNotNull(monitor);
    assertTrue("constructing the monitor took " + elapsed
        + "ms, so it is still connecting up front", elapsed < 1000L);
  }

  /**
   * The whole report, not just the reachable parts. Before, a single
   * unreachable resource manager cost thirty seconds here.
   */
  @Test
  public void areportOnDeadServicesComesBackPromptly() throws Exception {
    PCSHealthMonitor monitor = newMonitor();

    long started = System.currentTimeMillis();
    PCSHealthMonitorReport report = monitor.getReport();
    long elapsed = System.currentTimeMillis() - started;

    assertNotNull(report);
    assertTrue("the report took " + elapsed + "ms against services that are"
        + " not there", elapsed < GENEROUS_CEILING_MILLIS);
  }

  /** Down is a reportable state, not an exception and not a blank section. */
  @Test
  public void eachunreachableSubsystemIsReportedDown() throws Exception {
    PCSHealthMonitorReport report = newMonitor().getReport();

    assertNotNull(report.getFmStatus());
    assertNotNull(report.getWmStatus());
    assertNotNull(report.getRmStatus());

    assertEquals("file manager should read as down", DOWN,
        report.getFmStatus().getStatus());
    assertEquals("workflow manager should read as down", DOWN,
        report.getWmStatus().getStatus());
    assertEquals("resource manager should read as down", DOWN,
        report.getRmStatus().getStatus());
  }

  /**
   * A down service still says where it was looked for. "unavailable" beats a
   * null that renders as an empty cell next to the word DOWN.
   */
  @Test
  public void adownServiceStillReportsAnAddress() throws Exception {
    PCSHealthMonitorReport report = newMonitor().getReport();

    assertNotNull(report.getFmStatus().getUrlStr());
    assertNotNull(report.getWmStatus().getUrlStr());
    assertNotNull(report.getRmStatus().getUrlStr());
  }

  /** The sections that do not depend on a live service still come back. */
  @Test
  public void thereportIsStillWellFormedWithEverythingDown() throws Exception {
    PCSHealthMonitorReport report = newMonitor().getReport();

    assertNotNull("the report should be dated", report.getGenerationDate());
    assertNotNull(report.getCrawlerStatus());
  }

  /** Two reports from one monitor: the second must not rebuild or re-block. */
  @Test
  public void thereportCanBeAskedForRepeatedly() throws Exception {
    PCSHealthMonitor monitor = newMonitor();
    monitor.getReport();

    long started = System.currentTimeMillis();
    PCSHealthMonitorReport second = monitor.getReport();
    long elapsed = System.currentTimeMillis() - started;

    assertNotNull(second);
    assertTrue("the second report took " + elapsed + "ms",
        elapsed < GENEROUS_CEILING_MILLIS);
  }

  /** A null address is a misconfiguration, not a crash. */
  @Test
  public void anunsetAddressIsToleratedLikeAnUnreachableOne() throws Exception {
    PCSHealthMonitor monitor = new PCSHealthMonitor(null, null, null,
        resource("/pcs-crawlers.xml"), resource("/pcs-workflow-statuses.xml"));

    PCSHealthMonitorReport report = monitor.getReport();

    assertNotNull(report);
    assertEquals(DOWN, report.getRmStatus().getStatus());
    assertEquals("unavailable", report.getRmStatus().getUrlStr());
  }

  private PCSHealthMonitor newMonitor() throws Exception {
    return new PCSHealthMonitor(DEAD_FM, DEAD_WM, DEAD_RM,
        resource("/pcs-crawlers.xml"), resource("/pcs-workflow-statuses.xml"));
  }

  private String resource(String name) {
    return getClass().getResource(name).getFile();
  }
}
