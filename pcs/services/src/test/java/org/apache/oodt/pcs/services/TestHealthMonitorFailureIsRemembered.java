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

package org.apache.oodt.pcs.services;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Building the health monitor constructs a client for each subsystem, and the
 * resource manager client retries for about thirty seconds before giving up.
 * That happens inside a lock every caller of this class shares, and the failure
 * used to be forgotten -- so the next request paid the same thirty seconds, and
 * a browser polling the status view kept the endpoint permanently busy. It did
 * not fail, it hung.
 */
public class TestHealthMonitorFailureIsRemembered {

  @Before
  public void clearRememberedFailure() throws Exception {
    set("mon", null);
    set("nextAttemptAt", 0L);
  }

  @Test
  public void afailureIsRememberedRatherThanRepeated() throws Exception {
    // Pretend a build has just failed.
    set("nextAttemptAt", System.currentTimeMillis() + 60000L);

    long started = System.currentTimeMillis();
    try {
      invokeGetMonitor();
      fail("expected the remembered failure to be reported");
    } catch (Exception e) {
      long elapsed = System.currentTimeMillis() - started;
      assertTrue("a remembered failure must be reported at once, took "
          + elapsed + "ms", elapsed < 1000);
    }
  }

  @Test
  public void themessageSaysWhatToLookAt() throws Exception {
    set("nextAttemptAt", System.currentTimeMillis() + 60000L);

    try {
      invokeGetMonitor();
      fail("expected the remembered failure to be reported");
    } catch (Exception e) {
      String message = rootCauseMessage(e);
      assertNotNull(message);
      assertTrue("the message should name the subsystems to check: " + message,
          message.contains("resource manager") || message.contains("file manager"));
    }
  }

  /** The cooldown has to expire, or starting the subsystem would need a restart. */
  @Test
  public void anexpiredCooldownIsTriedAgain() throws Exception {
    set("nextAttemptAt", System.currentTimeMillis() - 1L);

    try {
      invokeGetMonitor();
    } catch (Exception expected) {
      // Building it will fail in a unit test -- there is no configuration --
      // but it must have been attempted rather than short-circuited.
    }

    long next = (Long) get("nextAttemptAt");
    assertTrue("a fresh attempt should set a new cooldown", next > System.currentTimeMillis());
  }

  private Object invokeGetMonitor() throws Exception {
    Method m = HealthResource.class.getDeclaredMethod("getMonitor");
    m.setAccessible(true);
    try {
      return m.invoke(null);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  private String rootCauseMessage(Throwable t) {
    while (t.getCause() != null && t.getMessage() == null) {
      t = t.getCause();
    }
    return t.getMessage();
  }

  private void set(String field, Object value) throws Exception {
    Field f = HealthResource.class.getDeclaredField(field);
    f.setAccessible(true);
    f.set(null, value);
  }

  private Object get(String field) throws Exception {
    Field f = HealthResource.class.getDeclaredField(field);
    f.setAccessible(true);
    return f.get(null);
  }
}
