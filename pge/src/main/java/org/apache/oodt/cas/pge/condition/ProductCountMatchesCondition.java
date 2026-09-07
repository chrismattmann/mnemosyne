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
package org.apache.oodt.cas.pge.condition;

import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.system.FileManagerClient;
import org.apache.oodt.cas.filemgr.util.RpcCommunicationFactory;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionInstance;

import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds a task until one product type has caught up with another.
 *
 * <p>
 * The fan-in half of a fan-out. A stage that splits work into pieces
 * produces one product per piece; the stage that gathers the results must
 * not start until every piece has an answer. Counting is the only way to
 * know that, and it is exact: when the answers equal the pieces, they are
 * all in.
 * </p>
 *
 * <p>
 * {@link ProductCountSettledCondition} answers a different and weaker
 * question -- whether production has paused -- which is a guess. A pause
 * between two pieces is indistinguishable from the end of the run, and if
 * the pieces are unequal in size the largest are last, so the longest
 * pause comes just before the end rather than at it. A pipeline gated that
 * way gathered seven results of ten, reported success, and left the rest of
 * the work undone in a way nothing downstream could see.
 * </p>
 *
 * <p>
 * Configuration:
 * </p>
 *
 * <ul>
 * <li><code>FileManagerUrl</code> -- where to count.</li>
 * <li><code>ProductTypeName</code> -- the answers, counted for the left
 * side.</li>
 * <li><code>MatchesProductTypeName</code> -- the pieces, counted for the
 * right. The condition passes when the first count reaches this one.</li>
 * <li><code>MinCount</code> -- a floor, so an empty catalog on both sides
 * does not read as "nothing to wait for". One by default.</li>
 * </ul>
 *
 * <p>
 * Unlike its neighbour this does not sleep, poll or time out. It answers
 * from the catalog as it stands and returns; the engine is what decides
 * when to ask again. A condition that blocks holds a worker thread for as
 * long as it waits, and reports itself as executing while it does nothing.
 * </p>
 */
public class ProductCountMatchesCondition implements WorkflowConditionInstance {

  private static final Logger LOG = Logger
      .getLogger(ProductCountMatchesCondition.class.getName());

  static final String FILE_MANAGER_URL = "FileManagerUrl";
  static final String PRODUCT_TYPE_NAME = "ProductTypeName";
  static final String MATCHES_PRODUCT_TYPE_NAME = "MatchesProductTypeName";
  static final String MIN_COUNT = "MinCount";

  private static final int DEFAULT_MIN_COUNT = 1;

  public ProductCountMatchesCondition() {
    super();
  }

  public boolean evaluate(Metadata metadata,
      WorkflowConditionConfiguration config) {
    String urlStr = config.getProperty(FILE_MANAGER_URL);
    String typeName = config.getProperty(PRODUCT_TYPE_NAME);
    String matchesTypeName = config.getProperty(MATCHES_PRODUCT_TYPE_NAME);

    if (urlStr == null || typeName == null || matchesTypeName == null) {
      LOG.log(Level.SEVERE, "Cannot evaluate without [" + FILE_MANAGER_URL
          + "], [" + PRODUCT_TYPE_NAME + "] and ["
          + MATCHES_PRODUCT_TYPE_NAME + "]");
      return false;
    }

    int minCount = intProperty(config, MIN_COUNT, DEFAULT_MIN_COUNT);

    try {
      int have = countProducts(urlStr, typeName);
      int expected = countProducts(urlStr, matchesTypeName);

      // A count that could not be read is not a count of zero. Saying "not
      // yet" leaves the task to be asked again; saying "yes" on a failed
      // query would start the gathering stage against whatever happened to
      // be catalogued at the time.
      if (have < 0 || expected < 0) {
        LOG.log(Level.WARNING, "Could not count [" + typeName + "] or ["
            + matchesTypeName + "] at [" + urlStr + "]; holding");
        return false;
      }

      if (expected < minCount) {
        // Nothing has been produced yet. Without this an empty catalog
        // satisfies the condition trivially, since zero does equal zero.
        LOG.log(Level.FINE, "Only [" + expected + "] of [" + matchesTypeName
            + "]; waiting for at least [" + minCount + "]");
        return false;
      }

      boolean ready = have >= expected;
      LOG.log(ready ? Level.INFO : Level.FINE, "[" + have + "] of ["
          + typeName + "] against [" + expected + "] of ["
          + matchesTypeName + "]: " + (ready ? "ready" : "holding"));
      return ready;
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Unable to compare [" + typeName + "] with ["
          + matchesTypeName + "] at [" + urlStr + "]: " + e.getMessage());
      return false;
    }
  }

  /**
   * How many of a type are catalogued, or -1 if that cannot be read.
   *
   * <p>Overridable, so the comparison can be exercised without a file
   * manager to count in, which is how its neighbour is tested.</p>
   */
  protected int countProducts(String urlStr, String typeName) {
    FileManagerClient client = null;
    try {
      client = RpcCommunicationFactory.createClient(new URL(urlStr));
      ProductType type = client.getProductTypeByName(typeName);
      if (type == null) {
        // A type nobody has declared has produced nothing, which is a
        // different thing from a query that failed.
        return 0;
      }
      return client.getNumProducts(type);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Unable to count [" + typeName + "]: "
          + e.getMessage());
      return -1;
    } finally {
      closeQuietly(client);
    }
  }

  private int intProperty(WorkflowConditionConfiguration config, String key,
      int fallback) {
    String value = config.getProperty(key);
    if (value == null || value.trim().length() == 0) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      LOG.log(Level.WARNING, "[" + key + "] is not a number: [" + value
          + "]; using [" + fallback + "]");
      return fallback;
    }
  }

  private void closeQuietly(FileManagerClient client) {
    if (client != null) {
      try {
        client.close();
      } catch (Exception ignore) {
        // Nothing useful to do about a client that will not close.
      }
    }
  }
}
