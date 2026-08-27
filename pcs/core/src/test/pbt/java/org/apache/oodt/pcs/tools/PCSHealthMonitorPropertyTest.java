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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.oodt.pcs.PcsConfigFixture;
import org.apache.oodt.pcs.health.CrawlerHealth;
import org.apache.oodt.pcs.health.CrawlerPropertiesMetKeys;
import org.apache.oodt.pcs.health.CrawlerStatus;
import org.apache.oodt.pcs.health.JobHealthStatus;
import org.apache.oodt.pcs.health.PCSHealthMonitorMetKeys;
import org.apache.oodt.pcs.health.PCSHealthMonitorReport;
import org.apache.oodt.pcs.health.WorkflowStatesMetKeys;
import org.apache.oodt.pcs.input.PGEConfigurationFile;
import org.apache.oodt.pcs.input.PGEGroup;
import org.apache.oodt.pcs.input.PGEScalar;
import org.apache.oodt.pcs.input.PGEVector;
import org.apache.oodt.pcs.util.StubResourceManagerClient;
import org.apache.oodt.pcs.util.StubWorkflowManagerFactory;

/**
 * Roll-up properties of {@link PCSHealthMonitor#getReport()}.
 *
 * <p>The health monitor builds its own clients from URLs, so the only way to
 * put a controlled PCS behind it is the same one production uses: the system
 * properties that name the client implementations. These properties register an
 * in-memory workflow manager and resource manager, point the file manager at a
 * closed port, and describe the crawlers in a real configuration file — then
 * assert that the report accounts for exactly what was configured.
 *
 * <p>The crawler ports are deliberately unbound loopback ports: the monitor is
 * expected to probe them, find nothing, and report DOWN. Nothing here binds a
 * port.
 */
class PCSHealthMonitorPropertyTest {

  private static final String RESMGR_CLIENT_PROPERTY = "resmgr.manager.client";
  /**
   * The workflow RPC factory reads its client class from a classpath
   * properties file, not from a system property, so the way in is to point it
   * at a properties file of the test's own.
   */
  private static final String WORKFLOW_PROPERTIES_FILE_PROPERTY =
      "org.apache.oodt.cas.workflow.properties";

  private static final String STUB_WORKFLOW_PROPERTIES = "/pcs-pbt-workflow.properties";

  /** A URL nothing is listening on, used for the file manager. */
  private static final String DEAD_URL = "http://127.0.0.1:1";

  private static final Generator<String> CRAWLER_NAME =
      text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");

  private static final Generator<String> STATE =
      sampledFrom(List.of("STARTED", "FINISHED", "PAUSED", "QUEUED", "ERROR"));

