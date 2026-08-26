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

package org.apache.oodt.pcs.health;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import org.apache.oodt.commons.date.DateUtils;

/**
 * Properties of {@link PCSHealthMonitorReport}, the object the health monitor
 * hands to its printers and to the {@code HealthResource} web service.
 *
 * <p>Nothing here needs a running PCS: the report is assembled from plain
 * status objects, and the two things a consumer depends on — its timestamp
 * and its survival over the wire — are decidable on the spot.
 */
class PCSHealthMonitorReportPropertyTest {

  private static final Generator<String> NAME =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  /**
   * Milliseconds within a range PCS timestamps can plausibly occupy: 1990
   * through roughly 2050. Fuzzing the whole long range would only be probing
   * SimpleDateFormat's behaviour outside the calendar the tools ever show.
   */
  private static final long EARLIEST_MILLIS = 631_152_000_000L;

  private static final long LATEST_MILLIS = 2_524_608_000_000L;

  /**
   * The report's timestamp survives being formatted: parsing the string back
   * yields the instant the report was generated. The health page shows this
   * string as the age of the report, so a drifting value would misreport how
   * stale the data is.
   */
  @HegelTest
  void theFormattedTimestampParsesBackToTheGenerationDate(TestCase tc) throws Exception {
    long millis = tc.draw(longs().min(EARLIEST_MILLIS).max(LATEST_MILLIS), "millis");

    PCSHealthMonitorReport report = new PCSHealthMonitorReport();
    report.setGenerationDate(new Date(millis));

    String formatted = report.getCreateDateIsoFormat();
    assertNotNull(formatted);

    Calendar parsed = DateUtils.toCalendar(formatted, DateUtils.FormatType.LOCAL_FORMAT);

    assertEquals(millis, parsed.getTimeInMillis(),
        "timestamp [" + formatted + "] did not parse back to the generation date");
  }

  /**
   * A report survives the trip to a remote caller. {@code HealthResource}
   * serialises this object, and every status class in it declares
   * {@link java.io.Serializable} for that reason; a field that failed to make
   * the trip would show up as a blank panel rather than an error.
   */
  @HegelTest
  void aReportSurvivesSerialisation(TestCase tc) throws Exception {
    long millis = tc.draw(longs().min(EARLIEST_MILLIS).max(LATEST_MILLIS), "millis");
    String fmName = tc.draw(NAME, "fmName");
    String fmStatus = tc.draw(sampledFrom("UP", "DOWN"), "fmStatus");
    List<String> crawlerNames = tc.draw(lists(NAME).minSize(0).maxSize(6), "crawlerNames");
    List<Integer> crawlCounts =
        tc.draw(lists(integers().min(0).max(100_000)).minSize(0).maxSize(6), "crawlCounts");
    List<Integer> pipelineCounts =
        tc.draw(lists(integers().min(0).max(100_000)).minSize(0).maxSize(6), "pipelineCounts");

    List<CrawlerHealth> crawlerHealth = new ArrayList<>();
    List<CrawlerStatus> crawlerStatus = new ArrayList<>();
    List<JobHealthStatus> jobHealth = new ArrayList<>();
    for (int i = 0; i < crawlerNames.size(); i++) {
      String name = crawlerNames.get(i);
      int crawls = i < crawlCounts.size() ? crawlCounts.get(i) : 0;
      crawlerHealth.add(new CrawlerHealth(name, crawls, crawls / 2.0));
      crawlerStatus.add(new CrawlerStatus(new CrawlInfo(name, "900" + i), "UP", "localhost"));
    }
    for (int i = 0; i < pipelineCounts.size(); i++) {
      jobHealth.add(new JobHealthStatus("STATE" + i, pipelineCounts.get(i)));
    }

    PCSHealthMonitorReport report =
        new PCSHealthMonitorReport(
            new Date(millis),
            new PCSDaemonStatus(fmName, "http://localhost:9000", fmStatus),
            new PCSDaemonStatus("Workflow Manager", "http://localhost:9001", "UP"),
            new PCSDaemonStatus("Resource Manager", "http://localhost:9002", "DOWN"),
            new ArrayList<>(),
            crawlerStatus,
            new ArrayList<>(),
            jobHealth,
            crawlerHealth);

    PCSHealthMonitorReport copy = roundTrip(report);

    assertEquals(report.getGenerationDate(), copy.getGenerationDate());
    assertEquals(fmName, copy.getFmStatus().getDaemonName());
    assertEquals(fmStatus, copy.getFmStatus().getStatus());
    assertEquals(crawlerHealth.size(), copy.getCrawlerHealthStatus().size());
    assertEquals(crawlerStatus.size(), copy.getCrawlerStatus().size());
    assertEquals(jobHealth.size(), copy.getJobHealthStatus().size());

    for (int i = 0; i < crawlerHealth.size(); i++) {
      CrawlerHealth original = crawlerHealth.get(i);
      CrawlerHealth restored = (CrawlerHealth) copy.getCrawlerHealthStatus().get(i);
      assertEquals(original.getCrawlerName(), restored.getCrawlerName());
      assertEquals(original.getNumCrawls(), restored.getNumCrawls());
      assertEquals(original.getAvgCrawlTime(), restored.getAvgCrawlTime());

      CrawlerStatus originalStatus = crawlerStatus.get(i);
      CrawlerStatus restoredStatus = (CrawlerStatus) copy.getCrawlerStatus().get(i);
      assertEquals(originalStatus.getInfo().getCrawlerName(),
          restoredStatus.getInfo().getCrawlerName());
      assertEquals(originalStatus.getInfo().getCrawlerPort(),
          restoredStatus.getInfo().getCrawlerPort());
    }

    for (int i = 0; i < jobHealth.size(); i++) {
      JobHealthStatus original = jobHealth.get(i);
      JobHealthStatus restored = (JobHealthStatus) copy.getJobHealthStatus().get(i);
      assertEquals(original.getStatus(), restored.getStatus());
      assertEquals(original.getNumPipelines(), restored.getNumPipelines());
    }
  }

  private static PCSHealthMonitorReport roundTrip(PCSHealthMonitorReport report) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(report);
    }
    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return (PCSHealthMonitorReport) in.readObject();
    }
  }
}
