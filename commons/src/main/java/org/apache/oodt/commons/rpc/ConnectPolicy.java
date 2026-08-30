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

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * How hard a client should try to reach a service before giving up.
 *
 * <p>
 * This used to be a decision the transport made for everyone. The resource
 * manager client retried thirty times at one second intervals, in its
 * constructor, with no way to opt out -- a policy that suits a daemon waiting
 * for a peer to come up at boot and suits nothing else. A health check, a
 * command line tool and a web page all want the opposite, and one of them
 * paying thirty seconds is what made an OPSUI status view hang rather than
 * report.
 * </p>
 *
 * <p>
 * Waiting is the caller's decision, because only the caller knows whether
 * anyone is waiting on the answer. The default is therefore {@link #failFast()},
 * and a caller that genuinely wants to wait says so.
 * </p>
 */
public final class ConnectPolicy {

  private static final Logger LOG = Logger.getLogger(ConnectPolicy.class.getName());

  /** Property naming the default policy's attempts, for deployments that must tune it. */
  public static final String ATTEMPTS_PROPERTY = "org.apache.oodt.rpc.connect.attempts";

  /** Property naming the interval between attempts, in milliseconds. */
  public static final String INTERVAL_PROPERTY = "org.apache.oodt.rpc.connect.intervalMillis";

  private final int attempts;

  private final long intervalMillis;

  private ConnectPolicy(int attempts, long intervalMillis) {
    this.attempts = Math.max(1, attempts);
    this.intervalMillis = Math.max(0, intervalMillis);
  }

  /**
   * One attempt. The default, and what any caller with something waiting on
   * the answer wants.
   */
  public static ConnectPolicy failFast() {
    return new ConnectPolicy(1, 0);
  }

  /**
   * Tries repeatedly, pausing between attempts.
   *
   * <p>
   * For a process that legitimately starts alongside the service it needs and
   * can afford to wait. Not for anything serving a request.
   * </p>
   */
  public static ConnectPolicy retrying(int attempts, long intervalMillis) {
    return new ConnectPolicy(attempts, intervalMillis);
  }

  /**
   * The policy a deployment has configured, or {@link #failFast()}.
   *
   * <p>
   * Reads {@link #ATTEMPTS_PROPERTY} and {@link #INTERVAL_PROPERTY}, so a
   * deployment that really does need the old behaviour can ask for it without
   * a code change.
   * </p>
   */
  public static ConnectPolicy configured() {
    int attempts = intProperty(ATTEMPTS_PROPERTY, 1);
    long interval = longProperty(INTERVAL_PROPERTY, 1000L);
    return new ConnectPolicy(attempts, interval);
  }

  public int getAttempts() {
    return attempts;
  }

  public long getIntervalMillis() {
    return intervalMillis;
  }

  /**
   * Runs a connection attempt under this policy.
   *
   * @param what      named in the failure, so a caller is told which service
   * @param attempt   the connection to make; may be tried more than once
   * @return whatever the attempt produced
   * @throws IOException the last failure, if every attempt failed
   */
  public <T> T connect(String what, Callable<T> attempt) throws IOException {
    IOException last = null;
    for (int i = 1; i <= attempts; i++) {
      try {
        return attempt.call();
      } catch (Exception e) {
        last = e instanceof IOException ? (IOException) e
            : new IOException(e.getMessage(), e);
        if (i < attempts) {
          LOG.log(Level.WARNING, "Unable to connect to {0}, attempt [{1}/{2}]: {3}",
              new Object[] {what, i, attempts, e.getMessage()});
          if (!pause()) {
            break;
          }
        }
      }
    }
    throw last != null ? last : new IOException("Unable to connect to " + what);
  }

  /** @return false if the wait was interrupted, so the caller stops trying */
  private boolean pause() {
    if (intervalMillis <= 0) {
      return true;
    }
    try {
      Thread.sleep(intervalMillis);
      return true;
    } catch (InterruptedException e) {
      // Someone wants this thread to stop; honour that rather than keep
      // sleeping, and leave the flag set so the caller above notices too.
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static int intProperty(String name, int fallback) {
    try {
      String value = System.getProperty(name);
      return value != null ? Integer.parseInt(value.trim()) : fallback;
    } catch (NumberFormatException e) {
      LOG.log(Level.WARNING, "Ignoring unreadable {0}: {1}",
          new Object[] {name, System.getProperty(name)});
      return fallback;
    }
  }

  private static long longProperty(String name, long fallback) {
    try {
      String value = System.getProperty(name);
      return value != null ? Long.parseLong(value.trim()) : fallback;
    } catch (NumberFormatException e) {
      LOG.log(Level.WARNING, "Ignoring unreadable {0}: {1}",
          new Object[] {name, System.getProperty(name)});
      return fallback;
    }
  }

  @Override
  public String toString() {
    return attempts == 1 ? "fail fast"
        : attempts + " attempts at " + intervalMillis + "ms";
  }
}
