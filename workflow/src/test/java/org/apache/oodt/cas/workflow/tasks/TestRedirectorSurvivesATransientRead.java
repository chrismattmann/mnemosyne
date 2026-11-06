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
package org.apache.oodt.cas.workflow.tasks;

//JDK imports
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.system.MockWorkflowManagerClient;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;
import org.apache.oodt.cas.workflow.metadata.CoreMetKeys;

//JUnit imports
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A poll that could not be read is not a branch that failed.
 *
 * <p>On 2026-11-03 a DRAT audit ingested 3,503 files successfully and was then
 * thrown away. The parent workflow's redirector, waiting for the ingest branch,
 * failed with</p>
 *
 * <pre>
 *   SEVERE: Unable to read instances of [urn:drat:IngestPhase]:
 *           java.net.SocketException: Unexpected end of file from server
 *   WARNING: Exception executing task: [Redirector Task]
 * </pre>
 *
 * <p>6m37s after the ingest's last task completed. MIME partitioning never
 * began, two aggregation preconditions waited forever, and a run that had done
 * all its work was marked failed.</p>
 *
 * <p>Two faults produced that. The redirector held <em>one</em> client open for
 * the whole branch and polled it every two seconds, so a connection the server
 * had since closed got handed back by the pool; and any read failure was
 * reported as the branch failing. Either alone is survivable. Together they
 * turn a transient socket close into lost work.</p>
 */
public class TestRedirectorSurvivesATransientRead {

  private static final String EVENT = "urn:drat:IngestPhase";
  private static final String MANAGER = "http://localhost:9000";

  /**
   * A client whose reads can be made to fail on command.
   *
   * <p>Scripted rather than random: each entry is one read, either a failure or
   * the state to report, so a test says exactly what sequence it is asserting
   * about.</p>
   */
  private static final class ScriptedClient extends MockWorkflowManagerClient {

    private final List<Object> script;
    private int reads;

    private ScriptedClient(List<Object> script) throws MalformedURLException {
      super();
      this.script = script;
    }

    @Override
    public Map<String, String> getWorkflowStatusCategories() {
      Map<String, String> categories = new HashMap<String, String>();
      categories.put("FINISHED", "done");
      categories.put("STARTED", "running");
      return categories;
    }

    @Override
    public boolean sendEvent(String eventName, Metadata metadata) {
      return true;
    }

    @Override
    public WorkflowInstancePage getFirstPage() throws Exception {
      Object next = reads < script.size()
          ? script.get(reads) : script.get(script.size() - 1);
      reads++;
      if (next instanceof Exception) {
        throw (Exception) next;
      }
      return (WorkflowInstancePage) next;
    }

    private int reads() {
      return reads;
    }
  }

  private static WorkflowInstancePage pageOf(String... statuses) {
    WorkflowInstancePage page = new WorkflowInstancePage();
    page.setPageNum(1);
    page.setTotalPages(1);
    page.setPageSize(statuses.length);
    List<WorkflowInstance> instances = new Vector<WorkflowInstance>();
    int n = 0;
    for (String status : statuses) {
      Workflow workflow = new Workflow();
      workflow.setId(EVENT);
      WorkflowInstance inst = new WorkflowInstance();
      inst.setWorkflow(workflow);
      inst.setId("inst-" + (++n));
      inst.setStatus(status);
      instances.add(inst);
    }
    page.setPageWorkflows(instances);
    return page;
  }

  /** An empty page, which is what "the branch has not started" looks like. */
  private static WorkflowInstancePage emptyPage() {
    WorkflowInstancePage page = new WorkflowInstancePage();
    page.setPageNum(1);
    page.setTotalPages(1);
    page.setPageWorkflows(new Vector<WorkflowInstance>());
    return page;
  }

  private static final class RedirectorUnder extends BranchRedirector {
    private final ScriptedClient client;
    private int clientsRequested;

    private RedirectorUnder(ScriptedClient client) {
      this.client = client;
    }

    @Override
    WorkflowManagerClient clientFor(URL managerUrl) {
      clientsRequested++;
      return client;
    }
  }

  private static Metadata metadata() {
    Metadata met = new Metadata();
    met.addMetadata(CoreMetKeys.WORKFLOW_MANAGER_URL, MANAGER);
    return met;
  }

  private static WorkflowTaskConfiguration config() {
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("eventName", EVENT);
    // Keep the test quick; the production default is 2s.
    config.addConfigProperty(BranchRedirector.POLL_SECONDS, "0");
    return config;
  }

  // ------------------------------------------------- the incident, as a test ---

