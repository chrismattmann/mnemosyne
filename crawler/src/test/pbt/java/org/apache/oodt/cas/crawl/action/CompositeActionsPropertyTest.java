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
import static dev.hegel.Generators.text;
import static dev.hegel.Generators.tuples;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import dev.hegel.Tuple2;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.oodt.cas.crawl.structs.exceptions.CrawlerActionException;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of the actions that combine other actions: {@link GroupAction},
 * {@link ToggleAction}, {@link TernaryAction} and {@link MimeTypeCrawlerAction}.
 *
 * <p>These four exist only to decide which of their children run and what the
 * combined answer is. The crawler turns that answer into an ingest verdict for
 * the product, so getting it wrong either ingests a product that should have
 * been held back or reports a failure that did not happen.
 */
class CompositeActionsPropertyTest {

  private static final File PRODUCT = new File("data", "product.dat");
  private static final String MIME_TYPES_HIERARCHY = "MimeTypesHierarchy";

  /** An action that reports a fixed answer, or throws, and counts its calls. */
  private static final class StubAction extends CrawlerAction {
    private final boolean result;
    private final boolean throwing;
    private int calls;

    StubAction(String id, boolean result, boolean throwing) {
      setId(id);
      setPhases(Arrays.asList(CrawlerActionPhases.PRE_INGEST.getName()));
      this.result = result;
      this.throwing = throwing;
    }

    @Override
    public boolean performAction(File product, Metadata productMetadata)
        throws CrawlerActionException {
      calls++;
      if (throwing) {
        throw new CrawlerActionException("stub " + getId() + " failed");
      }
      return result;
    }
  }

  private static dev.hegel.Generator<List<Tuple2<Boolean, Boolean>>> outcomes(int max) {
    return lists(tuples(booleans(), booleans())).maxSize(max);
  }

  private static List<StubAction> stubsFor(List<Tuple2<Boolean, Boolean>> outcomes) {
    List<StubAction> actions = new ArrayList<>();
    for (int i = 0; i < outcomes.size(); i++) {
      actions.add(new StubAction("a" + i, outcomes.get(i).value1(), outcomes.get(i).value2()));
    }
    return actions;
  }

  /**
   * A group succeeds only if every member did, and every member is given its
   * turn. The class exists so that a bean file can name one id instead of
   * several, so the group must behave as if all of them had been listed.
   */
  @HegelTest
  void aGroupSucceedsOnlyIfEveryMemberDid(TestCase tc) throws Exception {
    List<Tuple2<Boolean, Boolean>> outcomes = tc.draw(outcomes(6), "outcomes");
    List<StubAction> members = stubsFor(outcomes);

    GroupAction group = new GroupAction();
    group.setId("group");
    group.setPhases(Arrays.asList(CrawlerActionPhases.PRE_INGEST.getName()));
    group.setActionsToCall(new ArrayList<CrawlerAction>(members));

    boolean expected = true;
    for (Tuple2<Boolean, Boolean> outcome : outcomes) {
      expected &= outcome.value1() && !outcome.value2();
    }

    assertEquals(expected, group.performAction(PRODUCT, new Metadata()));
    for (StubAction member : members) {
      assertEquals(1, member.calls, member.getId() + " was not given its turn");
    }
  }

  /** A group with no members to call is a configuration error, and is reported. */
  @HegelTest
  void aGroupWithNoMembersIsRejectedByValidation(TestCase tc) {
    String id = tc.draw(text().minSize(1).maxSize(8).categories("Lu", "Ll"), "id");

    GroupAction group = new GroupAction();
    group.setId(id);
    group.setPhases(Arrays.asList(CrawlerActionPhases.PRE_INGEST.getName()));

    try {
      group.validate();
      fail("a group with no actionsToCall was accepted");
    } catch (CrawlerActionException expected) {
      assertTrue(true);
    }
  }

