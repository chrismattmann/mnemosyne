/**
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

package org.apache.oodt.cas.workflow.examples;

//JDK imports
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.metadata.CoreMetKeys;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.WorkflowTaskInstanceException;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;
import org.apache.oodt.cas.workflow.system.rpc.RpcCommunicationFactory;

/**
 * 
 * Redirects from an existing {@link org.apache.oodt.cas.workflow.structs.WorkflowInstance} by sending a specified
 * event specified by the task configuration parameter named
 * <code>eventName</code>.
 * 
 * @author mattmann
 * @version $Revision$
 * 
 */
public class BranchRedirector implements WorkflowTaskInstance {

  private static final Logger LOG = Logger
      .getLogger(BranchRedirector.class.getName());

  /** How often to look, in seconds, while waiting for the branch. */
  public static final String POLL_SECONDS = "PollSeconds";

  /** How long to wait before giving up, in seconds. Zero waits forever. */
  public static final String MAX_WAIT_SECONDS = "MaxWaitSeconds";

  private static final long DEFAULT_POLL_SECONDS = 2L;

  public BranchRedirector() {
  }

  /**
   * Start the workflow this redirector names, and wait for it to finish.
   *
   * <p>
   * Waiting is the point. A workflow whose children are workflows is built as
   * a list of these, one per child, and a sequential parent hands them out in
   * order -- so what "sequential" means for nested workflows is decided
   * entirely here. This used to send the event and return, which made every
   * child of a sequential parent start at once: a pipeline of four phases ran
   * all four within two seconds, and each phase read what the phase before it
   * had not yet produced. Ordering had to be recovered afterwards with
   * conditions, phase by phase, which is a gate standing in for a sequence.
   * </p>
   *
   * <p>
   * Which statuses mean finished is asked of the manager rather than assumed,
   * since a deployment's lifecycle decides them. A manager too old to say
   * leaves this unable to tell, and it returns rather than waiting for
   * something it cannot recognise -- the behaviour it has always had.
   * </p>
   */
  @Override
  public void run(Metadata metadata, WorkflowTaskConfiguration config)
      throws WorkflowTaskInstanceException {

    String eventName = config.getProperty("eventName");
    try (WorkflowManagerClient wm = RpcCommunicationFactory
            .createClient(new URL(metadata
                .getMetadata(CoreMetKeys.WORKFLOW_MANAGER_URL)))) {

      Map<String, String> categories = statusCategories(wm);
      Set<String> before = categories.isEmpty()
          ? null : instanceIds(wm, eventName);

      wm.sendEvent(eventName, metadata);

      if (before == null) {
        LOG.log(Level.WARNING, "This workflow manager does not report status "
            + "categories, so [" + eventName + "] is started without waiting "
            + "for it");
        return;
      }
      waitFor(wm, eventName, before, categories, config);
    } catch (WorkflowTaskInstanceException e) {
      throw e;
    } catch (Exception e) {
      throw new WorkflowTaskInstanceException(e.getMessage());
    }
  }

  private void waitFor(WorkflowManagerClient wm, String eventName,
      Set<String> before, Map<String, String> categories,
      WorkflowTaskConfiguration config)
      throws WorkflowTaskInstanceException {
    long pollMillis = seconds(config, POLL_SECONDS, DEFAULT_POLL_SECONDS) * 1000L;
    long maxWait = seconds(config, MAX_WAIT_SECONDS, 0L) * 1000L;
    long startedAt = System.currentTimeMillis();

    while (true) {
      boolean started = false;
      boolean finished = true;
      for (WorkflowInstance inst : instancesOf(wm, eventName)) {
        if (before.contains(inst.getId())) {
          continue;
        }
        started = true;
        String status = inst.getStatus();
        String category = status == null ? null : categories.get(status);
        if (!"done".equals(category)) {
          finished = false;
        }
      }
      if (started && finished) {
        return;
      }
      if (maxWait > 0 && System.currentTimeMillis() - startedAt > maxWait) {
        throw new WorkflowTaskInstanceException("Gave up waiting for ["
            + eventName + "] after " + (maxWait / 1000L) + "s");
      }
      try {
        Thread.sleep(pollMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new WorkflowTaskInstanceException("Interrupted while waiting for ["
            + eventName + "]");
      }
    }
  }

  /**
   * Instances of whatever the event starts. An event names its workflows, so
   * this is the set a caller has to watch to know the branch is over.
   */
  @SuppressWarnings("unchecked")
  private List<WorkflowInstance> instancesOf(WorkflowManagerClient wm,
      String eventName) throws WorkflowTaskInstanceException {
    try {
      List<WorkflowInstance> matching = new java.util.Vector<WorkflowInstance>();
      for (Object o : wm.getWorkflowInstances()) {
        WorkflowInstance inst = (WorkflowInstance) o;
        if (inst.getWorkflow() != null
            && eventName.equals(inst.getWorkflow().getId())) {
          matching.add(inst);
        }
      }
      return matching;
    } catch (Exception e) {
      throw new WorkflowTaskInstanceException("Unable to read instances of ["
          + eventName + "]: " + e.getMessage());
    }
  }

  private Set<String> instanceIds(WorkflowManagerClient wm, String eventName)
      throws WorkflowTaskInstanceException {
    Set<String> ids = new HashSet<String>();
    for (WorkflowInstance inst : instancesOf(wm, eventName)) {
      ids.add(inst.getId());
    }
    return ids;
  }

  private Map<String, String> statusCategories(WorkflowManagerClient wm) {
    try {
      Map<String, String> categories = wm.getWorkflowStatusCategories();
      return categories == null
          ? new java.util.HashMap<String, String>() : categories;
    } catch (Exception tooOldToAsk) {
      return new java.util.HashMap<String, String>();
    }
  }

  private long seconds(WorkflowTaskConfiguration config, String key,
      long fallback) {
    String value = config == null ? null : config.getProperty(key);
    if (value == null || value.trim().length() == 0) {
      return fallback;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
