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

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.metadata.Metadata;
import org.springframework.context.support.StaticApplicationContext;

/**
 * Properties of the phase routing in {@link CrawlerActionRepo}.
 *
 * <p>The repository is the only thing standing between a crawler's bean file
 * and the order in which actions run. Everything asserted here is what a
 * crawler author gets from reading the bean file: an action listed under a
 * phase runs in that phase, in the order the ids were given, and in no other
 * phase.
 */
class CrawlerActionRepoPropertyTest {

  /** The smallest concrete action: it does nothing and reports success. */
  private static final class StubAction extends CrawlerAction {
    StubAction(String id, List<String> phases) {
      setId(id);
      this.phases = phases;
    }

    @Override
    public boolean performAction(File product, Metadata productMetadata) {
      return true;
    }
  }

  private static final String PRE = CrawlerActionPhases.PRE_INGEST.getName();
  private static final String SUCCESS = CrawlerActionPhases.POST_INGEST_SUCCESS.getName();
  private static final String FAILURE = CrawlerActionPhases.POST_INGEST_FAILURE.getName();
  private static final List<String> PHASE_NAMES = Arrays.asList(PRE, SUCCESS, FAILURE);

  /** A context holding one action per id, as a crawler's bean file would. */
  private static StaticApplicationContext contextFor(List<CrawlerAction> actions) {
    StaticApplicationContext context = new StaticApplicationContext();
    for (CrawlerAction action : actions) {
      context.getBeanFactory().registerSingleton(action.getId(), action);
    }
    context.refresh();
    return context;
  }

  /** Actions named a0, a1, ... each declaring the drawn set of phases. */
  private static List<CrawlerAction> actionsFor(List<List<String>> phaseLists) {
    List<CrawlerAction> actions = new ArrayList<>(phaseLists.size());
    for (int i = 0; i < phaseLists.size(); i++) {
      actions.add(new StubAction("a" + i, phaseLists.get(i)));
    }
    return actions;
  }

  private static List<String> idsOf(List<CrawlerAction> actions) {
    List<String> ids = new ArrayList<>(actions.size());
    for (CrawlerAction action : actions) {
      ids.add(action.getId());
    }
    return ids;
  }

  private static dev.hegel.Generator<List<List<String>>> phaseLists() {
    return lists(lists(sampledFrom(PHASE_NAMES)).minSize(1).maxSize(3)).maxSize(6);
  }

  /**
   * Every action reaches exactly the phases it declares, in the order its id
   * appeared, and reaches no phase it did not declare. This is the whole
   * contract a crawler author relies on when writing {@code actionIds}.
   */
  @HegelTest
  void actionsReachExactlyThePhasesTheyDeclare(TestCase tc) {
    List<List<String>> phaseLists = tc.draw(phaseLists(), "phaseLists");
    List<CrawlerAction> actions = actionsFor(phaseLists);

    StaticApplicationContext context = contextFor(actions);
    try {
      CrawlerActionRepo repo = new CrawlerActionRepo();
      repo.loadActionsFromBeanFactory(context, idsOf(actions));

      assertEquals(expectedFor(actions, PRE), repo.getPreIngestActions(), "preIngest");
      assertEquals(
          expectedFor(actions, SUCCESS), repo.getPostIngestOnSuccessActions(), "postIngestSuccess");
      assertEquals(
          expectedFor(actions, FAILURE), repo.getPostIngestOnFailActions(), "postIngestFailure");
    } finally {
      context.close();
    }
  }

  /** The actions a bean file puts in {@code phase}, in declaration order. */
  private static List<CrawlerAction> expectedFor(List<CrawlerAction> actions, String phase) {
    List<CrawlerAction> expected = new ArrayList<>();
    for (CrawlerAction action : actions) {
      for (String declared : action.getPhases()) {
        if (declared.equals(phase)) {
          expected.add(action);
        }
      }
    }
    return expected;
  }