  /**
   * A toggle succeeds if any of its switched-on branches succeeded. The class
   * exists for the case where one of several alternatives applies, so one
   * working alternative is the whole answer.
   */
  @HegelTest
  void aToggleSucceedsIfAnySwitchedOnBranchDid(TestCase tc) throws Exception {
    List<Tuple2<Boolean, Boolean>> outcomes =
        tc.draw(lists(tuples(booleans(), booleans())).minSize(1).maxSize(5), "outcomes");
    List<Boolean> switches = tc.draw(lists(booleans()).maxSize(5), "switches");

    List<StubAction> branches = stubsFor(outcomes);
    ToggleAction toggleAction = new ToggleAction();
    toggleAction.setId("toggle");
    toggleAction.setPhases(Arrays.asList(CrawlerActionPhases.PRE_INGEST.getName()));
    toggleAction.setShortCircuit(false);

    List<ToggleAction.Toggle> toggles = new ArrayList<>();
    boolean expected = false;
    for (int i = 0; i < branches.size(); i++) {
      final boolean on = i < switches.size() && switches.get(i);
      ToggleAction.Toggle toggle =
          toggleAction.new Toggle() {
            @Override
            public boolean isOn(File product, Metadata productMetadata) {
              return on;
            }
          };
      toggle.setCrawlerAction(branches.get(i));
      toggles.add(toggle);
      expected |= on && outcomes.get(i).value1() && !outcomes.get(i).value2();
    }
    toggleAction.setToggles(toggles);

    assertEquals(expected, toggleAction.performAction(PRODUCT, new Metadata()));
    for (int i = 0; i < branches.size(); i++) {
      boolean on = i < switches.size() && switches.get(i);
      assertEquals(on ? 1 : 0, branches.get(i).calls, "branch " + i + " ran when it was switched " + (on ? "on" : "off"));
    }
  }

  /** A toggle with nothing to choose between has nothing to fail at. */
  @HegelTest
  void aToggleWithNoBranchesSucceeds(TestCase tc) throws Exception {
    boolean nullList = tc.draw(booleans(), "nullList");

    ToggleAction toggleAction = new ToggleAction();
    toggleAction.setId("toggle");
    toggleAction.setPhases(Arrays.asList(CrawlerActionPhases.PRE_INGEST.getName()));
    if (!nullList) {
      toggleAction.setToggles(new ArrayList<ToggleAction.Toggle>());
    }

    assertTrue(toggleAction.performAction(PRODUCT, new Metadata()));
  }

  /**
   * A ternary action runs the branch its condition chose. The condition's own
   * verdict is not the answer — the chosen branch's is.
   */
  @HegelTest
  void aTernaryActionRunsTheBranchItsConditionChose(TestCase tc) throws Exception {
    boolean conditionHolds = tc.draw(booleans(), "conditionHolds");
    boolean successResult = tc.draw(booleans(), "successResult");
    boolean failureResult = tc.draw(booleans(), "failureResult");

    StubAction condition = new StubAction("condition", conditionHolds, false);
    StubAction onSuccess = new StubAction("onSuccess", successResult, false);
    StubAction onFailure = new StubAction("onFailure", failureResult, false);

    TernaryAction ternary = new TernaryAction();
    ternary.setId("ternary");
    ternary.setPhases(Arrays.asList(CrawlerActionPhases.PRE_INGEST.getName()));
    ternary.setConditionAction(condition);
    ternary.setSuccessAction(onSuccess);
    ternary.setFailureAction(onFailure);

    assertEquals(conditionHolds ? successResult : failureResult, ternary.performAction(PRODUCT, new Metadata()));
    assertEquals(1, condition.calls, "the condition did not run");
    assertEquals(conditionHolds ? 1 : 0, onSuccess.calls, "the success branch ran out of turn");
    assertEquals(conditionHolds ? 0 : 1, onFailure.calls, "the failure branch ran out of turn");
  }

