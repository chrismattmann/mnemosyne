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
package org.apache.oodt.cas.workflow.lifecycle;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Whether a lifecycle answers the categories an engine will ask for.
 *
 * <p>A lifecycle written for one engine defines none of the categories
 * another asks for. The queue based engine wants initial, transition and
 * done; a thread pool lifecycle offers setup, error and completion. The
 * state is still handed back, but with no category, and an instance
 * holding it cannot be transitioned out: every instance is created and
 * then parks forever, with nothing logged.
 *
 * <p>Asking the question up front is what turns that into one line at the
 * point the deployment was configured wrongly.</p>
 */
public class TestMissingCategories {

  private WorkflowLifecycle threadPoolStyle() {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle();
    lifecycle.setName("thread-pool-style");
    for (String name : Arrays.asList("setup", "error", "completion")) {
      WorkflowLifecycleStage stage = new WorkflowLifecycleStage();
      stage.setName(name);
      lifecycle.addStage(stage);
    }
    return lifecycle;
  }

  private WorkflowLifecycle wengineStyle() {
    WorkflowLifecycle lifecycle = new WorkflowLifecycle();
    lifecycle.setName("wengine-style");
    for (String name : Arrays.asList("initial", "waiting", "transition",
        "holding", "running", "results", "done")) {
      WorkflowLifecycleStage stage = new WorkflowLifecycleStage();
      stage.setName(name);
      lifecycle.addStage(stage);
    }
    return lifecycle;
  }

  @Test
  public void testTheWrongLifecycleNamesEveryMissingCategory() {
    List<String> missing = threadPoolStyle().missingCategories(
        Arrays.asList("initial", "transition", "done"));
    assertEquals("all three are absent and all three should be named",
        Arrays.asList("initial", "transition", "done"), missing);
  }

  @Test
  public void testTheRightLifecycleIsMissingNothing() {
    assertTrue("the wengine lifecycle answers what the queue engine asks",
        wengineStyle().missingCategories(
            Arrays.asList("initial", "transition", "done")).isEmpty());
  }

  @Test
  public void testAStateInAnUnknownCategoryHasNoCategory() {
    // The behaviour the check exists to explain: the state comes back, so
    // nothing throws, but it carries nothing to transition on.
    WorkflowState state = threadPoolStyle()
        .createState("Null", "initial", "starting");
    assertNull("this is why the instance never moves", state.getCategory());
  }

  @Test
  public void testNothingRequiredIsNothingMissing() {
    assertTrue(threadPoolStyle().missingCategories(
        Collections.<String>emptyList()).isEmpty());
    assertTrue("a null request is not a failure",
        threadPoolStyle().missingCategories(null).isEmpty());
  }
}
