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
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.oodt.pcs.PcsConfigFixture;
import org.apache.oodt.pcs.input.PGEConfigurationFile;
import org.apache.oodt.pcs.input.PGEGroup;
import org.apache.oodt.pcs.input.PGEScalar;

/**
 * Properties of {@link CrawlPropertiesFile}, the file the health monitor reads
 * to learn which crawlers exist and where they live.
 *
 * <p>Each property writes a real configuration file into a fresh temporary
 * directory, hands the path to the production class, and asserts that what the
 * operator wrote is what the health monitor sees. The temporary directory is
 * removed in a {@code finally} block.
 */
class CrawlPropertiesFilePropertyTest {

  /** A crawler name, as an operator would write it into the config file. */
  private static final Generator<String> NAME =
      text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");

  /**
   * A hostname. Restricted to letters, digits and dots because
   * {@link CrawlPropertiesFile#getCrawlHost()} runs the value through
   * environment-variable replacement, which treats {@code [} specially.
   */
  private static final Generator<String> HOST =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  /**
   * A crawler configuration holding the given name-to-port pairs and the given
   * crawl host.
   */
  private static PGEConfigurationFile crawlProperties(
      Map<String, String> crawlers, String host) {
    PGEConfigurationFile conf = new PGEConfigurationFile();

    PGEGroup crawlerInfo = new PGEGroup(CrawlerPropertiesMetKeys.CRAWLER_INFO_GROUP);
    for (Map.Entry<String, String> crawler : crawlers.entrySet()) {
      crawlerInfo.addScalar(new PGEScalar(crawler.getKey(), crawler.getValue()));
    }
    conf.getPgeSpecificGroups().put(crawlerInfo.getName(), crawlerInfo);

    PGEGroup props = new PGEGroup(CrawlerPropertiesMetKeys.CRAWLER_PROPERTIES_GROUP);
    props.addScalar(new PGEScalar(CrawlerPropertiesMetKeys.CRAWLER_HOST_NAME, host));
    conf.getPgeSpecificGroups().put(props.getName(), props);

    return conf;
  }

  /** Distinct crawler names paired with ports, in the order drawn. */
  private static Map<String, String> pairUp(List<String> names, List<Integer> ports) {
    Set<String> distinct = new LinkedHashSet<>(names);
    Map<String, String> crawlers = new LinkedHashMap<>();
    int i = 0;
    for (String name : distinct) {
      crawlers.put(name, String.valueOf(i < ports.size() ? ports.get(i) : 9000 + i));
      i++;
    }
    return crawlers;
  }

  /**
   * Every crawler written into the file comes back out, with its port intact
   * and nothing invented. The health monitor probes exactly the crawlers this
   * list names, so a crawler lost here is a crawler nobody is watching.
   */
  @HegelTest(testCases = 25)
  void everyConfiguredCrawlerIsReported(TestCase tc) throws Exception {
    List<String> names = tc.draw(lists(NAME).minSize(0).maxSize(6), "names");
    List<Integer> ports =
        tc.draw(lists(integers().min(1).max(65535)).minSize(0).maxSize(6), "ports");
    String host = tc.draw(HOST, "host");

    Map<String, String> expected = pairUp(names, ports);

    File dir = PcsConfigFixture.freshDir();
    try {
      File file = PcsConfigFixture.write(crawlProperties(expected, host), dir, "crawler-config.xml");
      CrawlPropertiesFile crawlProps = new CrawlPropertiesFile(file.getAbsolutePath());

      List crawlers = crawlProps.getCrawlers();
      assertEquals(expected.size(), crawlers.size(), "the file gained or lost a crawler");

      Map<String, String> actual = new LinkedHashMap<>();
      for (Object o : crawlers) {
        CrawlInfo info = (CrawlInfo) o;
        actual.put(info.getCrawlerName(), info.getCrawlerPort());
      }
      assertEquals(expected, actual, "a crawler's name or port changed");
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }

  /** The crawl host the operator configured is the host the monitor probes. */
  @HegelTest(testCases = 25)
  void theCrawlHostSurvivesTheFile(TestCase tc) throws Exception {
    String host = tc.draw(HOST, "host");

    File dir = PcsConfigFixture.freshDir();
    try {
      File file = PcsConfigFixture.write(
          crawlProperties(new LinkedHashMap<String, String>(), host), dir, "crawler-config.xml");
      CrawlPropertiesFile crawlProps = new CrawlPropertiesFile(file.getAbsolutePath());

      assertEquals(host, crawlProps.getCrawlHost(), "the crawl host changed");
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }

  /**
   * A crawler name containing characters that are significant to XML survives
   * the file. Operators name crawlers after the things they crawl, and those
   * names are written into an XML attribute; if the writer or the reader
   * mangles one, the health monitor reports on a crawler that does not exist.
   */
  @HegelTest(testCases = 25)
  void significantCharactersInACrawlerNameSurvive(TestCase tc) throws Exception {
    String prefix = tc.draw(NAME, "prefix");
    String marker = tc.draw(
        dev.hegel.Generators.sampledFrom(List.of("<", ">", "&", "\"", "'", "%", "é", "中")),
        "marker");
    String suffix = tc.draw(NAME, "suffix");
    String name = prefix + marker + suffix;

    Map<String, String> crawlers = new LinkedHashMap<>();
    crawlers.put(name, "9000");

    File dir = PcsConfigFixture.freshDir();
    try {
      File file =
          PcsConfigFixture.write(crawlProperties(crawlers, "localhost"), dir, "crawler-config.xml");
      CrawlPropertiesFile crawlProps = new CrawlPropertiesFile(file.getAbsolutePath());

      List read = crawlProps.getCrawlers();
      assertEquals(1, read.size(), "the crawler went missing");
      assertEquals(name, ((CrawlInfo) read.get(0)).getCrawlerName(),
          "the crawler name was mangled by the round trip");
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }

  /**
   * A configuration file that declares no crawlers reports no crawlers.
   *
   * <p>{@link org.apache.oodt.pcs.listing.ListingConf} — the other PCS class
   * that reads a group out of one of these files — treats a missing group as
   * "nothing configured" and returns an empty list. The health monitor calls
   * {@code getCrawlers()} unconditionally and iterates the result, so the same
   * contract has to hold here: an operator who has not yet listed any crawlers
   * should get an empty report, not a crash.
   */
  @HegelTest(testCases = 20)
  void aFileWithNoCrawlerSectionReportsNoCrawlers(TestCase tc) throws Exception {
    String host = tc.draw(HOST, "host");

    PGEConfigurationFile conf = new PGEConfigurationFile();
    PGEGroup props = new PGEGroup(CrawlerPropertiesMetKeys.CRAWLER_PROPERTIES_GROUP);
    props.addScalar(new PGEScalar(CrawlerPropertiesMetKeys.CRAWLER_HOST_NAME, host));
    conf.getPgeSpecificGroups().put(props.getName(), props);

    File dir = PcsConfigFixture.freshDir();
    try {
      File file = PcsConfigFixture.write(conf, dir, "crawler-config.xml");
      CrawlPropertiesFile crawlProps = new CrawlPropertiesFile(file.getAbsolutePath());

      List crawlers = crawlProps.getCrawlers();
      assertNotNull(crawlers, "no crawler list at all");
      assertTrue(crawlers.isEmpty(), "crawlers appeared out of a file that declares none");
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }
}
