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

package org.apache.oodt.cas.resource.system;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.JobStatus;
import org.apache.oodt.cas.resource.structs.NameValueJobInput;
import org.apache.oodt.cas.resource.structs.ResourceNode;
import org.apache.oodt.cas.resource.structs.exceptions.JobRepositoryException;
import org.apache.oodt.cas.resource.structs.exceptions.MonitorException;
import org.apache.oodt.cas.resource.structs.exceptions.QueueManagerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Properties for {@link AvroRpcResourceManager} reached through
 * {@link AvroRpcResourceManagerClient} over a real Avro RPC connection.
 *
 * <p>Everything a Resource Manager deployment does — registering nodes,
 * declaring queues, submitting jobs — goes over this wire. The existing suite
 * sets one node's capacity and reads it back; these properties vary the node
 * identifiers, the addresses, the capacities, the queue names and the jobs, and
 * state what a caller is entitled to assume: that a node added is the node that
 * comes back, that a queue membership asked for is the membership reported,
 * that a job submitted keeps every field it was given, and that an operation
 * the server cannot perform arrives as a declared exception rather than as a
 * null or a hang.
 *
 * <p>The server is started fresh for each property on a port the operating
 * system chose, never a fixed one: ports in the 50000s collide with Docker on
 * developer machines and have taken this module's suite down before. Its node
 * and queue policy is copied into a temporary directory that is deleted
 * afterwards.
 *
 * <p>The scheduler's poll interval is pushed far beyond the life of the test.
 * Left at its configured twenty seconds it would wake mid-property, take
 * submitted jobs off the queue and try to run them on a batch stub that is not
 * there, so a property about queue depth would be racing a background thread.
 *
 * <p>One client is built per property and shared across that property's cases.
 * {@link AvroRpcResourceManagerClient} has no {@code close}, so every client
 * constructed leaks its transport; and its constructor retries a refused
 * connection thirty times a second apart, so a client must only ever be pointed
 * at a server that is already up.
 */
class AvroRpcResourceManagerPropertyTest {

  /** The node declared by the checked-in policy. */
  private static final String POLICY_NODE = "localhost";

  /** Queues declared by the checked-in policy for {@link #POLICY_NODE}. */
  private static final List<String> POLICY_QUEUES = List.of("high", "quick", "long");

  private Properties savedProperties;
  private Path policyDir;
  private int port;
  private AvroRpcResourceManager manager;
  private AvroRpcResourceManagerClient client;

  /** Identifiers: short, and free of anything a URL or an XML file minds. */
  private static Generator<String> names() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  /**
   * Identifiers that also exercise the wire's character handling: accents,
   * quotes and non-Latin scripts, which UTF-8 carries and a byte-oriented
   * transport would not.
   */
  private static Generator<String> awkwardNames() {
    return sampledFrom(List.of(
        "node-éè",
        "日本語",
        "with space",
        "quote'and\"quote",
        "line\nbreak",
        "ресурс"));
  }

  // ---------------------------------------------------------------- fixtures

  @BeforeEach
  void startServer() throws Exception {
    savedProperties = (Properties) System.getProperties().clone();

    policyDir = Files.createTempDirectory("resmgr-rpc-pbt");
    copyPolicy();

    Properties properties = new Properties(System.getProperties());
    try (InputStream in = getClass().getResourceAsStream("/test.resource.properties")) {
      properties.load(in);
    }
    properties.setProperty("org.apache.oodt.cas.resource.nodes.dirs",
        policyDir.toUri().toString());
    properties.setProperty("org.apache.oodt.cas.resource.nodetoqueues.dirs",
        policyDir.toUri().toString());
    /* keep the scheduler asleep for the whole run; see the class comment */
    properties.setProperty("org.apache.oodt.cas.resource.scheduler.wait.seconds", "86400");
    System.setProperties(properties);

    port = ephemeralPort();
    manager = new AvroRpcResourceManager(port);
    manager.startUp();
    client = new AvroRpcResourceManagerClient(new URL("http://localhost:" + port));
  }

  @AfterEach
  void stopServer() throws Exception {
    try {
      if (manager != null) {
        manager.shutdown();
      }
    } finally {
      client = null;
      manager = null;
      System.setProperties(savedProperties);
      deleteRecursively(policyDir);
    }
  }

