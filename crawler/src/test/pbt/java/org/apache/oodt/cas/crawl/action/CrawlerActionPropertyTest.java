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

package org.apache.oodt.cas.crawl.action;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.crawl.structs.exceptions.CrawlerActionException;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of identity and validation on {@link CrawlerAction}.
 *
 * <p>Actions are put into a {@link Set} by {@link CrawlerActionRepo#getActions()}
 * and compared by id, so {@code equals} and {@code hashCode} carry real weight:
 * the crawler's own action validation walks that set. The class also states, in
 * {@link CrawlerAction#validate()}, that an action with no id is a state it
 * expects to encounter and report.
 */
class CrawlerActionPropertyTest {

  /** The smallest concrete action: it does nothing and reports a fixed answer. */
  private static final class StubAction extends CrawlerAction {
    private final boolean result;

    StubAction(String id, List<String> phases, boolean result) {
      setId(id);
      this.phases = phases;
      this.result = result;
    }

    @Override
    public boolean performAction(File product, Metadata productMetadata) {
      return result;
    }
  }

  private static final List<String> PHASE_NAMES =
      Arrays.asList(
          CrawlerActionPhases.PRE_INGEST.getName(),
          CrawlerActionPhases.POST_INGEST_SUCCESS.getName(),
          CrawlerActionPhases.POST_INGEST_FAILURE.getName());

  private static dev.hegel.Generator<String> ids() {
    return text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");
  }

  /**
   * Identity is the id and nothing else. Two beans configured with the same id
   * are the same action to the repository, whatever else differs about them.
   */
  @HegelTest
  void identityIsTheIdAndNothingElse(TestCase tc) {
    String idA = tc.draw(ids(), "idA");
    String idB = tc.draw(ids(), "idB");
    boolean resultA = tc.draw(booleans(), "resultA");
    boolean resultB = tc.draw(booleans(), "resultB");
    List<String> phasesA = tc.draw(lists(sampledFrom(PHASE_NAMES)).maxSize(3), "phasesA");
    List<String> phasesB = tc.draw(lists(sampledFrom(PHASE_NAMES)).maxSize(3), "phasesB");

    CrawlerAction a = new StubAction(idA, phasesA, resultA);
    CrawlerAction b = new StubAction(idB, phasesB, resultB);

    assertEquals(idA.equals(idB), a.equals(b), "equality disagrees with the ids");
    assertEquals(a.equals(b), b.equals(a), "equality is not symmetric");
    assertTrue(a.equals(a), "an action is not equal to itself");
    if (a.equals(b)) {
      assertEquals(a.hashCode(), b.hashCode(), "equal actions hash differently");
    }
  }

  /** An action is never equal to null or to something that is not an action. */
  @HegelTest
  void nothingForeignIsEqualToAnAction(TestCase tc) {
    String id = tc.draw(ids(), "id");
    CrawlerAction a = new StubAction(id, PHASE_NAMES, true);

    assertFalse(a.equals(null), "an action claimed to equal null");
    assertFalse(a.equals(id), "an action claimed to equal its own id string");
  }

  /**
   * {@code equals} must answer, not explode. {@link CrawlerActionRepo#getActions()}
   * drops actions into a {@link HashSet} before anything has called
   * {@link CrawlerAction#validate()}, so the comparison can be handed an action
   * whose id has not been injected yet — exactly the state validate() exists to
   * report.
   */
  @HegelTest
  void equalsAnswersForAnActionWhoseIdIsNotSetYet(TestCase tc) {
    String id = tc.draw(ids(), "id");

    CrawlerAction configured = new StubAction(id, PHASE_NAMES, true);
    CrawlerAction awaitingId = new StubAction(null, PHASE_NAMES, true);

    try {
      assertFalse(configured.equals(awaitingId), "an action with no id was reported equal");
    } catch (RuntimeException e) {
      fail("equals threw " + e.getClass().getName() + " instead of returning false");
    }
  }

  /**
   * Validation reports exactly the two things it claims to require. Spring
   * marks {@code setPhases} as required and the id is injected by the framework,
   * so a bean missing either is a configuration error the crawler must name
   * rather than fail on later.
   */
  @HegelTest
  void validationRejectsExactlyTheIncompleteActions(TestCase tc) {
    boolean hasId = tc.draw(booleans(), "hasId");
    boolean hasPhases = tc.draw(booleans(), "hasPhases");
    String id = tc.draw(ids(), "id");
    List<String> phases = tc.draw(lists(sampledFrom(PHASE_NAMES)).maxSize(3), "phases");

    CrawlerAction action = new StubAction(hasId ? id : null, hasPhases ? phases : null, true);

    if (hasId && hasPhases) {
      try {
        action.validate();
      } catch (CrawlerActionException e) {
        fail("a fully configured action was rejected: " + e.getMessage());
      }
    } else {
      assertThrows(
          CrawlerActionException.class,
          action::validate,
          "an action missing its id or phases was accepted");
    }
  }

  /** The accessors hand back what was configured, unchanged. */
  @HegelTest
  void configuredValuesAreReadBackUnchanged(TestCase tc) {
    String id = tc.draw(ids(), "id");
    String description = tc.draw(text().maxSize(40), "description");
    List<String> phases = tc.draw(lists(sampledFrom(PHASE_NAMES)).maxSize(3), "phases");

    CrawlerAction action = new StubAction(id, phases, true);
    action.setDescription(description);

    assertEquals(id, action.getId());
    assertEquals(description, action.getDescription());
    assertEquals(phases, action.getPhases());
  }
}
