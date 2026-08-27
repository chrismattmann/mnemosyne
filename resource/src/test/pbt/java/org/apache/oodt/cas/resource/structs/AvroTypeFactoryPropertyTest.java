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

package org.apache.oodt.cas.resource.structs;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.maps;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.oodt.cas.resource.structs.avrotypes.AvroResourceNode;

/**
 * Round-trip properties of the Avro wire mapping in {@link AvroTypeFactory}.
 *
 * <p>The existing unit test covers two hand-written examples and leaves the
 * job mapping disabled. These properties state the same thing the examples
 * were reaching for, over every job and node the resource manager can build:
 * the Avro form is only useful if it is reversible, because the far side of an
 * RPC reconstructs the job from it and then runs it.
 */
class AvroTypeFactoryPropertyTest {

  private static Generator<String> word() {
    return text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");
  }

  private static Job drawJob(TestCase tc, String suffix) {
    Job job =
        new Job(
            tc.draw(word(), "job.id" + suffix),
            tc.draw(word(), "job.name" + suffix),
            tc.draw(word(), "job.instanceClass" + suffix),
            tc.draw(word(), "job.inputClass" + suffix),
            tc.draw(word(), "job.queue" + suffix),
            tc.draw(integers().min(0).max(1000), "job.load" + suffix));
    job.setStatus(tc.draw(word(), "job.status" + suffix));
    return job;
  }

  private static ResourceNode drawNode(TestCase tc, String suffix) {
    try {
      return new ResourceNode(
          tc.draw(word(), "node.id" + suffix),
          new URL(
              "http://"
                  + tc.draw(word(), "node.host" + suffix)
                  + ":"
                  + tc.draw(integers().min(1).max(65535), "node.port" + suffix)),
          tc.draw(integers().min(0).max(1000), "node.capacity" + suffix));
    } catch (MalformedURLException e) {
      throw new AssertionError(e);
    }
  }

  private static void assertSameNode(ResourceNode expected, ResourceNode actual) {
    assertEquals(expected.getNodeId(), actual.getNodeId(), "node id");
    assertEquals(expected.getCapacity(), actual.getCapacity(), "capacity");
    assertEquals(
        expected.getIpAddr().toExternalForm(), actual.getIpAddr().toExternalForm(), "node url");
  }

  /** A job put into Avro form and taken out again is the job it was. */
  @HegelTest
  void aJobRoundTripsThroughAvro(TestCase tc) {
    Job job = drawJob(tc, "");

    Job returned = AvroTypeFactory.getJob(AvroTypeFactory.getAvroJob(job));

    assertEquals(job.getId(), returned.getId(), "id");
    assertEquals(job.getName(), returned.getName(), "name");
    assertEquals(
        job.getJobInstanceClassName(), returned.getJobInstanceClassName(), "instance class");
    assertEquals(job.getJobInputClassName(), returned.getJobInputClassName(), "input class");
    assertEquals(job.getQueueName(), returned.getQueueName(), "queue");
    assertEquals(job.getLoadValue(), returned.getLoadValue(), "load");
    assertEquals(job.getStatus(), returned.getStatus(), "status");
  }

  /** A resource node put into Avro form and taken out again is the node it was. */
  @HegelTest
  void aResourceNodeRoundTripsThroughAvro(TestCase tc) {
    ResourceNode node = drawNode(tc, "");

    assertSameNode(node, AvroTypeFactory.getResourceNode(AvroTypeFactory.getAvroResourceNode(node)));
  }

  /**
   * A list of nodes round trips in order and without loss: this is how a
   * client is told which nodes the cluster has.
   */
  @HegelTest
  void aNodeListRoundTripsThroughAvro(TestCase tc) {
    int count = tc.draw(integers().min(0).max(8), "count");
    List<ResourceNode> nodes = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      nodes.add(drawNode(tc, "[" + i + "]"));
    }

    List<AvroResourceNode> avro = AvroTypeFactory.getListAvroResourceNode(nodes);
    List<ResourceNode> returned = AvroTypeFactory.getListResourceNode(avro);

    assertEquals(nodes.size(), returned.size(), "node count changed");
    for (int i = 0; i < nodes.size(); i++) {
      assertSameNode(nodes.get(i), returned.get(i));
    }
  }

  /**
   * A name/value job input round trips with all of its pairs. The job on the
   * far side is run with whatever this produces, so a dropped pair is a job
   * run with the wrong arguments.
   */
  @HegelTest
  void aNameValueInputRoundTripsThroughAvro(TestCase tc) {
    Map<String, String> pairs =
        tc.draw(
            maps(word(), text().minSize(0).maxSize(8).categories("Lu", "Ll", "Nd")).maxSize(6),
            "pairs");

    NameValueJobInput in = new NameValueJobInput();
    for (Map.Entry<String, String> e : pairs.entrySet()) {
      in.setNameValuePair(e.getKey(), e.getValue());
    }

    JobInput returned = AvroTypeFactory.getJobInput(AvroTypeFactory.getAvroJobInput(in));

    assertEquals(in.getId(), returned.getId(), "input id");
    assertEquals(in.getProps(), ((NameValueJobInput) returned).getProps(), "input properties");
  }
}
