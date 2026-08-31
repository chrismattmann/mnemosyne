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
 * of {@code ProductTypeName} to exist, and then the count to hold still for
 * {@code StableEvaluations} consecutive checks. The first stops the reduce
 * running against an empty catalog; the second stops it running against a
 * half-finished one. Both matter -- a count of zero is also "not growing".
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
 * How long a check is worth is the caller's to choose, through the interval
 * the engine evaluates conditions at. Two unchanged counts a second apart
 * means something different from two a minute apart, and only the pipeline
 * knows which it needs.
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
  static final String STABLE_EVALUATIONS = "StableEvaluations";

  private static final int DEFAULT_MIN_COUNT = 1;
  private static final int DEFAULT_STABLE_EVALUATIONS = 2;

  /* The count at the previous evaluation; -1 before the first. */
  private int lastCount = -1;

  /* How many consecutive evaluations have seen it unchanged. */
  private int stableEvaluations = 0;

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
    int needed = intProperty(config, STABLE_EVALUATIONS,
        DEFAULT_STABLE_EVALUATIONS);

    int count = countProducts(urlStr, typeName);
    if (count < 0) {
      // Could not be counted. Not the same as "not finished", but a caller
      // waiting on a count it cannot read should wait rather than proceed.
      reset();
      return false;
    }

    if (count < minCount) {
      LOG.log(Level.FINE, "[" + typeName + "] at " + count + ", waiting for "
          + minCount);
      reset();
      return false;
    }

    if (count == lastCount) {
      stableEvaluations++;
    } else {
      stableEvaluations = 0;
      lastCount = count;
    }

    boolean settled = stableEvaluations >= needed;
    LOG.log(Level.FINE, "[" + typeName + "] at " + count + ", unchanged for "
        + stableEvaluations + " of " + needed + (settled ? ": settled" : ""));
    return settled;
  }

  private void reset() {
    this.stableEvaluations = 0;
    this.lastCount = -1;
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