  /**
   * The exact failure, reproduced: one bad read in the middle of a branch that
   * then reports itself finished.
   */
  @Test
  public void testOneUnreadablePollDoesNotFailACompletedBranch()
      throws Exception {
    List<Object> script = new ArrayList<Object>();
    script.add(emptyPage());                                  // the "before" read
    script.add(new SocketException("Unexpected end of file from server"));
    script.add(pageOf("FINISHED"));                           // it was fine all along

    ScriptedClient client = new ScriptedClient(script);
    RedirectorUnder redirector = new RedirectorUnder(client);

    redirector.run(metadata(), config());

    assertEquals("the failed read should have been retried, not fatal",
        3, client.reads());
  }

  @Test
  public void testSeveralConsecutiveBadReadsAreStillSurvivable()
      throws Exception {
    List<Object> script = new ArrayList<Object>();
    script.add(emptyPage());
    for (int i = 0; i < 4; i++) {
      script.add(new SocketException("Unexpected end of file from server"));
    }
    script.add(pageOf("FINISHED"));

    ScriptedClient client = new ScriptedClient(script);
    new RedirectorUnder(client).run(metadata(), config());

    assertEquals(6, client.reads());
  }

  /**
   * But not forever. A manager that is actually gone has to be reported, or a
   * dead run waits for ever -- which is the other half of what went wrong:
   * two aggregation preconditions polled indefinitely.
   */
  @Test
  public void testAPersistentlyUnreadableManagerIsStillReported()
      throws Exception {
    List<Object> script = new ArrayList<Object>();
    script.add(emptyPage());
    script.add(new SocketException("Unexpected end of file from server"));
    // and never recovers: the scripted client repeats its last entry

    ScriptedClient client = new ScriptedClient(script);
    WorkflowTaskConfiguration config = config();
    config.addConfigProperty(BranchRedirector.MAX_READ_FAILURES, "3");

    try {
      new RedirectorUnder(client).run(metadata(), config);
      fail("a manager that never answers should not be waited on forever");
    } catch (Exception expected) {
      assertTrue("the message should say it gave up reading: "
          + expected.getMessage(),
          expected.getMessage().contains("Could not read instances"));
    }
  }

  // ------------------------------------------------------- the design fixes ---

  /**
   * A client per poll, not one held across the branch.
   *
   * <p>This is the half that stops the stale connection existing at all: the
   * original opened one client in run() and kept it for the whole wait.</p>
   */
  @Test
  public void testAClientIsTakenPerPollRatherThanHeldOpen() throws Exception {
    List<Object> script = new ArrayList<Object>();
    script.add(emptyPage());
    script.add(pageOf("STARTED"));     // still running: forces another poll
    script.add(pageOf("FINISHED"));

    ScriptedClient client = new ScriptedClient(script);
    RedirectorUnder redirector = new RedirectorUnder(client);
    redirector.run(metadata(), config());

    assertTrue("a client should be taken for the setup and for each poll, "
        + "got " + redirector.clientsRequested,
        redirector.clientsRequested >= 3);
  }

  /** A branch still has to be waited for, not merely started. */
  @Test
  public void testItWaitsUntilTheBranchIsActuallyDone() throws Exception {
    List<Object> script = new ArrayList<Object>();
    script.add(emptyPage());
    script.add(pageOf("STARTED"));
    script.add(pageOf("STARTED", "STARTED"));
    script.add(pageOf("FINISHED", "FINISHED"));

    ScriptedClient client = new ScriptedClient(script);
    new RedirectorUnder(client).run(metadata(), config());

    assertEquals("it should have polled until every instance was done",
        4, client.reads());
  }

  /**
   * The read is paged.
   *
   * <p>It used to call getWorkflowInstances(), every instance in the
   * repository, on every poll -- which scales with the whole run rather than
   * with the branch being watched.</p>
   */
  @Test
  public void testTheReadIsPagedNotTheWholeRepository() throws Exception {
    // Located from the class rather than a written-out path. The first
    // version hardcoded ".../examples/BranchRedirector.java" and went on
    // passing after the class moved here, because that path still holds the
    // deprecated shim -- which pages nothing. The assertion was reading the
    // wrong file and reporting confidently about it.
    Class<?> subject = BranchRedirector.class;
    java.nio.file.Path file = java.nio.file.Paths.get("src/main/java")
        .resolve(subject.getName().replace('.', '/') + ".java");
    assertTrue("cannot find the source of " + subject.getName() + " at "
        + file, java.nio.file.Files.isRegularFile(file));
    String source = new String(
        java.nio.file.Files.readAllBytes(file), "UTF-8");
    StringBuilder code = new StringBuilder();
    for (String line : source.split("\n")) {
      if (!line.trim().startsWith("//") && !line.trim().startsWith("*")) {
        code.append(line).append('\n');
      }
    }
    assertTrue("the redirector should page rather than fetch every instance",
        code.indexOf("getWorkflowInstances()") < 0);
    assertTrue("it should use the paged api",
        code.indexOf("getFirstPage()") > 0);
  }
}