  /**
   * {@code getActions()} is the union of the three phase lists and nothing
   * more. The crawler validates every action through this set, so an action
   * missing from it is an action whose configuration is never checked.
   */
  @HegelTest
  void getActionsIsTheUnionOfThePhases(TestCase tc) {
    List<List<String>> phaseLists = tc.draw(phaseLists(), "phaseLists");
    List<CrawlerAction> actions = actionsFor(phaseLists);

    StaticApplicationContext context = contextFor(actions);
    try {
      CrawlerActionRepo repo = new CrawlerActionRepo();
      repo.loadActionsFromBeanFactory(context, idsOf(actions));

      Set<CrawlerAction> union = new HashSet<>();
      union.addAll(repo.getPreIngestActions());
      union.addAll(repo.getPostIngestOnSuccessActions());
      union.addAll(repo.getPostIngestOnFailActions());

      assertEquals(union, repo.getActions());
    } finally {
      context.close();
    }
  }

  /**
   * Reading the repository does not change it. The crawler calls
   * {@code getActions()} during validation and then runs the phase lists, so a
   * query with a side effect would silently change what runs.
   */
  @HegelTest
  void queryingTheRepositoryLeavesItAlone(TestCase tc) {
    List<List<String>> phaseLists = tc.draw(phaseLists(), "phaseLists");
    List<CrawlerAction> actions = actionsFor(phaseLists);

    StaticApplicationContext context = contextFor(actions);
    try {
      CrawlerActionRepo repo = new CrawlerActionRepo();
      repo.loadActionsFromBeanFactory(context, idsOf(actions));

      List<CrawlerAction> pre = new ArrayList<>(repo.getPreIngestActions());
      List<CrawlerAction> success = new ArrayList<>(repo.getPostIngestOnSuccessActions());
      List<CrawlerAction> failure = new ArrayList<>(repo.getPostIngestOnFailActions());

      Set<CrawlerAction> first = repo.getActions();
      Set<CrawlerAction> second = repo.getActions();

      assertEquals(first, second, "two reads of getActions() disagree");
      assertEquals(pre, repo.getPreIngestActions(), "getActions() changed preIngest");
      assertEquals(success, repo.getPostIngestOnSuccessActions(), "getActions() changed success");
      assertEquals(failure, repo.getPostIngestOnFailActions(), "getActions() changed failure");
    } finally {
      context.close();
    }
  }

  /** Loading nothing leaves every phase empty rather than failing. */
  @HegelTest
  void anEmptyActionListLeavesEveryPhaseEmpty(TestCase tc) {
    tc.note("no actions configured");

    StaticApplicationContext context = contextFor(new ArrayList<CrawlerAction>());
    try {
      CrawlerActionRepo repo = new CrawlerActionRepo();
      repo.loadActionsFromBeanFactory(context, new ArrayList<String>());

      assertTrue(repo.getPreIngestActions().isEmpty());
      assertTrue(repo.getPostIngestOnSuccessActions().isEmpty());
      assertTrue(repo.getPostIngestOnFailActions().isEmpty());
      assertTrue(repo.getActions().isEmpty());
    } finally {
      context.close();
    }
  }

  /**
   * A phase name that is not one of the three supported phases is ordinary user
   * error — it comes straight out of a hand-written bean file — and the
   * repository has an explicit branch that says so, naming the offending phase.
   * Whatever is thrown must carry that name, or the crawler author is left with
   * no way to find the typo.
   */
  @HegelTest
  void anUnsupportedPhaseNameIsReportedWithTheNameInIt(TestCase tc) {
    String phase =
        tc.draw(
            text().minSize(1).maxSize(16).categories("Lu", "Ll", "Nd").filter(
                s -> !PHASE_NAMES.contains(s)),
            "phase");

    List<CrawlerAction> actions = Arrays.<CrawlerAction>asList(new StubAction("a0", Arrays.asList(phase)));
    StaticApplicationContext context = contextFor(actions);
    try {
      CrawlerActionRepo repo = new CrawlerActionRepo();
      try {
        repo.loadActionsFromBeanFactory(context, idsOf(actions));
        fail("an unsupported phase name '" + phase + "' was accepted");
      } catch (RuntimeException e) {
        String message = String.valueOf(e.getMessage());
        assertTrue(
            message.contains(phase),
            "the error does not name the offending phase '"
                + phase
                + "': "
                + e.getClass().getName()
                + ": "
                + e.getMessage());
      }
    } finally {
      context.close();
    }
  }
}