  /**
   * Asks the operating system for a port nobody is using and hands it back.
   *
   * <p>A hard-coded port is how a suite ends up failing on a machine that
   * happens to run something else; the number this module used to carry is one
   * Docker Desktop publishes on.
   */
  private static int ephemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  private void copyPolicy() throws IOException {
    for (String file : List.of("nodes.xml", "node-to-queue-mapping.xml")) {
      try (InputStream in = getClass().getResourceAsStream("/policy/" + file)) {
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Files.writeString(policyDir.resolve(file), content, StandardCharsets.UTF_8);
      }
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.forEach(paths::add);
    }
    paths.sort(Comparator.reverseOrder());
    for (Path p : paths) {
      Files.deleteIfExists(p);
    }
  }

  /** A node identifier no other case in this property will have used. */
  private static String uniqueId(String base, int salt) {
    return base + "-" + salt + "-" + System.nanoTime();
  }

  // -------------------------------------------------------------- properties

  /**
   * A node added over RPC must come back over RPC with every field it was given:
   * its identifier, its address and its capacity.
   *
   * <p>The monitor is the only record of what hardware the scheduler may use. A
   * capacity that arrives smaller than it was set is a node the scheduler will
   * under-fill forever; an address that arrives changed is a node no job can
   * reach.
   */
  @HegelTest(testCases = 20)
  void anAddedNodeComesBackWholeById(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int capacity = tc.draw(integers().min(1).max(64), "capacity");
    int nodePort = tc.draw(integers().min(1).max(65535), "nodePort");
    String nodeId = uniqueId(base, 0);
    URL address = new URL("http://" + base.toLowerCase() + ".example:" + nodePort);

    client.addNode(new ResourceNode(nodeId, address, capacity));

    ResourceNode back = client.getNodeById(nodeId);
    assertNotNull(back, "the node just added is not known to the monitor");
    assertEquals(nodeId, back.getNodeId(), "the node id changed on the wire");
    assertEquals(address, back.getIpAddr(), "the node address changed on the wire");
    assertEquals(capacity, back.getCapacity(), "the node capacity changed on the wire");

    assertTrue(nodeIds(client.getNodes()).contains(nodeId),
        "the node just added is not in the node list");
  }

  /**
   * Setting a node's capacity over RPC must be what a later read reports, and
   * must not disturb any other node.
   */
  @HegelTest(testCases = 20)
  void settingCapacityIsWhatIsReadBack(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int first = tc.draw(integers().min(1).max(64), "first");
    int second = tc.draw(integers().min(1).max(64), "second");
    String nodeId = uniqueId(base, 1);

    client.addNode(new ResourceNode(nodeId, new URL("http://localhost:1"), first));
    int policyCapacityBefore = client.getNodeById(POLICY_NODE).getCapacity();

    client.setNodeCapacity(nodeId, second);
    assertEquals(second, client.getNodeById(nodeId).getCapacity(),
        "setNodeCapacity did not take");
    assertEquals(policyCapacityBefore, client.getNodeById(POLICY_NODE).getCapacity(),
        "setting one node's capacity changed another node's");
  }

