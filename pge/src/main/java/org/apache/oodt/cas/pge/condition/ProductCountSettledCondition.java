/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.oodt.cas.pge.condition;

import org.apache.oodt.cas.filemgr.structs.ProductType;
import java.util.List;
import java.util.Date;
import org.apache.oodt.commons.util.DateConvert;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.system.FileManagerClient;
import org.apache.oodt.cas.filemgr.util.RpcCommunicationFactory;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;

import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Passes once a product type has stopped growing.
 *
 * <p>
 * The gate a map/reduce pipeline actually needs. A reduce step must not start
 * while its mappers are still producing, and there is no single moment at
 * which a fan-out is "done": jobs are submitted in a batch, each ingests when
 * it finishes, and the last one lands whenever it lands. What can be observed
 * is the catalog -- while mappers are working the count of what they produce
 * climbs, and when they have finished it stops.
 * </p>
 *
 * <p>
 * So this waits for two things in order: at least {@code MinCount} products
 * of {@code ProductTypeName} to exist, and then for the newest of them to
 * have been ingested at least {@code QuietSeconds} ago. The first stops the
 * reduce running against an empty catalog; the second stops it running
 * against a half-finished one. Both matter -- an empty catalog is also
 * perfectly quiet.
 * </p>
 *
 * <p>
 * Both facts are read from the catalog on each call, and nothing is
 * remembered between them. That is not an optimisation, it is a
 * requirement: ConditionTaskInstance builds a new condition object for every
 * evaluation, so anything counted in a field resets before it can be used
 * again. A condition that tries to observe a trend across evaluations never
 * finishes, and sits with a start time and no end.
 * </p>
 *
 * <p>
 * Configure it with:
 * </p>
 *
 * <ul>
 * <li>{@code FileManagerUrl} -- where to count, required</li>
 * <li>{@code ProductTypeName} -- what to count, required</li>
 * <li>{@code MinCount} -- how many must exist before settling counts,
 * default 1</li>
 * <li>{@code StableEvaluations} -- consecutive unchanged counts needed,
 * default 2</li>
 * </ul>
 *
 * <p>
 * How long a quiet period should be is the pipeline's to choose. It has to
 * exceed the gap between one unit of work finishing and the next ingesting,
 * or the reduce starts in a lull.
 * </p>
 *
 * <p>
 * <b>This waits rather than reporting "not yet".</b> A condition gets one
 * evaluation: ConditionTaskInstance throws when it returns false, which
 * fails the attempt, and the instance is left in PreConditionEval -- a state
 * TaskProcessor does not offer for execution again. There is no "ask me
 * later" in the contract, so a gate that needs to wait has to do the waiting
 * itself.
 * </p>
 *
 * <p>
 * This is deliberately not a timer. A condition that counts down passes
 * whether or not the work is finished, so a reduce gated on one runs against
 * whatever happens to have been ingested by then -- including nothing at all.
 * That is not a gate, it is a delay.
 * </p>
 */
public class ProductCountSettledCondition implements WorkflowConditionInstance {

  private static final Logger LOG = Logger
      .getLogger(ProductCountSettledCondition.class.getName());

  static final String FILE_MANAGER_URL = "FileManagerUrl";
  static final String PRODUCT_TYPE_NAME = "ProductTypeName";
  static final String MIN_COUNT = "MinCount";
  static final String QUIET_SECONDS = "QuietSeconds";
  static final String POLL_SECONDS = "PollSeconds";
  static final String MAX_WAIT_SECONDS = "MaxWaitSeconds";

  private static final int DEFAULT_MIN_COUNT = 1;
  private static final int DEFAULT_QUIET_SECONDS = 30;
  private static final int DEFAULT_POLL_SECONDS = 10;
  private static final int DEFAULT_MAX_WAIT_SECONDS = 3600;

  public ProductCountSettledCondition() {
    super();
  }

  public boolean evaluate(Metadata metadata,
      WorkflowConditionConfiguration config) {
    String urlStr = config.getProperty(FILE_MANAGER_URL);
    String typeName = config.getProperty(PRODUCT_TYPE_NAME);
    if (urlStr == null || typeName == null) {
      LOG.log(Level.SEVERE, "Cannot evaluate without both ["
          + FILE_MANAGER_URL + "] and [" + PRODUCT_TYPE_NAME + "]");
      return false;
    }

    int minCount = intProperty(config, MIN_COUNT, DEFAULT_MIN_COUNT);
    long quietSeconds = intProperty(config, QUIET_SECONDS,
        DEFAULT_QUIET_SECONDS);
    long pollSeconds = intProperty(config, POLL_SECONDS, DEFAULT_POLL_SECONDS);
    long maxWaitSeconds = intProperty(config, MAX_WAIT_SECONDS,
        DEFAULT_MAX_WAIT_SECONDS);

    long gaveUpAt = now() + (maxWaitSeconds * 1000L);
    while (true) {
      if (settled(urlStr, typeName, minCount, quietSeconds)) {
        return true;
      }
      if (now() >= gaveUpAt) {
        LOG.log(Level.WARNING, "[" + typeName + "] had not gone quiet for "
            + quietSeconds + "s within " + maxWaitSeconds
            + "s; the producer has not finished");
        return false;
      }
      if (!pause(pollSeconds)) {
        // Interrupted: something wants this thread to stop, and reporting
        // the producer finished on the way out would be a lie.
        return false;
      }
    }
  }

