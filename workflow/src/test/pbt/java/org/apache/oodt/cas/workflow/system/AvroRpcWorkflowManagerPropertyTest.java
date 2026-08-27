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

package org.apache.oodt.cas.workflow.system;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.commons.util.DateConvert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Properties for {@link AvroRpcWorkflowManager} reached through
 * {@link AvroRpcWorkflowManagerClient} over a real Avro RPC connection.
 *
 * <p>Every Workflow Manager client — the CLI, the web components, the PCS
 * tools — reaches the manager through this wire and through nothing else. The
 * existing suite fires one event and counts the instances it produced; these
 * properties instead state what a caller is entitled to assume about the
 * round trip itself: that an instance written over RPC comes back with every
 * field it was given, that the paging figures the server reports are the ones
 * the client reads, that the policy the server was configured with is the
 * policy it serves, and that a failure inside the server is named at the
 * client rather than arriving as a transport error.
 *
 * <p>Nothing here starts a workflow running. Instances are written directly
 * through {@code updateWorkflowInstance}, which the instance repository treats
 * as an upsert, so a property is about the wire and the repository rather than
 * about the engine's scheduling — and so a property finishes in milliseconds
 * rather than waiting on a task.
 *
 * <p>The server is started fresh for each property on a port the operating
 * system chose. A fixed port is how a suite comes to fail on a machine that
 * runs something else; the 50000s in particular belong to Docker on developer
 * machines. The instance repository lives under a temporary directory that is
 * deleted afterwards, so one property cannot see another's instances.
 */
class AvroRpcWorkflowManagerPropertyTest {

  /** A workflow the checked-in example policy declares. */
  private static final String TEST_WORKFLOW_ID = "urn:oodt:testWorkflow";
  private static final String TEST_WORKFLOW_NAME = "testWorkflow";

  /** A task and a condition that policy declares. */
  private static final String HELLO_TASK_ID = "urn:oodt:HelloWorld";
  private static final String TRUE_CONDITION_ID = "urn:oodt:TrueCondition";

  /** The page size the configuration asks the instance repository for. */
  private static final int CONFIGURED_PAGE_SIZE = 20;

  private Properties savedProperties;
  private Path root;
  private int port;
  private AvroRpcWorkflowManager manager;
  private WorkflowManagerClient client;

  /** Short identifiers, free of anything Lucene's term handling would fold. */
  private static Generator<String> names() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  /** Metadata values, including characters a byte-oriented wire would lose. */
  private static Generator<String> awkwardValues() {
    return sampledFrom(List.of(
        "plain",
        "accented-éèü",
        "日本語のテキスト",
        "quote'and\"quote",
        "line\nbreak\ttab",
        "  leading and trailing  ",
        "very-long-" + "x".repeat(2000)));
  }

  private static Generator<Priority> priorities() {
    return sampledFrom(List.of(
        Priority.LOW, Priority.MEDIUM_LOW, Priority.MEDIUM,
        Priority.MEDIUM_HIGH, Priority.HIGH));
  }

  // ---------------------------------------------------------------- fixtures

  @BeforeEach
  void startServer() throws Exception {
    savedProperties = (Properties) System.getProperties().clone();

    root = Files.createTempDirectory("wmgr-rpc-pbt");

    Properties properties = new Properties(System.getProperties());
    try (InputStream in = getClass().getResourceAsStream("/workflow.properties")) {
      properties.load(in);
    }
    properties.setProperty("workflow.engine.instanceRep.factory",
        "org.apache.oodt.cas.workflow.instrepo.LuceneWorkflowInstanceRepositoryFactory");
    properties.setProperty("org.apache.oodt.cas.workflow.instanceRep.lucene.idxPath",
        root.resolve("repo").toString());
    properties.setProperty("org.apache.oodt.cas.workflow.instanceRep.pageSize",
        String.valueOf(CONFIGURED_PAGE_SIZE));
    properties.setProperty("workflow.repo.factory",
        "org.apache.oodt.cas.workflow.repository.XMLWorkflowRepositoryFactory");
    properties.setProperty("org.apache.oodt.cas.workflow.repo.dirs",
        "file://" + examplesDir());
    properties.setProperty("org.apache.oodt.cas.workflow.lifecycle.filePath",
        examplesDir() + File.separator + "workflow-lifecycle.xml");
    System.setProperties(properties);

    port = ephemeralPort();
    manager = new AvroRpcWorkflowManager(port);
    client = new AvroRpcWorkflowManagerClient(new URL("http://localhost:" + port));
  }