  /**
   * A branch left unspecified counts as nothing to do. The class documents that
   * "this action should allow the success or failure action to remain
   * unspecified", and its own return statements guard for exactly that, so a
   * bean file naming only one branch is a supported configuration.
   */
  @HegelTest
  void anUnspecifiedBranchCountsAsNothingToDo(TestCase tc) throws Exception {
    boolean conditionHolds = tc.draw(booleans(), "conditionHolds");
    boolean otherResult = tc.draw(booleans(), "otherResult");

    StubAction condition = new StubAction("condition", conditionHolds, false);
    StubAction other = new StubAction("other", otherResult, false);

    TernaryAction ternary = new TernaryAction();
    ternary.setId("ternary");
    ternary.setPhases(Arrays.asList(CrawlerActionPhases.PRE_INGEST.getName()));
    ternary.setConditionAction(condition);
    if (conditionHolds) {
      ternary.setFailureAction(other);
    } else {
      ternary.setSuccessAction(other);
    }

    try {
      assertTrue(
          ternary.performAction(PRODUCT, new Metadata()),
          "an unspecified branch was treated as a failure");
    } catch (RuntimeException e) {
      fail(
          "leaving the "
              + (conditionHolds ? "success" : "failure")
              + " action unspecified threw "
              + e.getClass().getName());
    }
    assertEquals(0, other.calls, "the branch that was not chosen ran anyway");
  }

  /**
   * A mime-type restricted action runs exactly when the product's mime-type
   * hierarchy overlaps the types it was given, and reports success when it
   * decides not to run — skipping is not a failure.
   */
  @HegelTest
  void aMimeRestrictedActionRunsExactlyOnTheTypesItNames(TestCase tc) throws Exception {
    List<String> configured = tc.draw(lists(mimeType()).maxSize(4), "configured");
    List<String> hierarchy = tc.draw(lists(mimeType()).maxSize(4), "hierarchy");
    boolean innerResult = tc.draw(booleans(), "innerResult");

    StubAction inner = new StubAction("inner", innerResult, false);
    MimeTypeCrawlerAction action = new MimeTypeCrawlerAction();
    action.setId("mimeRestricted");
    action.setActionToCall(inner);
    action.asetMimeTypes(configured);

    Metadata metadata = new Metadata();
    for (String type : hierarchy) {
      metadata.addMetadata(MIME_TYPES_HIERARCHY, type);
    }

    boolean applies = false;
    for (String type : configured) {
      if (hierarchy.contains(type)) {
        applies = true;
        break;
      }
    }

    boolean answer = action.performAction(PRODUCT, metadata);

    assertEquals(applies ? 1 : 0, inner.calls, "the restricted action ran on the wrong product");
    assertEquals(applies ? innerResult : true, answer, "skipping was reported as a failure");
  }

  /**
   * An action with no mime types configured applies to everything. That is the
   * unrestricted case, and it must not silently skip every product.
   */
  @HegelTest
  void anUnrestrictedActionAppliesToEveryProduct(TestCase tc) throws Exception {
    List<String> hierarchy = tc.draw(lists(mimeType()).maxSize(4), "hierarchy");
    boolean innerResult = tc.draw(booleans(), "innerResult");

    StubAction inner = new StubAction("inner", innerResult, false);
    MimeTypeCrawlerAction action = new MimeTypeCrawlerAction();
    action.setId("unrestricted");
    action.setActionToCall(inner);

    Metadata metadata = new Metadata();
    for (String type : hierarchy) {
      metadata.addMetadata(MIME_TYPES_HIERARCHY, type);
    }

    assertEquals(innerResult, action.performAction(PRODUCT, metadata));
    assertEquals(1, inner.calls, "an unrestricted action skipped a product");
  }

  /**
   * Restricting an action does not change which phases it runs in, nor does it
   * lose the identity of the action it wraps.
   */
  @HegelTest
  void restrictingAnActionKeepsItsPhases(TestCase tc) {
    String id = tc.draw(text().minSize(1).maxSize(8).categories("Lu", "Ll"), "id");

    StubAction inner = new StubAction(id, true, false);
    MimeTypeCrawlerAction action = new MimeTypeCrawlerAction();
    action.setActionToCall(inner);

    assertEquals(inner.getPhases(), action.getPhases(), "the wrapper runs in different phases");
    assertFalse(action.getDescription() == null, "the wrapper has no description");
    assertTrue(action.getDescription().contains(id), "the description does not name the wrapped action");
  }

  private static dev.hegel.Generator<String> mimeType() {
    return text().minSize(1).maxSize(6).categories("Ll").map(s -> "application/" + s);
  }
}