  /** Whether the producer looks finished right now. */
  private boolean settled(String urlStr, String typeName, int minCount,
      long quietSeconds) {
    int count = countProducts(urlStr, typeName);
    if (count < 0 || count < minCount) {
      return false;
    }
    long quietFor = secondsSinceNewest(urlStr, typeName);
    if (quietFor < 0) {
      return false;
    }
    LOG.log(Level.FINE, "[" + typeName + "] at " + count + ", newest "
        + quietFor + "s ago, needs " + quietSeconds);
    return quietFor >= quietSeconds;
  }

  /** Overridable so a test does not have to sleep. */
  protected boolean pause(long seconds) {
    try {
      Thread.sleep(seconds * 1000L);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Overridable so a test can move time without waiting for it. */
  protected long now() {
    return System.currentTimeMillis();
  }

  /**
   * Seconds since the newest product of the type was received.
   *
   * <p>
   * Overridable for the same reason as the count: the decision is about
   * elapsed time, which is worth testing without a catalog to put products
   * in.
   * </p>
   *
   * @return the age in seconds, or -1 if it could not be determined
   */
  /**
   * When a product arrived.
   *
   * <p>
   * The file manager records this as CAS.ProductReceivedTime in the
   * product's metadata, which is where every catalog puts it and where the
   * rest of the platform reads it from. Product.getProductReceivedTime() is
   * a field on the struct that only the Solr catalog ever fills, so asking
   * it first meant this condition could not tell the time under a Lucene or
   * a database catalog: it logged that the newest product carried no receive
   * time, returned -1 on every evaluation, and the gate it guards never
   * opened. The struct is still consulted, second, for the catalog that does
   * set it.
   * </p>
   */
  protected String receivedTimeOf(FileManagerClient client, Product product) {
    try {
      Metadata met = client.getMetadata(product);
      if (met != null) {
        String received = met.getMetadata("CAS.ProductReceivedTime");
        if (received != null && received.trim().length() > 0) {
          return received;
        }
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Unable to read metadata for ["
          + product.getProductName() + "]: " + e.getMessage());
    }
    return product.getProductReceivedTime();
  }

  protected long secondsSinceNewest(String urlStr, String typeName) {
    FileManagerClient client = null;
    try {
      client = RpcCommunicationFactory.createClient(new URL(urlStr));
      ProductType type = client.getProductTypeByName(typeName);
      if (type == null) {
        return -1;
      }
      List<Product> newest = client.getTopNProducts(1, type);
      if (newest == null || newest.isEmpty()) {
        return -1;
      }
      String received = receivedTimeOf(client, newest.get(0));
      if (received == null || received.trim().length() == 0) {
        // Ingested without a receive time. Nothing to measure against, and
        // guessing "long ago" would open the gate on no evidence.
        LOG.log(Level.WARNING, "Newest [" + typeName + "] carries no receive"
            + " time; cannot tell whether the producer has finished");
        return -1;
      }
      Date when = DateConvert.isoParse(received.trim());
      long seconds = (System.currentTimeMillis() - when.getTime()) / 1000L;
      // A clock skew between this process and whatever ingested is not a
      // reason to declare the producer finished.
      return seconds < 0 ? 0 : seconds;
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Unable to read the newest [" + typeName
          + "] at [" + urlStr + "]: " + e.getMessage());
      return -1;
    } finally {
      closeQuietly(client);
    }
  }

  private void closeQuietly(FileManagerClient client) {
    if (client != null) {
      try {
        client.close();
      } catch (Exception ignored) {
        // nothing useful to do about a client that will not close
      }
    }
  }

  /**
   * How many products of the type the catalog holds.
   *
   * <p>
   * Overridable so the settling logic can be exercised against a sequence of
   * counts without standing up a File Manager: the decision this class makes
   * is about how counts change over time, and that is worth testing on its
   * own.
   * </p>
   *
   * @return the number of products of the type, or -1 if it could not be read
   */
  protected int countProducts(String urlStr, String typeName) {
    FileManagerClient client = null;
    try {
      client = RpcCommunicationFactory.createClient(new URL(urlStr));
      ProductType type = client.getProductTypeByName(typeName);
      if (type == null) {
        // The type is declared by the producer's policy; before anything has
        // been ingested it may not be there yet. Nothing to count.
        return 0;
      }
      return client.getNumProducts(type);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Unable to count [" + typeName + "] at ["
          + urlStr + "]: " + e.getMessage());
      return -1;
    } finally {
      if (client != null) {
        try {
          client.close();
        } catch (Exception ignored) {
          // nothing useful to do about a client that will not close
        }
      }
    }
  }

  private int intProperty(WorkflowConditionConfiguration config, String name,
      int fallback) {
    String value = config.getProperty(name);
    if (value == null || value.trim().equals("")) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      LOG.log(Level.WARNING, "[" + name + "] is not a number: [" + value
          + "], using " + fallback);
      return fallback;
    }
  }
}
