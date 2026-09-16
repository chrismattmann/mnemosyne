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
package org.apache.oodt.cas.workflow.engine.processor;

//JDK imports
import java.util.Collections;
import java.util.List;
import java.util.Vector;

import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

/**
 * 
 * WorkflowProcessor which handles running sub-processors in sequence.
 * 
 * @author bfoster
 * @author mattmann
 * @version $Revision$
 */
public class SequentialProcessor extends WorkflowProcessor {

  public SequentialProcessor(WorkflowLifecycleManager lifecycleManager, WorkflowInstance instance) {
    super(lifecycleManager, instance);
  }

  @Override
  public List<WorkflowProcessor> getRunnableSubProcessors() {
    trackCurrentTask();
    WorkflowProcessor nextWP = this.getNext();
    if (nextWP != null) {
      return Collections.singletonList(nextWP);
    } else {
      return new Vector<WorkflowProcessor>();
    }
  }

  /**
   * Keep this workflow's currentTaskId on the task it is actually on.
   *
   * <p>
   * The engine sets it once, to the first task, when the instance is created
   * and never moves it. So a workflow of three tasks reported the first one
   * for its whole life: an IndexCorpus instance showed IndexMetadataJaccard
   * while the progress beside it came from the fg/bg stage two tasks later,
   * which reads as Jaccard taking an hour when Jaccard had finished in
   * seconds.
   * </p>
   *
   * <p>
   * W1 has never had this problem -- IterativeWorkflowProcessorThread sets it
   * per task as it walks them. This gives the queue-based engine the same
   * behaviour for the sequential case, where "the task it is on" is
   * unambiguous.
   * </p>
   *
   * <p>
   * Nothing is persisted here. The querier writes this instance back as it
   * polls, so the value it finds is the current one.
   * </p>
   */
  private void trackCurrentTask() {
    WorkflowProcessor current = this.getCurrent();
    if (current == null) {
      return;
    }
    String taskId = current.getWorkflowInstance().getCurrentTaskId();
    if (taskId != null
        && !taskId.equals(this.getWorkflowInstance().getCurrentTaskId())) {
      this.getWorkflowInstance().setCurrentTaskId(taskId);
    }
  }

  /**
   * The child this workflow is on: the first that is not done, whether it is
   * running or still waiting to be handed out.
   */
  private WorkflowProcessor getCurrent() {
    for (WorkflowProcessor wp : this.getSubProcessors()) {
      if (wp.getWorkflowInstance().getState().getCategory().getName()
             .equals("done")) {
        continue;
      }
      return wp;
    }
    return null;
  }

  @Override
  public void handleSubProcessorMetadata(WorkflowProcessor workflowProcessor) {
    // do nothing
  }

  /**
   * The next thing to hand out, which is nothing while something is running.
   *
   * <p>
   * This walked past a child that was executing and offered the one after
   * it. The intent was not to hand out something already running, but for a
   * sequential workflow the effect of skipping it is to start the next step
   * alongside the step it is supposed to follow. Nothing showed while only
   * tasks could be Executing, because a workflow between this and its tasks
   * reported Queued for as long as its tasks ran. Once workflows reported
   * that they were running, a pipeline whose first phase took a while ran
   * every phase at once: a crawl, and the partitioning of what the crawl had
   * not yet ingested, and the aggregate of what the partitioning had not yet
   * produced, all within two seconds.
   * </p>
   *
   * <p>
   * Reaching a child that is not done means it is either running, in which
   * case there is nothing to hand out until it finishes, or waiting to be
   * handed out, in which case it is the one. Either way the walk stops at the
   * first child that is not done -- which is what sequential means.
   * </p>
   */
  private WorkflowProcessor getNext() {
    WorkflowProcessor current = this.getCurrent();
    if (current == null) {
      return null;
    }
    if (current.getWorkflowInstance().getState().getName().equals("Executing")) {
      return null;
    }
    return current;
  }

}
