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

package org.apache.oodt.cas.pge;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a PGE tells the workflow manager its status is.
 *
 * <p>
 * The phases are the PGE's own. Whether they are also the engine's is the
 * question this answers: a status the engine's lifecycle cannot categorise
 * is one the processor queue drops, so a PGE reporting one was invisible to
 * the task querier for as long as it ran.
 * </p>
 */
public class TestPgeStatusMapping extends TestCase {

  /** The statuses the older engine's lifecycle declares. */
  private static final List<String> W1 = Arrays.asList(
      "QUEUED", "BUILDING CONFIG FILE", "STAGING INPUT", "PGE EXEC",
      "CRAWLING", "FINISHED", "STARTED", "PAUSED");

  /** The statuses the queue-based engine's lifecycle declares. */
  private static final List<String> W2 = Arrays.asList(
      "Null", "Loaded", "Queued", "Blocked", "WaitingOnResources",
      "PreConditionEval", "Executing", "PostConditionEval", "Success",
      "Failure");

  /** A PGE told what the manager's lifecycle declares. */
  private static class Pge extends StdPGETaskInstance {
    private final List<String> supported;

    Pge(List<String> supported) {
      this.supported = supported;
    }

    @Override
    protected List<String> loadSupportedStatuses() {
      // Only what the manager reports is stubbed. reportableStatus itself is
      // the code under test, so overriding it would prove nothing.
      return supported;
    }
  }

  /**
   * On the older engine every phase is a status its lifecycle declares, so
   * nothing changes. This is the property worth holding: those deployments
   * must not notice.
   */
  public void testAlifecycleThatDeclaresThePhaseGetsThePhase() {
    Pge pge = new Pge(W1);

    assertEquals("BUILDING CONFIG FILE",
        pge.reportableStatus("BUILDING CONFIG FILE"));
    assertEquals("STAGING INPUT", pge.reportableStatus("STAGING INPUT"));
    assertEquals("PGE EXEC", pge.reportableStatus("PGE EXEC"));
    assertEquals("CRAWLING", pge.reportableStatus("CRAWLING"));
  }

  /**
   * On the queue-based engine none of them are declared, and all four mean
   * the same thing to that engine: the task is running.
   */
  public void testAlifecycleThatDoesNotGetsTheStateThePhaseIs() {
    Pge pge = new Pge(W2);

    assertEquals("Executing", pge.reportableStatus("BUILDING CONFIG FILE"));
    assertEquals("Executing", pge.reportableStatus("STAGING INPUT"));
    assertEquals("Executing", pge.reportableStatus("PGE EXEC"));
    assertEquals("Executing", pge.reportableStatus("CRAWLING"));
  }

  /** A status both vocabularies share is left alone. */
  public void testAstatusTheLifecycleSharesIsNotRenamed() {
    assertEquals("Success", new Pge(W2).reportableStatus("Success"));
    assertEquals("Failure", new Pge(W2).reportableStatus("Failure"));
  }

  /**
   * A manager too old to be asked reports nothing, and a PGE then behaves
   * exactly as it always has. Silence is not a reason to start renaming.
   */
  public void testAmanagerThatCannotSayLeavesEverythingAlone() {
    Pge pge = new Pge(new ArrayList<String>());

    assertEquals("PGE EXEC", pge.reportableStatus("PGE EXEC"));
    assertEquals("CRAWLING", pge.reportableStatus("CRAWLING"));
  }

  public void testAnullPhaseIsLeftAlone() {
    assertNull(new Pge(W2).reportableStatus(null));
  }
}
