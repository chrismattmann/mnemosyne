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

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.oodt.config.Constants;

import org.junit.After;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;

/**
 * All four Zookeeper client settings read the same system property --
 * ZK_CONNECTION_TIMEOUT -- and only the defaults differed. So
 * ZK_SESSION_TIMEOUT, ZK_RETRY_INITIAL_WAIT and ZK_RETRY_MAX_RETRIES were
 * dead properties, and setting ZK_CONNECTION_TIMEOUT silently changed three
 * other things.
 *
 * Invisible until somebody tuned anything, because the four hard-coded
 * defaults are each individually correct.
 */
public class TestCuratorUtilsProperties {

    @After
    public void clearProperties() {
        System.clearProperty(Constants.Properties.ZK_CONNECTION_TIMEOUT);
        System.clearProperty(Constants.Properties.ZK_SESSION_TIMEOUT);
        System.clearProperty(Constants.Properties.ZK_RETRY_INITIAL_WAIT);
        System.clearProperty(Constants.Properties.ZK_RETRY_MAX_RETRIES);
    }

    private static CuratorFramework client() {
        return CuratorUtils.newCuratorFrameworkClient("localhost:2181",
                LoggerFactory.getLogger(TestCuratorUtilsProperties.class));
    }

    /** How many retries a policy will actually allow. */
    private static int retryBudget(RetryPolicy policy) {
        // A sleeper that does not sleep: allowRetry consults the policy and
        // may ask to wait, and the answer is all this needs.
        org.apache.curator.RetrySleeper noSleep =
            new org.apache.curator.RetrySleeper() {
                @Override
                public void sleepFor(long time, java.util.concurrent.TimeUnit unit) {
                }
            };

        int allowed = 0;
        while (allowed < 1000 && policy.allowRetry(allowed, 0L, noSleep)) {
            allowed++;
        }
        return allowed;
    }

    /**
     * The counterexample: a connection timeout of 1000 also asked for 1000
     * retries, which Curator clamps to 29.
     */
    @Test
    public void testTheConnectionTimeoutDoesNotSetTheRetryCount() {
        System.setProperty(Constants.Properties.ZK_CONNECTION_TIMEOUT, "1000");
        System.setProperty(Constants.Properties.ZK_RETRY_MAX_RETRIES, "1");

        CuratorFramework client = client();
        try {
            assertEquals("the connection timeout is being used as the retry count",
                    1, retryBudget(client.getZookeeperClient().getRetryPolicy()));
        } finally {
            client.close();
        }
    }

    /** The connection timeout is still read for its own purpose. */
    @Test
    public void testTheConnectionTimeoutIsUsedForTheConnectionTimeout() {
        System.setProperty(Constants.Properties.ZK_CONNECTION_TIMEOUT, "4321");

        CuratorFramework client = client();
        try {
            assertEquals(4321,
                    client.getZookeeperClient().getConnectionTimeoutMs());
        } finally {
            client.close();
        }
    }

    /** ZK_RETRY_MAX_RETRIES was dead; setting it did nothing. */
    @Test
    public void testTheRetryCountPropertyIsRead() {
        System.setProperty(Constants.Properties.ZK_RETRY_MAX_RETRIES, "5");

        CuratorFramework client = client();
        try {
            assertEquals("ZK_RETRY_MAX_RETRIES is not read", 5,
                    retryBudget(client.getZookeeperClient().getRetryPolicy()));
        } finally {
            client.close();
        }
    }

    /** The untuned defaults were each individually correct, and still are. */
    @Test
    public void testTheUntunedDefaultsAreUnchanged() {
        CuratorFramework client = client();
        try {
            assertEquals(15000,
                    client.getZookeeperClient().getConnectionTimeoutMs());
            assertEquals(3, retryBudget(client.getZookeeperClient().getRetryPolicy()));
        } finally {
            client.close();
        }
    }
}
