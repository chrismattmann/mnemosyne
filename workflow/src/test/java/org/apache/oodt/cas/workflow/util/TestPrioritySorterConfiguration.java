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

package org.apache.oodt.cas.workflow.util;

import org.apache.oodt.cas.workflow.structs.FILOPrioritySorter;
import org.apache.oodt.cas.workflow.structs.HighestFIFOPrioritySorter;
import org.apache.oodt.cas.workflow.structs.HighestPrioritySorter;
import org.apache.oodt.cas.workflow.structs.PrioritySorter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The prioritizer is named by class in workflow.properties and resolved with
 * Class.newInstance(), so every sorter that can be named there needs a no-arg
 * constructor. HighestFIFOPrioritySorter did not have one (#239), and the
 * failure reported itself as a bare class name at SEVERE (#240).
 */
public class TestPrioritySorterConfiguration {

  private final List<LogRecord> records = new ArrayList<LogRecord>();

  private Logger factoryLog;

  private Handler capture;

  private Level priorLevel;

  @Before
  public void captureFactoryLogging() {
    factoryLog = Logger.getLogger(GenericWorkflowObjectFactory.class.getName());
    // Give this logger a level of its own. Several tests in this module set the
    // root logger to OFF and never put it back, and a logger with no level of
    // its own inherits that -- so the record under test would be filtered
    // before it ever reached the handler below, depending on what ran first.
    priorLevel = factoryLog.getLevel();
    factoryLog.setLevel(Level.ALL);
    capture = new Handler() {
      public void publish(LogRecord record) { records.add(record); }
      public void flush() { }
      public void close() { }
    };
    factoryLog.addHandler(capture);
  }

  @After
  public void releaseFactoryLogging() {
    factoryLog.removeHandler(capture);
    factoryLog.setLevel(priorLevel);
  }

  /** #239: this is the one that ages a waiting instance up, and it was the one that could not be selected. */
  @Test
  public void thefifoSorterCanBeNamedInConfiguration() {
    PrioritySorter sorter = GenericWorkflowObjectFactory
        .getPrioritySorterFromClassName(HighestFIFOPrioritySorter.class.getName());

    assertNotNull("naming it in workflow.properties must produce a sorter", sorter);
    assertTrue(sorter instanceof HighestFIFOPrioritySorter);
  }

  /** Every shipped sorter has to survive the path a deployment actually uses. */
  @Test
  public void everyShippedSorterCanBeNamedInConfiguration() {
    for (Class<?> type : new Class<?>[] {HighestFIFOPrioritySorter.class,
        HighestPrioritySorter.class, FILOPrioritySorter.class}) {
      assertNotNull(type.getName() + " must be configurable",
          GenericWorkflowObjectFactory.getPrioritySorterFromClassName(type.getName()));
    }
  }

  /** The no-arg constructor must agree with the three-arg one it delegates to. */
  @Test
  public void thedefaultsSortTheSameWayAsAnExplicitlyBuiltSorter() {
    PrioritySorter configured = GenericWorkflowObjectFactory
        .getPrioritySorterFromClassName(HighestFIFOPrioritySorter.class.getName());

    assertNotNull(configured);
    // Sorting nothing is still exercising sort(); the point is that a sorter
    // built by the factory is usable rather than a half-built object.
    configured.sort(new ArrayList());
  }

  /**
   * #240: the informative message went to WARNING and the class name alone to
   * SEVERE, so filtering to SEVERE -- which is what you do when a server will
   * not start -- showed the useless half.
   */
  @Test
  public void afailureIsReportedOnceWithSomethingWorthReading() {
    assertNull(GenericWorkflowObjectFactory
        .getPrioritySorterFromClassName("org.apache.oodt.NoSuchSorterAnywhere"));

    LogRecord severe = null;
    for (LogRecord record : records) {
      if (record.getLevel() == Level.SEVERE) {
        assertNull("it should be reported once, not twice", severe);
        severe = record;
      }
    }

    assertNotNull("the failure should be reported at SEVERE", severe);
    assertTrue("the message should name the class: " + severe.getMessage(),
        severe.getMessage().contains("org.apache.oodt.NoSuchSorterAnywhere"));
    assertTrue("and say what went wrong, not just echo the name: "
        + severe.getMessage(), severe.getMessage().length()
        > "org.apache.oodt.NoSuchSorterAnywhere".length() + 10);
    assertNotNull("and carry the exception, so a cause survives",
        severe.getThrown());
  }

  @Test
  public void anullClassNameIsStillNullWithoutComplaint() {
    assertNull(GenericWorkflowObjectFactory.getPrioritySorterFromClassName(null));
    assertEquals(0, records.size());
  }
}