  /**
   * Removing a node over RPC must remove that node and leave the rest alone.
   */
  @HegelTest(testCases = 15)
  void removingOneNodeLeavesTheRest(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(2).max(4), "count");
    int victim = tc.draw(integers().min(0).max(count - 1), "victim");

    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String nodeId = uniqueId(base, 20 + i);
      ids.add(nodeId);
      client.addNode(new ResourceNode(nodeId, new URL("http://localhost:" + (i + 1)), 4));
    }

    client.removeNode(ids.get(victim));

    List<String> remaining = nodeIds(client.getNodes());
    assertFalse(remaining.contains(ids.get(victim)), "the removed node is still listed");
    for (int i = 0; i < count; i++) {
      if (i != victim) {
        assertTrue(remaining.contains(ids.get(i)),
            "removing node " + victim + " also removed node " + i);
      }
    }
    assertTrue(remaining.contains(POLICY_NODE), "removing a node removed the policy node");
  }

  /**
   * A queue declared over RPC, and the nodes put into it, must be exactly what
   * the server reports back — from both directions, the queue's node list and
   * the node's queue list.
   */
  @HegelTest(testCases = 20)
  void queueMembershipIsSymmetric(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int nodeCount = tc.draw(integers().min(1).max(3), "nodeCount");
    String queueName = uniqueId(base, 40);

    client.addQueue(queueName);
    assertTrue(client.getQueues().contains(queueName), "the queue just added is not listed");

    List<String> ids = new ArrayList<>();
    for (int i = 0; i < nodeCount; i++) {
      String nodeId = uniqueId(base, 50 + i);
      ids.add(nodeId);
      client.addNode(new ResourceNode(nodeId, new URL("http://localhost:" + (i + 1)), 4));
      client.addNodeToQueue(nodeId, queueName);
    }

    List<String> inQueue = client.getNodesInQueue(queueName);
    assertEquals(ids.size(), inQueue.size(), "the queue holds a different number of nodes");
    assertTrue(inQueue.containsAll(ids), "a node added to the queue is not in it: " + inQueue);
    for (String nodeId : ids) {
      assertTrue(client.getQueuesWithNode(nodeId).contains(queueName),
          "the node does not report the queue it was added to");
    }

    client.removeNodeFromQueue(ids.get(0), queueName);
    assertFalse(client.getNodesInQueue(queueName).contains(ids.get(0)),
        "the node removed from the queue is still in it");
    assertFalse(client.getQueuesWithNode(ids.get(0)).contains(queueName),
        "the node still reports a queue it was removed from");

    client.removeQueue(queueName);
    assertFalse(client.getQueues().contains(queueName), "the removed queue is still listed");
  }

  /**
   * The policy the server was configured with must be what it serves: the node
   * it declares, and the queues that node belongs to.
   */
  @HegelTest(testCases = 10)
  void thePolicyIsServedAsItWasConfigured(TestCase tc) throws Exception {
    int unused = tc.draw(integers().min(0).max(3), "unused");
    tc.note("case " + unused);

    ResourceNode node = client.getNodeById(POLICY_NODE);
    assertNotNull(node, "the configured node is not served");
    assertEquals(8, node.getCapacity(), "the configured capacity is not served");

    assertTrue(client.getQueues().containsAll(POLICY_QUEUES),
        "the configured queues are not all served: " + client.getQueues());
    assertTrue(client.getQueuesWithNode(POLICY_NODE).containsAll(POLICY_QUEUES),
        "the configured node is not in all its configured queues");
    for (String queue : POLICY_QUEUES) {
      assertTrue(client.getNodesInQueue(queue).contains(POLICY_NODE),
          "queue " + queue + " does not hold the configured node");
    }
  }

  /**
   * Every field of a submitted {@link Job} must survive the wire.
   *
   * <p>The name, the two class names, the queue and the load are what the
   * scheduler and the batch manager act on: a load value that arrives wrong
   * over-commits a node, a class name that arrives wrong runs the wrong code.
   * The identifier and the status are the server's to assign, so the property
   * asks only that the server hand back an identifier and mark the job queued.
   */
  @HegelTest(testCases = 15)
  void aSubmittedJobKeepsItsFieldsOverTheWire(TestCase tc) throws Exception {
    String name = tc.draw(names(), "name");
    int load = tc.draw(integers().min(1).max(8), "load");
    String queueName = tc.draw(sampledFrom(POLICY_QUEUES), "queueName");

    Job job = new Job(null, name,
        "org.apache.oodt.cas.resource.examples.HelloWorldJob",
        NameValueJobInput.class.getCanonicalName(), queueName, load);

    NameValueJobInput input = new NameValueJobInput();
    input.setNameValuePair("user.name", name);

    String jobId = client.submitJob(job, input);
    assertNotNull(jobId, "submitJob returned no job id");

    Job back = client.getJobInfo(jobId);
    assertNotNull(back, "the job just submitted is not in the repository");
    assertEquals(jobId, back.getId(), "the job id changed on the wire");
    assertEquals(name, back.getName(), "the job name changed on the wire");
    assertEquals("org.apache.oodt.cas.resource.examples.HelloWorldJob",
        back.getJobInstanceClassName(), "the job instance class name changed on the wire");
    assertEquals(NameValueJobInput.class.getCanonicalName(), back.getJobInputClassName(),
        "the job input class name changed on the wire");
    assertEquals(queueName, back.getQueueName(), "the job queue name changed on the wire");
    assertEquals(Integer.valueOf(load), back.getLoadValue(),
        "the job load value changed on the wire");
    assertEquals(JobStatus.QUEUED, back.getStatus(),
        "a job that was queued is not marked queued");
  }

  /**
   * Submitting {@code n} jobs must raise the reported queue depth by exactly
   * {@code n}, and every one of them must be visible in the queued job listing.
   *
   * <p>Queue depth against capacity is how an operator decides whether the
   * system is keeping up. A depth that does not move with what was submitted is
   * a number nobody can act on.
   */
  @HegelTest(testCases = 12)
  void submittingNjobsRaisesTheQueueDepthByN(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(1).max(3), "count");
    String queueName = tc.draw(sampledFrom(POLICY_QUEUES), "queueName");

    int before = client.getJobQueueSize();
    assertTrue(client.getJobQueueCapacity() > before + count,
        "the fixture needs a queue with room in it");

    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Job job = new Job(null, base + i,
          "org.apache.oodt.cas.resource.examples.HelloWorldJob",
          NameValueJobInput.class.getCanonicalName(), queueName, 1);
      ids.add(client.submitJob(job, new NameValueJobInput()));
    }

    assertEquals(before + count, client.getJobQueueSize(),
        "the queue depth did not move by the number of jobs submitted");

    List<String> queued = queuedJobIds(client.getQueuedJobs());
    assertTrue(queued.containsAll(ids),
        "a submitted job is missing from the queued job listing: " + queued);
  }

  /**
   * The queued job listing must be made of {@link Job}s.
   *
   * <p>Every other list-returning call on this client — {@code getNodes} above
   * all — converts the wire records back into the domain objects the interface
   * promises. A caller that iterates {@code getQueuedJobs} and casts, which is
   * the only thing an untyped {@code List} lets it do, is entitled to the same.
   */
  @HegelTest(testCases = 10)
  void queuedJobsComeBackAsJobs(TestCase tc) throws Exception {
    String name = tc.draw(names(), "name");
    String queueName = tc.draw(sampledFrom(POLICY_QUEUES), "queueName");

    Job job = new Job(null, name,
        "org.apache.oodt.cas.resource.examples.HelloWorldJob",
        NameValueJobInput.class.getCanonicalName(), queueName, 1);
    client.submitJob(job, new NameValueJobInput());

    List<?> queued = client.getQueuedJobs();
    assertFalse(queued.isEmpty(), "nothing is queued after a submit");
    for (Object entry : queued) {
      assertTrue(entry instanceof Job,
          "the queued job listing holds " + entry.getClass().getName() + ", not a Job");
    }
  }

  /**
   * An operation the server cannot perform must arrive at the client as the
   * exception the interface declares — not as a null, and not as a call that
   * never returns.
   *
   * <p>Every one of these fails inside the server with an unchecked exception:
   * the monitor and the job repository answer a lookup miss with null and the
   * manager dereferences it. What a caller needs is only that the failure
   * crosses the wire and is named; a client that silently returns null cannot
   * tell "no such node" from "a node with nothing in it".
   */
  @HegelTest(testCases = 12)
  void aServerSideFailureArrivesAsADeclaredException(TestCase tc) {
    String missing = uniqueId(tc.draw(names(), "missing"), 90);

    assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
      List<String> violations = new ArrayList<>();
      check(violations, "getNodeById", MonitorException.class, () -> client.getNodeById(missing));
      check(violations, "getNodeLoad", MonitorException.class, () -> client.getNodeLoad(missing));
      check(violations, "getJobInfo", JobRepositoryException.class,
          () -> client.getJobInfo(missing));
      check(violations, "isJobComplete", JobRepositoryException.class,
          () -> client.isJobComplete(missing));
      check(violations, "getNodesInQueue", QueueManagerException.class,
          () -> client.getNodesInQueue(missing));
      assertTrue(violations.isEmpty(),
          "a server-side failure did not reach the client as the declared exception: "
              + String.join("; ", violations));
    });
  }

  /**
   * Records how one lookup-on-a-missing-thing behaved, rather than stopping at
   * the first that misbehaves, so the failure names every operation that is
   * wrong instead of only the first one tried.
   */
  private static void check(List<String> violations, String name,
      Class<? extends Exception> declared, ThrowingCall call) {
    try {
      Object result = call.call();
      violations.add(name + " returned " + result + " instead of raising "
          + declared.getSimpleName());
    } catch (Throwable t) {
      if (!declared.isInstance(t)) {
        violations.add(name + " raised " + t.getClass().getName() + " (" + t.getMessage()
            + ") instead of " + declared.getSimpleName());
      }
    }
  }

  /** A client call that is expected to fail. */
  private interface ThrowingCall {
    Object call() throws Exception;
  }

  /**
   * Node identifiers and queue names that carry accents, quotes, newlines or
   * non-Latin scripts must survive the wire unchanged.
   *
   * <p>Nothing in the Resource Manager restricts an identifier to ASCII, and a
   * deployment naming its nodes in its own language is entitled to have them
   * come back spelled the way they were sent.
   */
  @HegelTest(testCases = 12)
  void awkwardIdentifiersSurviveTheWire(TestCase tc) throws Exception {
    String nodeBase = tc.draw(awkwardNames(), "nodeBase");
    String queueBase = tc.draw(awkwardNames(), "queueBase");
    String nodeId = uniqueId(nodeBase, 70);
    String queueName = uniqueId(queueBase, 71);

    client.addNode(new ResourceNode(nodeId, new URL("http://localhost:1"), 3));
    client.addQueue(queueName);
    client.addNodeToQueue(nodeId, queueName);

    assertEquals(nodeId, client.getNodeById(nodeId).getNodeId(),
        "the node id changed on the wire");
    assertTrue(client.getNodesInQueue(queueName).contains(nodeId),
        "the node is not in the queue it was added to");
    assertTrue(client.getQueuesWithNode(nodeId).contains(queueName),
        "the queue name changed on the wire");
  }

  /**
   * The node report a client reads must describe the nodes the same client can
   * list: one line per node, naming it and its capacity.
   *
   * <p>This is the operator's view of the cluster. A report that omits a node
   * the monitor knows about is a node nobody will notice is idle.
   */
  @HegelTest(testCases = 12)
  void theNodeReportDescribesEveryKnownNode(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    List<Integer> capacities =
        tc.draw(lists(integers().min(1).max(16)).minSize(1).maxSize(3), "capacities");

    List<String> added = new ArrayList<>();
    for (int i = 0; i < capacities.size(); i++) {
      String nodeId = uniqueId(base, 80 + i);
      added.add(nodeId);
      client.addNode(
          new ResourceNode(nodeId, new URL("http://localhost:" + (i + 1)), capacities.get(i)));
    }

    String report = client.getNodeReport();
    assertNotNull(report, "the node report is null");
    for (int i = 0; i < added.size(); i++) {
      assertTrue(report.contains(added.get(i)),
          "the node report does not mention node " + added.get(i));
      assertTrue(report.contains("/" + capacities.get(i) + ")"),
          "the node report does not carry the capacity of " + added.get(i) + ": " + report);
    }
    assertEquals(client.getNodes().size(), report.split("\n").length,
        "the node report has a different number of lines than there are nodes");
  }

  /**
   * A live server must answer {@code isAlive}, and must keep answering it after
   * the operations above have run against it.
   */
  @HegelTest(testCases = 8)
  void aLiveServerSaysSo(TestCase tc) throws Exception {
    int calls = tc.draw(integers().min(1).max(4), "calls");
    for (int i = 0; i < calls; i++) {
      assertTrue(client.isAlive(), "a running resource manager reported itself not alive");
    }
  }

  // ------------------------------------------------------------------ helpers

  private static List<String> nodeIds(List<?> nodes) {
    List<String> ids = new ArrayList<>();
    for (Object node : nodes) {
      ids.add(((ResourceNode) node).getNodeId());
    }
    return ids;
  }

  /**
   * The identifiers in a queued-job listing, however the client chose to type
   * its elements. {@link #queuedJobsComeBackAsJobs} is the property about the
   * type; this reads the identifier either way so the counting property is not
   * a second copy of it.
   */
  private static List<String> queuedJobIds(List<?> queued) {
    List<String> ids = new ArrayList<>();
    for (Object entry : queued) {
      if (entry instanceof Job) {
        ids.add(((Job) entry).getId());
      } else if (entry instanceof org.apache.oodt.cas.resource.structs.avrotypes.AvroJob) {
        ids.add(String.valueOf(
            ((org.apache.oodt.cas.resource.structs.avrotypes.AvroJob) entry).getId()));
      }
    }
    return ids;
  }
}
