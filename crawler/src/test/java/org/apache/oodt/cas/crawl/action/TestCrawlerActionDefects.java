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

import org.apache.oodt.cas.crawl.structs.exceptions.CrawlerActionException;
import org.apache.oodt.cas.metadata.Metadata;

import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Items 26-29 of #134: crawler actions that crash, or silently do nothing,
 * where the code right beside them was written to handle the case.
 */
public class TestCrawlerActionDefects {

  /**
   * 27. equals dereferenced the *other* action's id, and hashCode its own. An
   * action with no id is exactly what validate() exists to report, but
   * CrawlerActionRepo.getActions() puts actions in a HashSet and
   * ProductCrawler.validateActions() walks that set first -- so the crash
   * pre-empted the check written to describe it.
   */
  @Test
  public void anActionWithNoIdCanGoInASet() {
    Set<CrawlerAction> actions = new HashSet<CrawlerAction>();
    actions.add(new StubAction(null));

    assertEquals(1, actions.size());
  }

  @Test
  public void comparingAgainstAnActionWithNoIdDoesNotThrow() {
    assertFalse(new StubAction("a").equals(new StubAction(null)));
    assertFalse(new StubAction(null).equals(new StubAction("a")));
    assertTrue(new StubAction(null).equals(new StubAction(null)));
  }

  @Test
  public void actionsWithTheSameIdAreStillEqual() {
    assertTrue(new StubAction("a").equals(new StubAction("a")));
    assertFalse(new StubAction("a").equals(new StubAction("b")));
  }

  /** An action with no id now reaches its own validate(). */
  @Test
  public void validateStillReportsAMissingId() {
    try {
      new StubAction(null).validate();
      throw new AssertionError("expected a CrawlerActionException");
    } catch (CrawlerActionException e) {
      assertNotNull(e.getMessage());
    }
  }

  /**
   * 28. The javadoc says the success or failure action "should be allowed to
   * remain unspecified", and the null guards were already there -- but the
   * logging dereferenced the action one line ahead of them.
   */
  @Test
  public void anUnspecifiedSuccessActionIsAllowed() throws Exception {
    TernaryAction action = new TernaryAction();
    action.setConditionAction(new StubAction("cond", true));
    action.setFailureAction(new StubAction("fail"));

    assertTrue(action.performAction(new File("."), new Metadata()));
  }

  @Test
  public void anUnspecifiedFailureActionIsAllowed() throws Exception {
    TernaryAction action = new TernaryAction();
    action.setConditionAction(new StubAction("cond", false));
    action.setSuccessAction(new StubAction("success"));

    assertTrue(action.performAction(new File("."), new Metadata()));
  }

  @Test
  public void thespecifiedBranchIsStillTheOneThatRuns() throws Exception {
    StubAction success = new StubAction("success", true);
    StubAction failure = new StubAction("failure", true);

    TernaryAction action = new TernaryAction();
    action.setConditionAction(new StubAction("cond", true));
    action.setSuccessAction(success);
    action.setFailureAction(failure);
    action.performAction(new File("."), new Metadata());

    assertTrue(success.ran);
    assertFalse(failure.ran);
  }

  /**
   * 26. Switching on the lookup unboxed null, so a typo in a bean file's
   * phases property died with "Cannot invoke CrawlerActionPhases.ordinal()"
   * and the default branch written to name the phase was unreachable.
   */
  @Test
  public void anUnsupportedPhaseNamesItself() {
    StubAction action = new StubAction("a");
    action.setPhases(Arrays.asList("preIngestTypo"));

    try {
      new CrawlerActionRepo().loadActionsFromBeanFactory(contextWith(action),
          Arrays.asList("a"));
      throw new AssertionError("expected a RuntimeException");
    } catch (RuntimeException e) {
      assertTrue("message should name the phase: " + e.getMessage(),
          String.valueOf(e.getMessage()).contains("preIngestTypo"));
    }
  }

  @Test
  public void asupportedPhaseIsStillLoaded() {
    StubAction action = new StubAction("a");
    action.setPhases(Arrays.asList(CrawlerActionPhases.PRE_INGEST.getName()));

    CrawlerActionRepo repo = new CrawlerActionRepo();
    repo.loadActionsFromBeanFactory(contextWith(action), Arrays.asList("a"));

    assertEquals(1, repo.getPreIngestActions().size());
  }

  /** 29. Spring binds &lt;property name="mimeTypes"&gt; to setMimeTypes. */
  @Test
  public void theMimeTypesPropertyIsBindable() throws Exception {
    assertNotNull(MimeTypeCrawlerAction.class.getMethod("setMimeTypes", List.class));
  }

  private ApplicationContext contextWith(CrawlerAction action) {
    GenericApplicationContext context = new GenericApplicationContext();
    context.refresh();
    context.getBeanFactory().registerSingleton(action.getId(), action);
    return context;
  }

  private static class StubAction extends CrawlerAction {
    private final boolean result;
    boolean ran;

    StubAction(String id) {
      this(id, true);
    }

    StubAction(String id, boolean result) {
      setId(id);
      this.result = result;
    }

    @Override
    public boolean performAction(File product, Metadata metadata) {
      this.ran = true;
      return result;
    }
  }
}
