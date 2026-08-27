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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

/**
 * Properties of the Java-serialisation form of a job in
 * {@link JobSpecSerializer}.
 *
 * <p>The class had no unit tests. It exists so a {@link JobSpec} can be handed
 * to a remote batch stub and rebuilt there, so the only thing it has to promise
 * is that what comes out the far end is the job that went in. These properties
 * state that for a {@link NameValueJobInput}, the input implementation the
 * serialiser has special support for.
 */
class JobSpecSerializerPropertyTest {

  private static Generator<String> word() {
    return text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");
  }

  private static Generator<Map<String, String>> pairs() {
    return maps(word(), text().minSize(0).maxSize(8).categories("Lu", "Ll", "Nd")).maxSize(6);
  }

  private static JobSpec drawSpec(TestCase tc) {
    Job job =
        new Job(
            tc.draw(word(), "job.id"),
            tc.draw(word(), "job.name"),
            tc.draw(word(), "job.instanceClass"),
            NameValueJobInput.class.getName(),
            tc.draw(word(), "job.queue"),
            tc.draw(integers().min(0).max(1000), "job.load"));
    job.setStatus(JobStatus.QUEUED);

    NameValueJobInput in = new NameValueJobInput();
    for (Map.Entry<String, String> e : tc.draw(pairs(), "input").entrySet()) {
      in.setNameValuePair(e.getKey(), e.getValue());
    }
    return new JobSpec(in, job);
  }

  private static void assertSameSpec(JobSpec expected, JobSpec actual) {
    Job a = expected.getJob();
    Job b = actual.getJob();
    assertEquals(a.getId(), b.getId(), "id");
    assertEquals(a.getName(), b.getName(), "name");
    assertEquals(a.getJobInstanceClassName(), b.getJobInstanceClassName(), "instance class");
    assertEquals(a.getJobInputClassName(), b.getJobInputClassName(), "input class");
    assertEquals(a.getQueueName(), b.getQueueName(), "queue");
    assertEquals(a.getLoadValue(), b.getLoadValue(), "load");
    assertEquals(a.getStatus(), b.getStatus(), "status");
    assertEquals(
        ((NameValueJobInput) expected.getIn()).getProps(),
        ((NameValueJobInput) actual.getIn()).getProps(),
        "input");
  }

  /**
   * A job spec rebuilt from its serialiser is the job spec it was built from.
   * A remote node executes what it is handed, so any field lost here is a job
   * that runs with the wrong input, on the wrong queue, or under the wrong id.
   */
  @HegelTest
  void aSpecRebuildsToItself(TestCase tc) throws Exception {
    JobSpec spec = drawSpec(tc);

    JobSpec rebuilt = new JobSpecSerializer(spec).getJobSpec();

    assertSameSpec(spec, rebuilt);
  }

  /**
   * The serialiser survives an actual Java serialisation round trip, which is
   * what it is for: it is written to a stream on one host and read back on
   * another.
   */
  @HegelTest
  void aSpecSurvivesJavaSerialisation(TestCase tc) throws Exception {
    JobSpec spec = drawSpec(tc);

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(new JobSpecSerializer(spec));
    }

    JobSpecSerializer read;
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      read = (JobSpecSerializer) in.readObject();
    }

    assertSameSpec(spec, read.getJobSpec());
  }

  /** Rebuilding twice from the same serialiser gives the same spec twice. */
  @HegelTest
  void rebuildingIsRepeatable(TestCase tc) throws Exception {
    JobSpec spec = drawSpec(tc);
    JobSpecSerializer serializer = new JobSpecSerializer(spec);

    assertSameSpec(serializer.getJobSpec(), serializer.getJobSpec());
  }
}
