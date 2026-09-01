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

package org.apache.oodt.cas.crawl.workflow;

import junit.framework.TestCase;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.exceptions.WorkflowTaskInstanceException;

/**
 * What the crawl task refuses to do, which is most of what matters here: a
 * task that cannot crawl has to say so rather than report that it did.
 */
public class TestCrawlerTaskInstance extends TestCase {

  public void testNoPathIsAFailureNotAQuietSuccess() {
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("CrawlerBeanRepo", "/tmp/does-not-matter.xml");

    try {
      new CrawlerTaskInstance().run(new Metadata(), config);
      fail("a crawl with nothing to crawl reported success");
    } catch (WorkflowTaskInstanceException expected) {
      assertTrue(expected.getMessage(),
          expected.getMessage().contains("ProductPath"));
    }
  }

  public void testMissingConfigurationIsAFailure() {
    try {
      new CrawlerTaskInstance().run(new Metadata(),
          new WorkflowTaskConfiguration());
      fail("a crawl with no bean repository reported success");
    } catch (WorkflowTaskInstanceException expected) {
      assertTrue(expected.getMessage(),
          expected.getMessage().contains("CrawlerBeanRepo"));
    }
  }

  /** The run says what to crawl; the configuration is only the default. */
  public void testTheRunsPathWins() {
    Metadata metadata = new Metadata();
    metadata.addMetadata(CrawlerTaskInstance.PRODUCT_PATH, "/from-the-run");

    try {
      new CrawlerTaskInstance().run(metadata, fullConfig());
      fail("expected the missing bean file to stop it");
    } catch (WorkflowTaskInstanceException expected) {
      // It got as far as trying to crawl the path the run named, which is
      // what this checks; the absent bean file is what stopped it.
      assertTrue(expected.getMessage(),
          expected.getMessage().contains("/from-the-run"));
    }
  }

  /**
   * The bean files carry placeholders -- an exclusion regex among them --
   * that resolve from system properties, which whatever launched the crawl
   * used to set on its command line.
   */
  public void testSystemPropertiesArePassedThrough() {
    WorkflowTaskConfiguration config = fullConfig();
    config.addConfigProperty(CrawlerTaskInstance.SYS_PROP_PREFIX
        + "TEST_EXCLUDE", "target|.git");

    try {
      new CrawlerTaskInstance().run(new Metadata(), config);
    } catch (WorkflowTaskInstanceException ignored) {
      // The bean file does not exist; the property is set before that point.
    }

    assertEquals("target|.git", System.getProperty("TEST_EXCLUDE"));
    System.clearProperty("TEST_EXCLUDE");
  }

  /**
   * A run can say what the task definition could not know. Which directories
   * to leave out belongs to the repository being crawled, not to the task
   * that crawls every repository.
   */
  public void testTheRunCanSupplySystemPropertiesToo() {
    Metadata metadata = new Metadata();
    metadata.addMetadata(CrawlerTaskInstance.SYS_PROP_PREFIX + "TEST_RUN_EXCLUDE",
        "target|.git");

    try {
      new CrawlerTaskInstance().run(metadata, fullConfig());
    } catch (WorkflowTaskInstanceException ignored) {
      // The bean file does not exist; the property is set before that point.
    }

    assertEquals("target|.git", System.getProperty("TEST_RUN_EXCLUDE"));
    System.clearProperty("TEST_RUN_EXCLUDE");
  }

  private WorkflowTaskConfiguration fullConfig() {
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("CrawlerBeanRepo", "/tmp/no-such-beans.xml");
    config.addConfigProperty("ProductPath", "/configured");
    config.addConfigProperty("MetExtractor", "x");
    config.addConfigProperty("MetExtractorConfig", "y");
    config.addConfigProperty("FileManagerUrl", "http://localhost:9000");
    config.addConfigProperty("ClientTransferer", "z");
    return config;
  }
}
