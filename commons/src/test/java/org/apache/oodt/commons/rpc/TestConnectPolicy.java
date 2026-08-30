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

package org.apache.oodt.commons.rpc;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * How hard to try is the caller's decision. It used to be the transport's, and
 * the resource manager client chose thirty attempts at one second intervals for
 * everybody -- so a health report that could not reach it took thirty seconds
 * to say so.
 */
public class TestConnectPolicy {

  @After
  public void clearProperties() {
    System.clearProperty(ConnectPolicy.ATTEMPTS_PROPERTY);
    System.clearProperty(ConnectPolicy.INTERVAL_PROPERTY);
  }

  @Test
  public void failFastTriesOnceAndReturnsAtOnce() {
    AtomicInteger tries = new AtomicInteger();
    long started = System.currentTimeMillis();

    try {
      ConnectPolicy.failFast().connect("a service", failing(tries));
      fail("expected the failure to be reported");
    } catch (IOException expected) {
      assertEquals("one attempt means one attempt", 1, tries.get());
      assertTrue("and it should not have waited",
          System.currentTimeMillis() - started < 500);
    }
  }

  @Test
  public void retryingTriesTheNumberAsked() {
    AtomicInteger tries = new AtomicInteger();

    try {
      ConnectPolicy.retrying(3, 1).connect("a service", failing(tries));
      fail("expected the failure to be reported");
    } catch (IOException expected) {
      assertEquals(3, tries.get());
    }
  }

  @Test
  public void asuccessfulAttemptIsNotRepeated() throws Exception {
    AtomicInteger tries = new AtomicInteger();

    String result = ConnectPolicy.retrying(5, 1).connect("a service",
        new Callable<String>() {
          public String call() {
            tries.incrementAndGet();
            return "connected";
          }
        });

    assertEquals("connected", result);
    assertEquals(1, tries.get());
  }

  /** It gives up after the last failure and reports why, not a generic error. */
  @Test
  public void thelastFailureIsWhatIsReported() {
    try {
      ConnectPolicy.retrying(2, 1).connect("the Resource Manager",
          new Callable<Void>() {
            public Void call() throws IOException {
              throw new IOException("connection refused");
            }
          });
      fail("expected the failure to be reported");
    } catch (IOException e) {
      assertEquals("connection refused", e.getMessage());
    }
  }

  /** The default is fail fast, so nothing inherits a wait it did not ask for. */
  @Test
  public void theconfiguredDefaultIsFailFast() {
    assertEquals(1, ConnectPolicy.configured().getAttempts());
  }

  /** A deployment that really does need to wait can say so without a code change. */
  @Test
  public void adeploymentCanConfigureRetrying() {
    System.setProperty(ConnectPolicy.ATTEMPTS_PROPERTY, "4");
    System.setProperty(ConnectPolicy.INTERVAL_PROPERTY, "7");

    ConnectPolicy policy = ConnectPolicy.configured();

    assertEquals(4, policy.getAttempts());
    assertEquals(7, policy.getIntervalMillis());
  }

  @Test
  public void anunreadablePropertyFallsBackRatherThanFailing() {
    System.setProperty(ConnectPolicy.ATTEMPTS_PROPERTY, "not a number");

    assertEquals(1, ConnectPolicy.configured().getAttempts());
  }

  private Callable<Void> failing(final AtomicInteger tries) {
    return new Callable<Void>() {
      public Void call() throws IOException {
        tries.incrementAndGet();
        throw new IOException("nothing listening");
      }
    };
  }
}
