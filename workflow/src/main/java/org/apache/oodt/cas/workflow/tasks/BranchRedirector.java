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

package org.apache.oodt.cas.workflow.tasks;

//JDK imports
import java.io.IOException;
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
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
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

  /** How many unreadable polls in a row before giving up. */
  public static final String MAX_READ_FAILURES = "MaxReadFailures";

  private static final long DEFAULT_MAX_READ_FAILURES = 5L;

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
    URL managerUrl;
    try {
      managerUrl = new URL(metadata.getMetadata(CoreMetKeys.WORKFLOW_MANAGER_URL));
    } catch (Exception badUrl) {
      throw new WorkflowTaskInstanceException("No usable workflow manager url: "
          + badUrl.getMessage());
    }

    Map<String, String> categories;
    Set<String> before;
    // The setup calls get their own short-lived client. The wait below opens
    // one per poll rather than holding this one open for the whole branch.
    try (WorkflowManagerClient wm = clientFor(managerUrl)) {
      categories = statusCategories(wm);
      before = categories.isEmpty() ? null : instanceIds(wm, eventName);
      wm.sendEvent(eventName, metadata);
    } catch (WorkflowTaskInstanceException e) {
      throw e;
    } catch (Exception e) {
      throw new WorkflowTaskInstanceException(e.getMessage());
    }

    if (before == null) {
      LOG.log(Level.WARNING, "This workflow manager does not report status "
          + "categories, so [" + eventName + "] is started without waiting "
          + "for it");
      return;
    }
    waitFor(eventName, managerUrl, before, categories, config);
  }

  private void waitFor(String eventName, URL managerUrl,
      Set<String> before, Map<String, String> categories,
      WorkflowTaskConfiguration config)
      throws WorkflowTaskInstanceException {
    long pollMillis = seconds(config, POLL_SECONDS, DEFAULT_POLL_SECONDS) * 1000L;
    long maxWait = seconds(config, MAX_WAIT_SECONDS, 0L) * 1000L;
    long maxFailures = seconds(config, MAX_READ_FAILURES, DEFAULT_MAX_READ_FAILURES);
    long startedAt = System.currentTimeMillis();
    long consecutiveFailures = 0L;

    while (true) {
      boolean started = false;
      boolean finished = true;
      // A client per poll, closed each time.
      //
      // One client used to be opened in run() and held for the whole branch,
      // which on a long ingest means a connection kept across many minutes of
      // two-second polls. A connection the server has since closed is handed
      // back by the pool and the next read fails with "Unexpected end of file
      // from server" -- which is what killed the 2026-11-03 DRAT run, 6m37s
      // after its ingest had finished. Connecting per poll costs one connect
      // every couple of seconds and removes the whole class of failure.
      try (WorkflowManagerClient wm = clientFor(managerUrl)) {
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
        consecutiveFailures = 0L;
      } catch (TransientReadException e) {
        // Retried, not fatal. The branch is very probably fine; it is this
        // read that was not.
        if (++consecutiveFailures > maxFailures) {
          throw new WorkflowTaskInstanceException("Could not read instances of ["
              + eventName + "] " + consecutiveFailures + " times in a row; "
              + "giving up. Last reason: " + e.getMessage());
        }
        LOG.log(Level.WARNING, "Could not read instances of [" + eventName
            + "] (attempt " + consecutiveFailures + " of " + (maxFailures + 1)
            + "); retrying in " + (pollMillis / 1000L) + "s: " + e.getMessage());
        started = false;
        finished = false;
      } catch (IOException closeFailed) {
        // Closing a client that is already broken is not a branch failure.
        LOG.log(Level.FINE, "Could not close the workflow manager client: "
            + closeFailed.getMessage());
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
   * The client this task talks to its own manager with.
   *
   * <p>Overridable so the polling can be exercised without a manager to talk
   * to, which is how the retry behaviour below is tested. The same seam exists
   * on RemoteDataTransferer for the same reason.</p>
   */
  WorkflowManagerClient clientFor(URL managerUrl)
      throws TransientReadException {
    try {
      return RpcCommunicationFactory.createClient(managerUrl);
    } catch (Exception e) {
      // A manager we could not reach this time is the same kind of problem as
      // a poll we could not read: retry it, do not fail the branch.
      throw new TransientReadException("Could not connect to the workflow "
          + "manager at [" + managerUrl + "]: " + e.getMessage(), e);
    }
  }

  /**
   * Instances of whatever the event starts. An event names its workflows, so
   * this is the set a caller has to watch to know the branch is over.
   */
  @SuppressWarnings("unchecked")
  private List<WorkflowInstance> instancesOf(WorkflowManagerClient wm,
      String eventName) throws TransientReadException {
    try {
      List<WorkflowInstance> matching = new java.util.Vector<WorkflowInstance>();
      // Paged, because this used to be getWorkflowInstances(): every instance
      // in the repository, in one response, re-fetched on every poll. That
      // scales with the size of the whole run rather than with the branch
      // being watched, and it is a poor thing to do every two seconds.
      WorkflowInstancePage page = wm.getFirstPage();
      while (page != null && page.getPageWorkflows() != null) {
        for (Object o : page.getPageWorkflows()) {
          WorkflowInstance inst = (WorkflowInstance) o;
          if (inst != null && inst.getWorkflow() != null
              && eventName.equals(inst.getWorkflow().getId())) {
            matching.add(inst);
          }
        }
        if (page.isLastPage()) {
          break;
        }
        WorkflowInstancePage next = wm.getNextPage(page);
        // A manager that keeps handing back the same page would otherwise
        // spin here forever.
        if (next == null || next.getPageNum() <= page.getPageNum()) {
          break;
        }
        page = next;
      }
      return matching;
    } catch (Exception e) {
      throw new TransientReadException("Unable to read instances of ["
          + eventName + "]: " + e.getMessage(), e);
    }
  }

  /**
   * A poll that could not be read.
   *
   * <p>Distinct from a branch that failed, because the two were conflated and
   * that is what did the damage: one unreadable poll threw away a DRAT ingest
   * of 3,503 files that had already completed successfully.</p>
   */
  static final class TransientReadException extends Exception {
    private static final long serialVersionUID = 1L;

    TransientReadException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private Set<String> instanceIds(WorkflowManagerClient wm, String eventName)
      throws TransientReadException {
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
