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

package org.apache.oodt.config.distributed.utils;

import static dev.hegel.Generators.integers;
import static org.apache.oodt.config.Constants.Properties.ZK_CONNECTION_TIMEOUT;
import static org.apache.oodt.config.Constants.Properties.ZK_RETRY_INITIAL_WAIT;
import static org.apache.oodt.config.Constants.Properties.ZK_RETRY_MAX_RETRIES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.retry.ExponentialBackoffRetry;

/**
 * Properties of the Zookeeper client that {@link CuratorUtils} builds.
 *
 * <p>Building the client does not connect to anything — only starting it does —
 * so the settings it was built with can be read straight back off it.
 *
 * <p>These four settings are the only control an operator has over how a
 * component behaves when the ensemble is slow or unreachable. Each one is
 * documented as its own system property in {@code Constants.Properties}, so
 * each has to be the one that ends up on the client.
 */
class CuratorUtilsPropertyTest {

  /** A connect string is never resolved until the client is started. */
  private static final String CONNECT_STRING = "zookeeper.invalid:2181";

  private static void restore(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }

  /**
   * The client is built with the timeouts and retry budget the operator
   * configured. Each of these has its own documented property, and a component
   * that ignores them either gives up on a slow ensemble too early or hammers
   * an unreachable one far longer than was asked for.
   */
  @HegelTest
  void theClientUsesEachConfiguredSettingForItsOwnPurpose(TestCase tc) {
    int connectionTimeoutMs = tc.draw(integers().min(1_000).max(60_000), "connectionTimeoutMs");
    int retryInitialWaitMs = tc.draw(integers().min(100).max(5_000), "retryInitialWaitMs");
    int maxRetries = tc.draw(integers().min(1).max(10), "maxRetries");

    String previousConnection = System.getProperty(ZK_CONNECTION_TIMEOUT);
    String previousWait = System.getProperty(ZK_RETRY_INITIAL_WAIT);
    String previousRetries = System.getProperty(ZK_RETRY_MAX_RETRIES);
    try {
      System.setProperty(ZK_CONNECTION_TIMEOUT, Integer.toString(connectionTimeoutMs));
      System.setProperty(ZK_RETRY_INITIAL_WAIT, Integer.toString(retryInitialWaitMs));
      System.setProperty(ZK_RETRY_MAX_RETRIES, Integer.toString(maxRetries));

      CuratorFramework client = CuratorUtils.newCuratorFrameworkClient(CONNECT_STRING);

      assertEquals(
          connectionTimeoutMs,
          client.getZookeeperClient().getConnectionTimeoutMs(),
          "the connection timeout is not the configured one");

      ExponentialBackoffRetry retryPolicy =
          assertInstanceOf(
              ExponentialBackoffRetry.class, client.getZookeeperClient().getRetryPolicy());
      assertEquals(maxRetries, retryPolicy.getN(), "the retry budget is not the configured one");
      assertEquals(
          retryInitialWaitMs,
          retryPolicy.getBaseSleepTimeMs(),
          "the initial retry wait is not the configured one");
    } finally {
      restore(ZK_CONNECTION_TIMEOUT, previousConnection);
      restore(ZK_RETRY_INITIAL_WAIT, previousWait);
      restore(ZK_RETRY_MAX_RETRIES, previousRetries);
    }
  }

  /**
   * With nothing configured, the client is built with the defaults the class
   * names in its own source. A component started with no zookeeper tuning at
   * all has to get a usable client.
   */
  @HegelTest
  void anUntunedClientGetsTheDocumentedDefaults(TestCase tc) {
    tc.note("no zookeeper tuning properties set");

    String previousConnection = System.getProperty(ZK_CONNECTION_TIMEOUT);
    String previousWait = System.getProperty(ZK_RETRY_INITIAL_WAIT);
    String previousRetries = System.getProperty(ZK_RETRY_MAX_RETRIES);
    try {
      System.clearProperty(ZK_CONNECTION_TIMEOUT);
      System.clearProperty(ZK_RETRY_INITIAL_WAIT);
      System.clearProperty(ZK_RETRY_MAX_RETRIES);

      CuratorFramework client = CuratorUtils.newCuratorFrameworkClient(CONNECT_STRING);

      assertEquals(15_000, client.getZookeeperClient().getConnectionTimeoutMs());
      ExponentialBackoffRetry retryPolicy =
          assertInstanceOf(
              ExponentialBackoffRetry.class, client.getZookeeperClient().getRetryPolicy());
      assertEquals(3, retryPolicy.getN(), "an untuned client does not retry three times");
      assertEquals(1_000, retryPolicy.getBaseSleepTimeMs(), "an untuned client waits the wrong time");
    } finally {
      restore(ZK_CONNECTION_TIMEOUT, previousConnection);
      restore(ZK_RETRY_INITIAL_WAIT, previousWait);
      restore(ZK_RETRY_MAX_RETRIES, previousRetries);
    }
  }
}