  private static void restore(String key, String original) {
    if (original == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, original);
    }
  }

  private static PGEConfigurationFile crawlProperties(Map<String, String> crawlers) {
    PGEConfigurationFile conf = new PGEConfigurationFile();

    PGEGroup crawlerInfo = new PGEGroup(CrawlerPropertiesMetKeys.CRAWLER_INFO_GROUP);
    for (Map.Entry<String, String> crawler : crawlers.entrySet()) {
      crawlerInfo.addScalar(new PGEScalar(crawler.getKey(), crawler.getValue()));
    }
    conf.getPgeSpecificGroups().put(crawlerInfo.getName(), crawlerInfo);

    PGEGroup props = new PGEGroup(CrawlerPropertiesMetKeys.CRAWLER_PROPERTIES_GROUP);
    props.addScalar(new PGEScalar(CrawlerPropertiesMetKeys.CRAWLER_HOST_NAME, "127.0.0.1"));
    conf.getPgeSpecificGroups().put(props.getName(), props);

    return conf;
  }

  private static PGEConfigurationFile statesConfig(List<String> states) {
    PGEConfigurationFile conf = new PGEConfigurationFile();
    PGEGroup group = new PGEGroup(WorkflowStatesMetKeys.WORKFLOW_STATES_GROUP);
    group.addVector(new PGEVector(
        WorkflowStatesMetKeys.WORKFLOW_STATES_VECTOR, new ArrayList<Object>(states)));
    conf.getPgeSpecificGroups().put(group.getName(), group);
    return conf;
  }

  /**
   * A report accounts for exactly what was configured: one job-health line per
   * declared workflow state carrying the workflow manager's own count, one
   * crawler-status line and one crawler-health line per declared crawler, and
   * no line invented for anything that was not configured.
   *
   * <p>The counts are the point. An operator reads this report to decide
   * whether the system is healthy; a state silently missing from the roll-up,
   * or a count that does not match the workflow manager's, is a wrong answer to
   * that question. Counts are also asserted non-negative: the underlying
   * workflow manager wrapper returns -1 when it cannot answer, and the monitor
   * is responsible for turning that into a zero before an operator sees it.
   */
  @HegelTest(testCases = 6)
  void theReportAccountsForExactlyWhatWasConfigured(TestCase tc) throws Exception {
    List<String> crawlerNames = tc.draw(lists(CRAWLER_NAME).minSize(0).maxSize(1), "crawlerNames");
    List<Integer> crawlerPorts =
        tc.draw(lists(integers().min(41000).max(41999)).minSize(1).maxSize(2), "crawlerPorts");
    List<String> states = tc.draw(lists(STATE).minSize(1).maxSize(4), "states");
    List<Integer> counts =
        tc.draw(lists(integers().min(0).max(200)).minSize(1).maxSize(4), "counts");

    Set<String> distinctCrawlers = new LinkedHashSet<>(crawlerNames);
    Map<String, String> crawlers = new LinkedHashMap<>();
    int i = 0;
    for (String name : distinctCrawlers) {
      crawlers.put(name, String.valueOf(crawlerPorts.get(i % crawlerPorts.size())));
      i++;
    }

    Set<String> distinctStates = new LinkedHashSet<>(states);
    Map<String, Integer> countsByStatus = new LinkedHashMap<>();
    int j = 0;
    for (String state : distinctStates) {
      countsByStatus.put(state, counts.get(j % counts.size()));
      j++;
    }

    String originalResMgr = System.getProperty(RESMGR_CLIENT_PROPERTY);
    String originalWorkflow = System.getProperty(WORKFLOW_PROPERTIES_FILE_PROPERTY);
    File dir = PcsConfigFixture.freshDir();
    try {
      System.setProperty(RESMGR_CLIENT_PROPERTY, StubResourceManagerClient.class.getName());
      System.setProperty(WORKFLOW_PROPERTIES_FILE_PROPERTY, STUB_WORKFLOW_PROPERTIES);
      StubWorkflowManagerFactory.countsByStatus = countsByStatus;
      StubWorkflowManagerFactory.failing = false;

      File crawlFile = PcsConfigFixture.write(crawlProperties(crawlers), dir, "crawler-config.xml");
      File statesFile = PcsConfigFixture.write(
          statesConfig(new ArrayList<>(distinctStates)), dir, "workflow-states.xml");

      PCSHealthMonitor monitor = new PCSHealthMonitor(
          DEAD_URL, DEAD_URL, DEAD_URL,
          crawlFile.getAbsolutePath(), statesFile.getAbsolutePath());

      PCSHealthMonitorReport report = monitor.getReport();

      assertNotNull(report.getGenerationDate(), "the report has no generation date");
      assertNotNull(report.getFmStatus(), "no file manager status");
      assertNotNull(report.getWmStatus(), "no workflow manager status");
      assertNotNull(report.getRmStatus(), "no resource manager status");

      // Job health: one line per declared state, carrying that state's count.
      assertEquals(distinctStates.size(), report.getJobHealthStatus().size(),
          "the job roll-up gained or lost a workflow state");
      long total = 0;
      for (Object o : report.getJobHealthStatus()) {
        JobHealthStatus status = (JobHealthStatus) o;
        assertTrue(countsByStatus.containsKey(status.getStatus()),
            "the report names a workflow state [" + status.getStatus() + "] nobody configured");
        assertTrue(status.getNumPipelines() >= 0,
            "a negative pipeline count reached the report for [" + status.getStatus() + "]");
        assertEquals(countsByStatus.get(status.getStatus()).intValue(), status.getNumPipelines(),
            "the count for [" + status.getStatus() + "] does not match the workflow manager's");
        total += status.getNumPipelines();
      }

      long expectedTotal = 0;
      for (Integer count : countsByStatus.values()) {
        expectedTotal += count;
      }
      assertEquals(expectedTotal, total, "the roll-up total is not the sum of its parts");

      // Crawlers: one status and one health line each, no more, no fewer.
      assertEquals(crawlers.size(), report.getCrawlerStatus().size(),
          "the crawler status roll-up gained or lost a crawler");
      for (Object o : report.getCrawlerStatus()) {
        CrawlerStatus status = (CrawlerStatus) o;
        assertTrue(crawlers.containsKey(status.getInfo().getCrawlerName()),
            "the report names a crawler nobody configured");
        assertEquals(PCSHealthMonitorMetKeys.STATUS_DOWN, status.getStatus(),
            "a crawler on a closed port was reported as up");
      }

      assertEquals(crawlers.size(), report.getCrawlerHealthStatus().size(),
          "the ingest roll-up gained or lost a crawler");
      for (Object o : report.getCrawlerHealthStatus()) {
        CrawlerHealth health = (CrawlerHealth) o;
        assertTrue(crawlers.containsKey(health.getCrawlerName()),
            "the ingest roll-up names a crawler nobody configured");
        assertEquals(PCSHealthMonitorMetKeys.CRAWLER_DOWN_INT, health.getNumCrawls(),
            "an unreachable crawler reported a crawl count");
      }

      // No resource nodes were declared, so no batch stubs may be reported.
      assertTrue(report.getBatchStubStatus().isEmpty(),
          "batch stubs appeared for a resource manager with no nodes");
      assertTrue(report.getLatestProductsIngested().isEmpty(),
          "products were reported from an unreachable file manager");
    } finally {
      StubWorkflowManagerFactory.reset();
      restore(RESMGR_CLIENT_PROPERTY, originalResMgr);
      restore(WORKFLOW_PROPERTIES_FILE_PROPERTY, originalWorkflow);
      PcsConfigFixture.delete(dir);
    }
  }

  /**
   * A workflow manager that cannot answer must not put a negative pipeline
   * count in front of an operator. The wrapper returns -1 for "cannot tell";
   * the monitor already normalises that to zero, and this pins it down for
   * every configured state at once.
   */
  @HegelTest(testCases = 6)
  void anUnreachableWorkflowManagerNeverProducesNegativeCounts(TestCase tc) throws Exception {
    List<String> states = tc.draw(lists(STATE).minSize(1).maxSize(4), "states");
    Set<String> distinctStates = new LinkedHashSet<>(states);

    String originalResMgr = System.getProperty(RESMGR_CLIENT_PROPERTY);
    String originalWorkflow = System.getProperty(WORKFLOW_PROPERTIES_FILE_PROPERTY);
    File dir = PcsConfigFixture.freshDir();
    try {
      System.setProperty(RESMGR_CLIENT_PROPERTY, StubResourceManagerClient.class.getName());
      System.setProperty(WORKFLOW_PROPERTIES_FILE_PROPERTY, STUB_WORKFLOW_PROPERTIES);
      StubWorkflowManagerFactory.countsByStatus = new LinkedHashMap<>();
      StubWorkflowManagerFactory.failCounts = true;

      File crawlFile = PcsConfigFixture.write(
          crawlProperties(new LinkedHashMap<String, String>()), dir, "crawler-config.xml");
      File statesFile = PcsConfigFixture.write(
          statesConfig(new ArrayList<>(distinctStates)), dir, "workflow-states.xml");

      PCSHealthMonitor monitor = new PCSHealthMonitor(
          DEAD_URL, DEAD_URL, DEAD_URL,
          crawlFile.getAbsolutePath(), statesFile.getAbsolutePath());

      for (Object o : monitor.getReport().getJobHealthStatus()) {
        JobHealthStatus status = (JobHealthStatus) o;
        assertTrue(status.getNumPipelines() >= 0,
            "the report shows " + status.getNumPipelines() + " pipelines "
                + status.getStatus());
      }
    } finally {
      StubWorkflowManagerFactory.reset();
      restore(RESMGR_CLIENT_PROPERTY, originalResMgr);
      restore(WORKFLOW_PROPERTIES_FILE_PROPERTY, originalWorkflow);
      PcsConfigFixture.delete(dir);
    }
  }
}