  @AfterEach
  void stopServer() throws Exception {
    try {
      if (client != null) {
        client.close();
      }
    } finally {
      try {
        if (manager != null) {
          manager.shutdown();
        }
      } finally {
        client = null;
        manager = null;
        System.setProperties(savedProperties);
        deleteRecursively(root);
      }
    }
  }

  private static String examplesDir() throws IOException {
    return new File("./src/main/resources/examples").getCanonicalPath();
  }

  /**
   * Asks the operating system for a port nobody is using and hands it back.
   *
   * <p>A hard-coded port is how a suite ends up failing on a machine that
   * happens to run something else.
   */
  private static int ephemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    try (var walk = Files.walk(dir)) {
      walk.forEach(paths::add);
    }
    paths.sort(Comparator.reverseOrder());
    for (Path p : paths) {
      Files.deleteIfExists(p);
    }
  }

  /** An instance id no other case in this property will have used. */
  private static String uniqueId(String base, int salt) {
    return base + "-" + salt + "-" + System.nanoTime();
  }

  /**
   * An instance carrying a value in every field the wire is supposed to move,
   * hung off a workflow the repository already knows about.
   */
  private WorkflowInstance instance(String id, String status, String currentTaskId,
      Priority priority, Metadata sharedContext, long startMillis) throws Exception {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(client.getWorkflowById(TEST_WORKFLOW_ID));
    inst.setId(id);
    inst.setStatus(status);
    inst.setCurrentTaskId(currentTaskId);
    inst.setStartDateTimeIsoStr(DateConvert.isoFormat(new Date(startMillis)));
    inst.setEndDateTimeIsoStr(DateConvert.isoFormat(new Date(startMillis + 1000L)));
    inst.setCurrentTaskStartDateTimeIsoStr(DateConvert.isoFormat(new Date(startMillis + 10L)));
    inst.setCurrentTaskEndDateTimeIsoStr(DateConvert.isoFormat(new Date(startMillis + 20L)));
    inst.setPriority(priority);
    inst.setSharedContext(sharedContext);
    return inst;
  }

  // -------------------------------------------------------------- properties

  /**
   * Every field of a {@link WorkflowInstance} written over RPC must come back
   * over RPC unchanged.
   *
   * <p>This is the whole contract of the instance repository as a remote
   * caller sees it. A status that arrives wrong misreports what the system is
   * doing; a start time that arrives wrong corrupts every duration computed
   * from it; a priority that arrives wrong reorders the queue.
   */
  @HegelTest(testCases = 20)
  void everyFieldOfAnInstanceSurvivesTheWire(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    String status = tc.draw(names(), "status");
    String currentTaskId = tc.draw(names(), "currentTaskId");
    Priority priority = tc.draw(priorities(), "priority");
    long startMillis = tc.draw(
        dev.hegel.Generators.longs().min(1_000_000_000_000L).max(2_000_000_000_000L),
        "startMillis");
    String id = uniqueId(base, 0);

    Metadata sharedContext = new Metadata();
    sharedContext.addMetadata("Origin", base);

    WorkflowInstance sent = instance(id, status, currentTaskId, priority, sharedContext,
        startMillis);
    /* the date assertions below mean nothing if the fixture itself holds no date */
    assertNotNull(sent.getStartDateTimeIsoStr(), "the fixture has no start time to send");
    assertNotNull(sent.getEndDateTimeIsoStr(), "the fixture has no end time to send");
    assertTrue(client.updateWorkflowInstance(sent), "updateWorkflowInstance reported failure");

    WorkflowInstance back = client.getWorkflowInstanceById(id);
    assertNotNull(back, "the instance just written is not readable back");
    assertEquals(id, back.getId(), "the instance id changed on the wire");
    assertEquals(status, back.getStatus(), "the instance status changed on the wire");
    assertEquals(currentTaskId, back.getCurrentTaskId(),
        "the current task id changed on the wire");
    assertEquals(sent.getStartDateTimeIsoStr(), back.getStartDateTimeIsoStr(),
        "the start time changed on the wire");
    assertEquals(sent.getEndDateTimeIsoStr(), back.getEndDateTimeIsoStr(),
        "the end time changed on the wire");
    assertEquals(priority, back.getPriority(), "the instance priority changed on the wire");
    assertNotNull(back.getWorkflow(), "the instance came back with no workflow");
    assertEquals(TEST_WORKFLOW_ID, back.getWorkflow().getId(),
        "the workflow id changed on the wire");
    assertEquals(TEST_WORKFLOW_NAME, back.getWorkflow().getName(),
        "the workflow name changed on the wire");
    assertEquals(List.of(base), back.getSharedContext().getAllMetadata("Origin"),
        "the shared context changed on the wire");
  }

  /**
   * A shared context carrying accents, quotes, newlines or a very long value
   * must survive the wire spelled the way it was sent, and must be readable
   * both from the instance and from
   * {@code getWorkflowInstanceMetadata}.
   *
   * <p>Shared context is where a workflow's inputs live. Anything the wire
   * silently rewrites here is a task run against the wrong parameters.
   */
  @HegelTest(testCases = 15)
  void awkwardSharedContextSurvivesTheWire(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    String key = tc.draw(names(), "key");
    List<String> values = tc.draw(lists(awkwardValues()).minSize(1).maxSize(3), "values");
    String id = uniqueId(base, 1);

    Metadata sharedContext = new Metadata();
    sharedContext.addMetadata(key, values);

    client.updateWorkflowInstance(
        instance(id, "STARTED", "task", Priority.MEDIUM, sharedContext, 1_500_000_000_000L));

    WorkflowInstance back = client.getWorkflowInstanceById(id);
    assertEquals(values, back.getSharedContext().getAllMetadata(key),
        "the shared context values changed on the wire");

    Metadata viaRpc = client.getWorkflowInstanceMetadata(id);
    assertNotNull(viaRpc, "getWorkflowInstanceMetadata returned nothing");
  }

  /**
   * A page of instances must report the page size the server paged with.
   *
   * <p>A client pages by asking for page {@code n} and looking at what came
   * back. Page size is the only thing that tells it how far {@code n} moves the
   * window; the total-page count alone cannot. A page that says its size is
   * {@code -1} — the value {@link WorkflowInstancePage} carries when nobody has
   * set one — leaves a caller unable to compute an offset for any purpose.
   */
  @HegelTest(testCases = 12)
  void aPageReportsThePageSizeItWasBuiltWith(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(1).max(3), "count");

    for (int i = 0; i < count; i++) {
      client.updateWorkflowInstance(instance(uniqueId(base, 10 + i), "STARTED", "task",
          Priority.MEDIUM, new Metadata(), 1_500_000_000_000L));
    }

    WorkflowInstancePage first = client.getFirstPage();
    assertNotNull(first, "getFirstPage returned nothing");
    assertEquals(CONFIGURED_PAGE_SIZE, first.getPageSize(),
        "the first page does not report the configured page size");

    WorkflowInstancePage paged = client.paginateWorkflowInstances(1);
    assertEquals(CONFIGURED_PAGE_SIZE, paged.getPageSize(),
        "a paged request does not report the configured page size");
  }

  /**
   * A page of instances must report a page number and a total-page count that
   * agree with what the server actually paged, and must not hold more
   * instances than a page holds.
   */
  @HegelTest(testCases = 12)
  void aPageIsInternallyConsistent(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(1).max(3), "count");

    for (int i = 0; i < count; i++) {
      client.updateWorkflowInstance(instance(uniqueId(base, 20 + i), "STARTED", "task",
          Priority.MEDIUM, new Metadata(), 1_500_000_000_000L));
    }

    WorkflowInstancePage first = client.getFirstPage();
    assertEquals(1, first.getPageNum(), "the first page is not numbered one");
    assertTrue(first.getTotalPages() >= 1,
        "a repository holding instances reports no pages at all");
    assertNotNull(first.getPageWorkflows(), "the first page holds no instance list");
    assertTrue(first.getPageWorkflows().size() <= CONFIGURED_PAGE_SIZE,
        "the first page holds more instances than a page holds: "
            + first.getPageWorkflows().size());

    WorkflowInstancePage last = client.getLastPage();
    assertEquals(first.getTotalPages(), last.getPageNum(),
        "the last page is not numbered as the last page");
  }

  /**
   * Writing {@code n} new instances must raise the reported instance count by
   * exactly {@code n}, and each must be listed under the status it was given.
   */
  @HegelTest(testCases = 12)
  void writingNinstancesRaisesTheCountByN(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(1).max(3), "count");
    String status = uniqueId(tc.draw(names(), "status"), 30);

    int before = client.getNumWorkflowInstances();
    int beforeByStatus = client.getNumWorkflowInstancesByStatus(status);
    assertEquals(0, beforeByStatus, "a status nobody has used already has instances");

    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String id = uniqueId(base, 40 + i);
      ids.add(id);
      client.updateWorkflowInstance(instance(id, status, "task", Priority.MEDIUM,
          new Metadata(), 1_500_000_000_000L));
    }

    assertEquals(before + count, client.getNumWorkflowInstances(),
        "getNumWorkflowInstances did not move by the number of instances written");
    assertEquals(count, client.getNumWorkflowInstancesByStatus(status),
        "getNumWorkflowInstancesByStatus did not move by the number written");

    List<String> byStatus = idsOf(client.getWorkflowInstancesByStatus(status));
    assertTrue(byStatus.containsAll(ids),
        "an instance written under this status is not listed under it: " + byStatus);
    assertTrue(idsOf(client.getWorkflowInstances()).containsAll(ids),
        "an instance written is missing from the full instance listing");
  }

  /**
   * Changing an instance's status over RPC must be what a later read reports,
   * and must not disturb any other field.
   */
  @HegelTest(testCases = 15)
  void changingStatusChangesOnlyTheStatus(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    String firstStatus = tc.draw(names(), "firstStatus");
    String secondStatus = tc.draw(names(), "secondStatus");
    String currentTaskId = tc.draw(names(), "currentTaskId");
    String id = uniqueId(base, 50);

    WorkflowInstance sent = instance(id, firstStatus, currentTaskId, Priority.HIGH,
        new Metadata(), 1_500_000_000_000L);
    client.updateWorkflowInstance(sent);

    assertTrue(client.updateWorkflowInstanceStatus(id, secondStatus),
        "updateWorkflowInstanceStatus reported failure");

    WorkflowInstance back = client.getWorkflowInstanceById(id);
    assertEquals(secondStatus, back.getStatus(), "the status was not updated");
    assertEquals(currentTaskId, back.getCurrentTaskId(),
        "updating the status changed the current task id");
    assertEquals(sent.getStartDateTimeIsoStr(), back.getStartDateTimeIsoStr(),
        "updating the status changed the start time");
    assertEquals(Priority.HIGH, back.getPriority(),
        "updating the status changed the priority");
  }

  /**
   * Setting a current task's start and end time over RPC must be what a later
   * read reports.
   *
   * <p>These two times are what {@code getWorkflowCurrentTaskWallClockMinutes}
   * is computed from, and what every progress display shows. The call the
   * client makes returns {@code true}, so a caller has no way to notice if the
   * value did not stick.
   *
   * <p>The instance carries a task identifier the workflow actually declares.
   * These are not plain fields: {@code WorkflowInstance} routes them to the
   * task named by {@code currentTaskId} and silently drops them when that task
   * cannot be found, so an instance whose current task is a name the workflow
   * has never heard of would make this property vacuous rather than true.
   */
  @HegelTest(testCases = 12)
  void currentTaskTimesAreWhatWasSet(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    long startMillis = tc.draw(
        dev.hegel.Generators.longs().min(1_000_000_000_000L).max(2_000_000_000_000L),
        "startMillis");
    int durationSeconds = tc.draw(integers().min(1).max(3600), "durationSeconds");
    String id = uniqueId(base, 60);

    WorkflowInstance sent = instance(id, "STARTED", HELLO_TASK_ID, Priority.MEDIUM,
        new Metadata(), 1_500_000_000_000L);
    assertNotNull(sent.getCurrentTaskStartDateTimeIsoStr(),
        "the fixture's current task carries no start time, so this property proves nothing");
    client.updateWorkflowInstance(sent);

    String start = DateConvert.isoFormat(new Date(startMillis));
    String end = DateConvert.isoFormat(new Date(startMillis + durationSeconds * 1000L));

    assertTrue(client.setWorkflowInstanceCurrentTaskStartDateTime(id, start),
        "setWorkflowInstanceCurrentTaskStartDateTime reported failure");
    assertTrue(client.setWorkflowInstanceCurrentTaskEndDateTime(id, end),
        "setWorkflowInstanceCurrentTaskEndDateTime reported failure");

    WorkflowInstance back = client.getWorkflowInstanceById(id);
    assertEquals(start, back.getCurrentTaskStartDateTimeIsoStr(),
        "the current task start time is not what was set");
    assertEquals(end, back.getCurrentTaskEndDateTimeIsoStr(),
        "the current task end time is not what was set");
  }

  /**
   * The policy the server was configured with must be what it serves: the
   * workflow, its tasks, a task's configuration and required fields, and the
   * conditions that task carries.
   *
   * <p>The client's view of policy is what a caller builds a dynamic workflow
   * from and what a UI renders. A task configuration that arrives empty is a
   * task nobody can see the parameters of.
   */
  @HegelTest(testCases = 10)
  void thePolicyIsServedAsItWasConfigured(TestCase tc) throws Exception {
    int unused = tc.draw(integers().min(0).max(3), "unused");
    tc.note("case " + unused);

    Workflow workflow = client.getWorkflowById(TEST_WORKFLOW_ID);
    assertNotNull(workflow, "the configured workflow is not served");
    assertEquals(TEST_WORKFLOW_NAME, workflow.getName(), "the workflow name changed on the wire");
    assertEquals(2, workflow.getTasks().size(),
        "the workflow does not carry the tasks policy gave it");

    WorkflowTask task = client.getTaskById(HELLO_TASK_ID);
    assertNotNull(task, "the configured task is not served");
    assertEquals(HELLO_TASK_ID, task.getTaskId(), "the task id changed on the wire");
    assertEquals("Hello World", task.getTaskName(), "the task name changed on the wire");
    assertEquals("org.apache.oodt.cas.workflow.examples.HelloWorld",
        task.getTaskInstanceClassName(), "the task class name changed on the wire");
    assertNotNull(task.getTaskConfig(), "the task came back with no configuration");
    assertEquals("Chris", task.getTaskConfig().getProperty("Person"),
        "the task configuration changed on the wire");
    assertEquals(1, task.getPreConditions().size(),
        "the task does not carry the condition policy gave it");
    assertEquals(TRUE_CONDITION_ID, task.getPreConditions().get(0).getConditionId(),
        "the task's condition id changed on the wire");

    WorkflowCondition condition = client.getConditionById(TRUE_CONDITION_ID);
    assertNotNull(condition, "the configured condition is not served");
    assertEquals("True Condition", condition.getConditionName(),
        "the condition name changed on the wire");
    assertEquals("org.apache.oodt.cas.workflow.examples.TrueCondition",
        condition.getConditionInstanceClassName(),
        "the condition class name changed on the wire");
  }

  /**
   * An event the policy declares must be registered, and the workflows it
   * names must be the workflows the server serves for it.
   */
  @HegelTest(testCases = 10)
  void anEventServesTheWorkflowsPolicyGaveIt(TestCase tc) throws Exception {
    String event = tc.draw(sampledFrom(List.of("test", "long", "metUpdate", "conditions")),
        "event");

    List<?> registered = client.getRegisteredEvents();
    assertNotNull(registered, "no events are registered");
    assertTrue(registered.contains(event),
        "a declared event is not registered: " + event + " in " + registered);

    List<?> workflows = client.getWorkflowsByEvent(event);
    assertNotNull(workflows, "no workflows came back for event " + event);
    assertTrue(!workflows.isEmpty(), "a declared event serves no workflows: " + event);
    for (Object entry : workflows) {
      Workflow w = (Workflow) entry;
      assertNotNull(w.getId(), "a workflow served for " + event + " has no id");
      assertEquals(w.getName(), client.getWorkflowById(w.getId()).getName(),
          "the workflow served for an event disagrees with the workflow served by id");
    }
  }

  /**
   * The full workflow listing must agree with what {@code getWorkflowById}
   * serves, name for name.
   */
  @HegelTest(testCases = 10)
  void theWorkflowListingAgreesWithLookupById(TestCase tc) throws Exception {
    int unused = tc.draw(integers().min(0).max(3), "unused");
    tc.note("case " + unused);

    List<?> all = client.getWorkflows();
    assertNotNull(all, "the workflow listing is null");
    assertTrue(!all.isEmpty(), "a configured repository serves no workflows");
    for (Object entry : all) {
      Workflow w = (Workflow) entry;
      Workflow byId = client.getWorkflowById(w.getId());
      assertNotNull(byId, "a listed workflow cannot be looked up by id: " + w.getId());
      assertEquals(w.getName(), byId.getName(),
          "the listing and the lookup disagree about workflow " + w.getId());
      assertEquals(w.getTasks().size(), byId.getTasks().size(),
          "the listing and the lookup disagree about the tasks of " + w.getId());
    }
  }

  /**
   * A request the server cannot satisfy must arrive at the client named — as
   * an exception carrying the server's reason, or as a defined empty answer —
   * never as a transport-level error and never as a call that does not return.
   *
   * <p>The Avro protocol this manager speaks declares no error types, so the
   * only error a response can carry is a string. Wherever the manager raises
   * {@code new AvroRemoteException(someException)} it hands the responder a
   * datum it cannot write, and what reaches the caller is a serialization
   * complaint with the real reason buried in its text.
   */
  @HegelTest(testCases = 12)
  void aRequestTheServerCannotSatisfyIsNamedAtTheClient(TestCase tc) {
    String missing = uniqueId(tc.draw(names(), "missing"), 70);

    assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
      List<String> violations = new ArrayList<>();

      try {
        Metadata met = new Metadata();
        met.addMetadata("Origin", missing);
        client.executeDynamicWorkflow(List.of(missing), met);
        violations.add("executeDynamicWorkflow accepted an undefined task id");
      } catch (Exception e) {
        if (e instanceof org.apache.avro.AvroRuntimeException) {
          violations.add("executeDynamicWorkflow raised a transport error, "
              + e.getMessage() + ", rather than the repository failure it stands for");
        } else if (!message(e).contains(missing)) {
          violations.add("executeDynamicWorkflow raised " + e.getClass().getName()
              + " (" + e.getMessage() + ") which does not name the undefined task");
        }
      }

      try {
        WorkflowInstance unknown = client.getWorkflowInstanceById(missing);
        if (unknown != null && missing.equals(unknown.getId())) {
          violations.add("getWorkflowInstanceById invented an instance for an unknown id");
        }
      } catch (Exception e) {
        violations.add("getWorkflowInstanceById on an unknown id raised "
            + e.getClass().getName() + " (" + e.getMessage() + ")");
      }

      assertTrue(violations.isEmpty(),
          "a request the server could not satisfy was not named at the client: "
              + String.join("; ", violations));
    });
  }

  /**
   * A live server must answer {@code isAlive}, and must keep answering it.
   */
  @HegelTest(testCases = 8)
  void aLiveServerSaysSo(TestCase tc) {
    int calls = tc.draw(integers().min(1).max(4), "calls");
    for (int i = 0; i < calls; i++) {
      assertTrue(client.isAlive(), "a running workflow manager reported itself not alive");
    }
  }

  // ------------------------------------------------------------------ helpers

  private static List<String> idsOf(List<?> instances) {
    List<String> ids = new ArrayList<>();
    if (instances != null) {
      for (Object o : instances) {
        ids.add(((WorkflowInstance) o).getId());
      }
    }
    return ids;
  }

  /** The whole message of a throwable and everything that caused it. */
  private static String message(Throwable t) {
    StringBuilder text = new StringBuilder();
    for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
      text.append(cursor.getMessage()).append(' ');
      if (cursor.getCause() == cursor) {
        break;
      }
    }
    return text.toString();
  }
}
