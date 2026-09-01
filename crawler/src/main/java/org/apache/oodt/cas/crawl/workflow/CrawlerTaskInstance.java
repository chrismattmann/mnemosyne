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

import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.oodt.cas.crawl.MetExtractorProductCrawler;
import org.apache.oodt.cas.crawl.ProductCrawler;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.WorkflowTaskInstanceException;
import org.springframework.context.support.FileSystemXmlApplicationContext;

/**
 * A crawl, as a workflow task.
 *
 * <p>
 * Crawling has been something done to a deployment rather than something it
 * runs: a command line tool, or code inside a web application, either way
 * outside the engine. Nothing scheduled it, nothing recorded that it was
 * happening, and nothing could say afterwards how long it took or whether it
 * worked -- the run only became visible once the first workflow it fed was
 * submitted.
 * </p>
 *
 * <p>
 * As a task it is an instance like any other: it has a status, a start and an
 * end, it appears wherever instances appear, and a pipeline can put it ahead
 * of the work that depends on it rather than trusting a caller to sequence
 * the two.
 * </p>
 *
 * <p>
 * The path to crawl comes from the workflow's metadata under
 * <code>ProductPath</code>, so one task definition serves every repository,
 * and falls back to the configured value when a run does not supply one.
 * </p>
 */
public class CrawlerTaskInstance implements WorkflowTaskInstance {

  private static final Logger LOG = Logger
      .getLogger(CrawlerTaskInstance.class.getName());

  /** Metadata key naming what to crawl. */
  public static final String PRODUCT_PATH = "ProductPath";

  /**
   * Config properties beginning with this are set as system properties before
   * the crawler's Spring context is built. The bean files use placeholders --
   * an exclusion regex, for one -- and those resolve from system properties,
   * which the tool that used to launch the crawl set on its command line.
   */
  public static final String SYS_PROP_PREFIX = "SysProp.";

  public void run(Metadata metadata, WorkflowTaskConfiguration config)
      throws WorkflowTaskInstanceException {
    String beanRepo = required(config, "CrawlerBeanRepo");
    String productPath = metadata != null
        && metadata.getMetadata(PRODUCT_PATH) != null
        ? metadata.getMetadata(PRODUCT_PATH)
        : config.getProperty("ProductPath");
    if (productPath == null || productPath.trim().length() == 0) {
      throw new WorkflowTaskInstanceException("No " + PRODUCT_PATH
          + " in the workflow metadata and none configured: nothing to crawl");
    }

    applySystemProperties(config, metadata);

    FileSystemXmlApplicationContext appContext = null;
    ProductCrawler crawler = null;
    try {
      appContext = new FileSystemXmlApplicationContext("file:" + beanRepo);
      MetExtractorProductCrawler metCrawler = new MetExtractorProductCrawler();
      metCrawler.setApplicationContext(appContext);
      metCrawler.setId(config.getProperty("CrawlerId") != null
          ? config.getProperty("CrawlerId") : "MetExtractorProductCrawler");
      metCrawler.setMetExtractor(required(config, "MetExtractor"));
      metCrawler.setMetExtractorConfig(required(config, "MetExtractorConfig"));
      String preCondIds = config.getProperty("PreCondIds");
      if (preCondIds != null && preCondIds.trim().length() > 0) {
        metCrawler.setPreCondIds(Arrays.asList(preCondIds.split("\\s*,\\s*")));
      }
      crawler = metCrawler;
      crawler.setFilemgrUrl(required(config, "FileManagerUrl"));
      crawler.setClientTransferer(required(config, "ClientTransferer"));
      crawler.setProductPath(productPath);

      LOG.log(Level.INFO, "Crawling [" + productPath + "]");
      crawler.crawl();
      LOG.log(Level.INFO, "Finished crawling [" + productPath + "]");
    } catch (WorkflowTaskInstanceException e) {
      throw e;
    } catch (Exception e) {
      // Loudly. A crawl that fails quietly leaves an empty catalog for
      // whatever runs next, which then succeeds at doing nothing.
      throw new WorkflowTaskInstanceException("Unable to crawl [" + productPath
          + "]: " + e.getMessage(), e);
    } finally {
      if (crawler != null) {
        try {
          crawler.shutdown();
        } catch (IOException e) {
          LOG.log(Level.WARNING, "Unable to shut the crawler down cleanly: "
              + e.getMessage());
        }
      }
      if (appContext != null) {
        appContext.close();
      }
    }
  }

  /**
   * Configuration first, then the run, so a run can say something the task
   * definition could not know. An exclusion regex is the example: which
   * directories to leave out belongs to the repository being crawled, not to
   * the task that crawls every repository.
   */
  private void applySystemProperties(WorkflowTaskConfiguration config,
      Metadata metadata) {
    Properties properties = config == null ? null : config.getProperties();
    if (properties != null) {
      for (Enumeration<?> names = properties.propertyNames();
          names.hasMoreElements();) {
        String name = (String) names.nextElement();
        if (name.startsWith(SYS_PROP_PREFIX)) {
          System.setProperty(name.substring(SYS_PROP_PREFIX.length()),
              properties.getProperty(name));
        }
      }
    }
    if (metadata != null) {
      for (String key : metadata.getAllKeys()) {
        if (key.startsWith(SYS_PROP_PREFIX)) {
          System.setProperty(key.substring(SYS_PROP_PREFIX.length()),
              metadata.getMetadata(key));
        }
      }
    }
  }

  private String required(WorkflowTaskConfiguration config, String key)
      throws WorkflowTaskInstanceException {
    String value = config == null ? null : config.getProperty(key);
    if (value == null || value.trim().length() == 0) {
      throw new WorkflowTaskInstanceException("A crawl task needs [" + key
          + "] configured");
    }
    return value;
  }
}
