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

import java.util.List;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArrayList;

import junit.framework.TestCase;

import org.apache.oodt.cas.workflow.engine.processor.WorkflowProcessor;
import org.apache.oodt.cas.workflow.engine.processor.WorkflowProcessorQueue;
import org.apache.oodt.cas.workflow.structs.FILOPrioritySorter;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;

/**
 * The reason an instance is waiting has to reach the repository.
 *
 * <p>
 * Nothing outside the engine's own JVM reads engine memory: the REST layer
 * that draws the instance list, a restart rebuilding its processors, and any
 * other engine looking at the same store all read the repository. A reason
 * computed and left in a field is a reason nobody can see, which is the whole
 * of what it was added for.
 * </p>
 */
public class TestWaitingOnIsPersisted extends TestCase {

  private static final long WAIT_SECS = 1;

  public void testTheQuerierWritesTheReasonWhereItCanBeRead()
      throws Exception {
    RecordingRepository repo = new RecordingRepository();
    GatedProcessorQueue queue = new GatedProcessorQueue();

    TaskQuerier querier =
        new TaskQuerier(queue, new FILOPrioritySorter(), repo, WAIT_SECS);
    Thread querierThread = new Thread(querier);
    querierThread.start();
    try {
      long deadline = System.currentTimeMillis() + 20000L;
      while (repo.reasonWritten() == null
          && System.currentTimeMillis() < deadline) {
        Thread.sleep(50L);
      }
      assertEquals("the reason never reached the repository",
          "condition:urn:test:Gate", repo.reasonWritten());
    } finally {
      querier.setRunning(false);
      querierThread.join(5000L);
    }
  }

  /** Records what the querier actually stored. */
  private static class RecordingRepository extends
      org.apache.oodt.cas.workflow.instrepo.AbstractPaginatibleInstanceRepository {

    private final List<String> reasons = new CopyOnWriteArrayList<String>();

    String reasonWritten() {
      return reasons.isEmpty() ? null : reasons.get(0);
    }

    private void note(WorkflowInstance wInst) {
      if (wInst != null && wInst.getWaitingOn() != null) {
        reasons.add(wInst.getWaitingOn());
      }
    }

    public void addWorkflowInstance(WorkflowInstance wInst) {
      note(wInst);
    }

    public void updateWorkflowInstance(WorkflowInstance wInst) {
      note(wInst);
    }

    public void removeWorkflowInstance(WorkflowInstance wInst) {
    }

    public WorkflowInstance getWorkflowInstanceById(String workflowInstId) {
      return null;
    }

    public List getWorkflowInstances() {
      return new Vector();
    }

    public List getWorkflowInstancesByStatus(String status) {
      return new Vector();
    }

    public int getNumWorkflowInstances() {
      return 0;
    }

    public int getNumWorkflowInstancesByStatus(String status) {
      return 0;
    }

    public boolean clearWorkflowInstances() throws InstanceRepositoryException {
      return true;
    }

    protected List paginateWorkflows(int pageNum, String status) {
      return new Vector();
    }
  }

  /** One processor, waiting on a condition it names. */
  private static class GatedProcessorQueue extends WorkflowProcessorQueue {

    private final QuerierAndRunnerUtils utils = new QuerierAndRunnerUtils();
    private WorkflowProcessor gated;

    GatedProcessorQueue() {
      super(null, null, null);
    }

    @Override
    public synchronized List<WorkflowProcessor> getProcessors() {
      List<WorkflowProcessor> processors = new Vector<WorkflowProcessor>();
      try {
        if (gated == null) {
          gated = new Gated(utils.getProcessor(2.0, "Loaded", "initial"),
              (org.apache.oodt.cas.workflow.engine.processor.TaskProcessor)
                  utils.getProcessor(3.0, "Loaded", "initial"));
        }
        processors.add(gated);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return processors;
    }
  }

  /**
   * Stands in for a processor held by an unmet condition. What is under test
   * is that the querier stores a reason once one appears, not how the reason
   * is worked out -- that is covered where the reason is computed.
   */
  private static class Gated extends org.apache.oodt.cas.workflow.engine.processor.TaskProcessor {

    private final org.apache.oodt.cas.workflow.engine.processor.TaskProcessor child;

    Gated(WorkflowProcessor delegate,
        org.apache.oodt.cas.workflow.engine.processor.TaskProcessor child) {
      super(delegate.getLifecycleManager(), delegate.getWorkflowInstance());
      this.child = child;
    }

    /**
     * Hands out a child, which is what puts the querier down the branch that
     * persists the child and not the parent.
     *
     * <p>
     * This is the case the reason would otherwise be lost in. A processor
     * with nothing to hand out falls to the querier's else branch, which
     * calls nextState and persists it, so its reason reached the repository
     * incidentally -- carried by a write that was made for another purpose.
     * A processor that does have work to hand out is never persisted there,
     * so nothing stored its reason at all.
     * </p>
     */
    @Override
    public synchronized List<org.apache.oodt.cas.workflow.engine.processor.TaskProcessor>
        getRunnableWorkflowProcessors() {
      List<org.apache.oodt.cas.workflow.engine.processor.TaskProcessor> runnable =
          new Vector<org.apache.oodt.cas.workflow.engine.processor.TaskProcessor>();
      runnable.add(child);
      return runnable;
    }

    @Override
    public boolean recordWaitingOn() {
      if (getWorkflowInstance().getWaitingOn() != null) {
        return false;
      }
      getWorkflowInstance().setWaitingOn("condition:urn:test:Gate");
      return true;
    }
  }
}
