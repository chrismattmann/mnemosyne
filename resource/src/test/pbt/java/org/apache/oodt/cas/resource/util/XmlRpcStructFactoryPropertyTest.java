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

package org.apache.oodt.cas.resource.util;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.ResourceNode;

/**
 * Properties of the XML-RPC wire encoding in {@link XmlRpcStructFactory}.
 *
 * <p>The class had no unit tests. Both directions of the encoding are stated
 * over the domain a caller can actually reach: a fully populated {@link Job} or
 * {@link ResourceNode}, which is what the resource manager puts on the wire.
 * Whatever goes out of one end must come back in at the other, otherwise a job
 * changes identity in transit.
 */
class XmlRpcStructFactoryPropertyTest {

  /** Names, ids and class names as they appear in a nodes/jobs definition. */
  private static Generator<String> word() {
    return text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");
  }

  private static Job drawJob(TestCase tc, String suffix) {
    return new Job(
        tc.draw(word(), "job.id" + suffix),
        tc.draw(word(), "job.name" + suffix),
        tc.draw(word(), "job.instanceClass" + suffix),
        tc.draw(word(), "job.inputClass" + suffix),
        tc.draw(word(), "job.queue" + suffix),
        tc.draw(integers().min(0).max(1000), "job.load" + suffix));
  }

  private static ResourceNode drawNode(TestCase tc, String suffix) {
    String host = tc.draw(word(), "node.host" + suffix);
    int port = tc.draw(integers().min(1).max(65535), "node.port" + suffix);
    try {
      return new ResourceNode(
          tc.draw(word(), "node.id" + suffix),
          new URL("http://" + host + ":" + port),
          tc.draw(integers().min(0).max(1000), "node.capacity" + suffix));
    } catch (MalformedURLException e) {
      throw new AssertionError(e);
    }
  }

  private static void assertSameJob(Job expected, Job actual) {
    assertEquals(expected.getId(), actual.getId(), "id");
    assertEquals(expected.getName(), actual.getName(), "name");
    assertEquals(
        expected.getJobInstanceClassName(), actual.getJobInstanceClassName(), "instance class");
    assertEquals(expected.getJobInputClassName(), actual.getJobInputClassName(), "input class");
    assertEquals(expected.getQueueName(), actual.getQueueName(), "queue");
    assertEquals(expected.getLoadValue(), actual.getLoadValue(), "load");
    assertEquals(expected.getStatus(), actual.getStatus(), "status");
  }

  private static void assertSameNode(ResourceNode expected, ResourceNode actual) {
    assertEquals(expected.getNodeId(), actual.getNodeId(), "node id");
    assertEquals(expected.getCapacity(), actual.getCapacity(), "capacity");
    assertEquals(
        expected.getIpAddr().toExternalForm(), actual.getIpAddr().toExternalForm(), "node url");
  }

  /**
   * A job survives the round trip to the wire and back with every field it
   * started with. The status is set explicitly because a job on the wire is
   * always one the resource manager has already given a status to.
   */
  @HegelTest
  void jobSurvivesTheWire(TestCase tc) {
    Job job = drawJob(tc, "");
    job.setStatus(tc.draw(word(), "job.status"));

    Hashtable<String, Object> onWire = XmlRpcStructFactory.getXmlRpcJob(job);
    Job returned = XmlRpcStructFactory.getJobFromXmlRpc(onWire);

    assertSameJob(job, returned);
  }

  /**
   * A list of jobs survives the wire in order and without loss: the report a
   * client renders must list the same jobs the server holds.
   */
  @HegelTest
  void jobListSurvivesTheWire(TestCase tc) {
    int count = tc.draw(integers().min(0).max(10), "count");
    List<Job> jobs = new Vector<>();
    for (int i = 0; i < count; i++) {
      Job job = drawJob(tc, "[" + i + "]");
      job.setStatus(tc.draw(word(), "job.status[" + i + "]"));
      jobs.add(job);
    }

    Vector onWire = XmlRpcStructFactory.getXmlRpcJobList(jobs);
    List returned = XmlRpcStructFactory.getJobListFromXmlRpc(onWire);

    assertEquals(jobs.size(), returned.size(), "job count changed in transit");
    for (int i = 0; i < jobs.size(); i++) {
      assertSameJob(jobs.get(i), (Job) returned.get(i));
    }
  }

  /**
   * A resource node survives the round trip. The capacity crosses the wire as
   * a string, so this is the property that catches a broken parse.
   */
  @HegelTest
  void resourceNodeSurvivesTheWire(TestCase tc) {
    ResourceNode node = drawNode(tc, "");

    Map<String, String> onWire = XmlRpcStructFactory.getXmlRpcResourceNode(node);
    ResourceNode returned = XmlRpcStructFactory.getResourceNodeFromXmlRpc(onWire);

    assertSameNode(node, returned);
  }

  /** A list of nodes survives the wire in order and without loss. */
  @HegelTest
  void resourceNodeListSurvivesTheWire(TestCase tc) {
    int count = tc.draw(integers().min(0).max(10), "count");
    List<ResourceNode> nodes = new Vector<>();
    for (int i = 0; i < count; i++) {
      nodes.add(drawNode(tc, "[" + i + "]"));
    }

    Vector onWire = XmlRpcStructFactory.getXmlRpcResourceNodeList(nodes);
    List returned = XmlRpcStructFactory.getResourceNodeListFromXmlRpc(onWire);

    assertEquals(nodes.size(), returned.size(), "node count changed in transit");
    for (int i = 0; i < nodes.size(); i++) {
      assertSameNode(nodes.get(i), (ResourceNode) returned.get(i));
    }
  }

  /**
   * Encoding a job twice produces the same struct: the wire form is a pure
   * function of the job, so two clients asking for the same job cannot be told
   * different things.
   */
  @HegelTest
  void encodingAJobIsDeterministic(TestCase tc) {
    Job job = drawJob(tc, "");
    job.setStatus(tc.draw(word(), "job.status"));

    assertEquals(XmlRpcStructFactory.getXmlRpcJob(job), XmlRpcStructFactory.getXmlRpcJob(job));
  }

  /**
   * An empty or absent collection encodes to an empty collection rather than
   * to null: callers iterate the result without checking.
   */
  @HegelTest
  void emptyCollectionsEncodeToEmptyCollections(TestCase tc) {
    // Drawn so the property is exercised against both an explicit empty list
    // and a null one, which is what a server with no jobs may hand over.
    boolean useNull = tc.draw(booleans(), "useNull");
    List<Job> jobs = useNull ? null : new Vector<>();
    List<ResourceNode> nodes = useNull ? null : new Vector<ResourceNode>();

    assertEquals(0, XmlRpcStructFactory.getXmlRpcJobList(jobs).size());
    assertEquals(0, XmlRpcStructFactory.getXmlRpcResourceNodeList(nodes).size());
    assertEquals(0, XmlRpcStructFactory.getJobListFromXmlRpc(null).size());
    assertEquals(0, XmlRpcStructFactory.getResourceNodeListFromXmlRpc(null).size());
    assertEquals(
        0, XmlRpcStructFactory.getJobListFromXmlRpc(new Vector<Map>()).size());
    assertEquals(
        0, XmlRpcStructFactory.getResourceNodeListFromXmlRpc(new Vector<Map>()).size());
  }
}
