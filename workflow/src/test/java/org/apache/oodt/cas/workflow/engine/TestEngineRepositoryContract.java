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

package org.apache.oodt.cas.workflow.engine;

import org.apache.oodt.cas.workflow.engine.runner.AsynchronousLocalEngineRunner;
import org.apache.oodt.cas.workflow.instrepo.MemoryWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.repository.PackagedWorkflowRepository;
import org.apache.oodt.cas.workflow.repository.WorkflowRepository;
import org.apache.oodt.cas.workflow.structs.HighestFIFOPrioritySorter;

import junit.framework.TestCase;

import java.io.File;
import java.util.Arrays;

/**
 * Every engine can say which repository it belongs to.
 *
 * <p>
 * A caller adding a workflow at runtime has to put it where the engine will
 * look. One engine builds a repository, the other is handed models and built
 * none, and answered null -- so the caller had to guard for an engine that
 * could not say, and a null slipping past that guard is an exception at the
 * point of use rather than at the point of the mistake.
 * </p>
 */
public class TestEngineRepositoryContract extends TestCase {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String MODEL_DIR = "./src/test/resources/wengine-e2e";

  private PackagedWorkflowRepository repo;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.repo = new PackagedWorkflowRepository(
        Arrays.asList(new File(MODEL_DIR).listFiles()));
  }

  /** The queue-based engine resolves against the one it was built with. */
  public void testThequeueBasedEngineReportsTheRepositoryItResolvesAgainst()
      throws Exception {
    PrioritizedQueueBasedWorkflowEngine engine =
        new PrioritizedQueueBasedWorkflowEngine(
            new MemoryWorkflowInstanceRepository(500),
            new HighestFIFOPrioritySorter(1, 50, 1),
            new WorkflowLifecycleManager(LIFECYCLE),
            new AsynchronousLocalEngineRunner(), repo, 1);

    assertSame("it must report the repository it was constructed with",
        repo, engine.getWorkflowRepository());
  }

  /**
   * Its repository is fixed at construction, because the processor queue it
   * built holds that reference. Accepting another would report one it does
   * not use.
   */
  public void testThequeueBasedEngineKeepsTheRepositoryItWasBuiltWith()
      throws Exception {
    PrioritizedQueueBasedWorkflowEngine engine =
        new PrioritizedQueueBasedWorkflowEngine(
            new MemoryWorkflowInstanceRepository(500),
            new HighestFIFOPrioritySorter(1, 50, 1),
            new WorkflowLifecycleManager(LIFECYCLE),
            new AsynchronousLocalEngineRunner(), repo, 1);

    WorkflowRepository other = new PackagedWorkflowRepository(
        Arrays.asList(new File(MODEL_DIR).listFiles()));
    engine.setWorkflowRepository(other);

    assertSame("its processor queue holds the original, so that is what it"
        + " must keep reporting", repo, engine.getWorkflowRepository());
  }

  /**
   * The thread pool engine is handed models rather than looking them up, so
   * it has none until its owner supplies one -- and then it answers.
   */
  public void testThethreadPoolEngineReportsTheRepositoryItIsGiven()
      throws Exception {
    ThreadPoolWorkflowEngine engine = new ThreadPoolWorkflowEngine(
        new MemoryWorkflowInstanceRepository(500), 10, 5, 1, 5L, false, null);

    engine.setWorkflowRepository(repo);

    assertSame("an engine told which repository it belongs to must say so",
        repo, engine.getWorkflowRepository());
  }

  /** Handed a different one later, it reports the newer one. */
  public void testThethreadPoolEngineFollowsArefreshedRepository()
      throws Exception {
    ThreadPoolWorkflowEngine engine = new ThreadPoolWorkflowEngine(
        new MemoryWorkflowInstanceRepository(500), 10, 5, 1, 5L, false, null);
    engine.setWorkflowRepository(repo);

    WorkflowRepository refreshed = new PackagedWorkflowRepository(
        Arrays.asList(new File(MODEL_DIR).listFiles()));
    engine.setWorkflowRepository(refreshed);

    assertSame("refreshRepository replaces the repository, and the engine"
        + " must not keep answering for the discarded one",
        refreshed, engine.getWorkflowRepository());
  }
}
