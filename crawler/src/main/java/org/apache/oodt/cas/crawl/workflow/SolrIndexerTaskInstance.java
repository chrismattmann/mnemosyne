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

import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.oodt.cas.filemgr.tools.SolrIndexer;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.WorkflowTaskInstanceException;

/**
 * Publishing the catalog to Solr, as a workflow task.
 *
 * <p>
 * The companion to {@link CrawlerTaskInstance}, and here for the same reason:
 * a step that a run depends on, which the engine knew nothing about because
 * something outside it did the work. A pipeline can now say that indexing
 * follows the crawl and precedes whatever queries the index, rather than
 * leaving a caller to run the two in order and hope.
 * </p>
 *
 * <p>
 * In the crawler module because it is the only one that depends on both the
 * workflow structures this implements and the file manager tool it drives.
 * </p>
 */
public class SolrIndexerTaskInstance implements WorkflowTaskInstance {

  private static final Logger LOG = Logger
      .getLogger(SolrIndexerTaskInstance.class.getName());

  /** Where SolrIndexer looks for its own configuration. */
  private static final String INDEXER_CONFIG_PROPERTY = "SOLR_INDEXER_CONFIG";

  public void run(Metadata metadata, WorkflowTaskConfiguration config)
      throws WorkflowTaskInstanceException {
    String solrUrl = required(config, "SolrUrl");
    String fmUrl = required(config, "FileManagerUrl");
    String indexerConfig = config.getProperty("IndexerConfig");
    if (indexerConfig != null && indexerConfig.trim().length() > 0) {
      System.setProperty(INDEXER_CONFIG_PROPERTY, indexerConfig);
    }

    boolean deleteFirst = Boolean.parseBoolean(
        config.getProperty("DeleteBeforeIndexing"));

    try {
      SolrIndexer indexer = new SolrIndexer(solrUrl, fmUrl);
      LOG.log(Level.INFO, "Indexing [" + fmUrl + "] into [" + solrUrl + "]");
      indexer.indexAll(deleteFirst);
      indexer.commit();
      indexer.optimize();
      LOG.log(Level.INFO, "Finished indexing into [" + solrUrl + "]");
    } catch (Exception e) {
      // Loudly, for the same reason the crawl fails loudly: an index that
      // quietly did not happen leaves everything downstream querying stale
      // answers and reporting success.
      throw new WorkflowTaskInstanceException("Unable to index [" + fmUrl
          + "] into [" + solrUrl + "]: " + e.getMessage(), e);
    }
  }

  private String required(WorkflowTaskConfiguration config, String key)
      throws WorkflowTaskInstanceException {
    String value = config == null ? null : config.getProperty(key);
    if (value == null || value.trim().length() == 0) {
      throw new WorkflowTaskInstanceException("An indexing task needs [" + key
          + "] configured");
    }
    return value;
  }
}
