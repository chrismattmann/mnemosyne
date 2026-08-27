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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.NameValueJobInput;

/**
 * Properties of {@link JobBuilder}, which turns a job XML file into the
 * {@link JobSpec} the resource manager schedules.
 *
 * <p>A job file is what a user hands the resource manager on the command line.
 * Every field in it decides something about the run — which queue, how much
 * load, which classes to instantiate — so each property writes a job file into
 * a fresh temporary directory and asserts the built spec says what the file
 * said. The directory is removed in a {@code finally} block.
 */
class JobBuilderPropertyTest {

  private static final String INPUT_CLASS =
      "org.apache.oodt.cas.resource.structs.NameValueJobInput";

  /** An identifier as it appears in a job file. */
  private static final Generator<String> WORD =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  /** A queue name. */
  private static final Generator<String> QUEUE =
      sampledFrom(List.of("quick", "long", "high-mem", "default"));

  private static File freshDir() throws IOException {
    return Files.createTempDirectory("resource-pbt").toFile();
  }

  private static void delete(File dir) {
    File[] children = dir.listFiles();
    if (children != null) {
      for (File child : children) {
        delete(child);
      }
    }
    if (!dir.delete()) {
      dir.deleteOnExit();
    }
  }

  private static List<String> distinct(List<String> values) {
    Set<String> set = new LinkedHashSet<>(values);
    return new ArrayList<>(set);
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
  }

  private static File writeJobFile(File dir, String xml) throws IOException {
    File file = new File(dir, "job.xml");
    Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  /**
   * Every field of a job file reaches the job the resource manager will run:
   * its id and name, the classes to instantiate, the queue it belongs in, and
   * the load it claims. Silently defaulting any one of these schedules a
   * different job from the one that was asked for.
   */
  @HegelTest(testCases = 30)
  void everyJobFieldSurvivesTheFile(TestCase tc) throws Exception {
    String id = tc.draw(WORD, "id");
    String name = tc.draw(WORD, "name");
    String instanceClass = tc.draw(WORD, "instanceClass");
    String queue = tc.draw(QUEUE, "queue");
    int load = tc.draw(integers().min(0).max(1000), "load");

    File dir = freshDir();
    try {
      File file = writeJobFile(dir,
          "<cas:job xmlns:cas=\"http://oodt.apache.org\" id=\"" + escape(id)
              + "\" name=\"" + escape(name) + "\">"
              + "<instanceClass name=\"" + escape(instanceClass) + "\"/>"
              + "<queue>" + queue + "</queue>"
              + "<load>" + load + "</load>"
              + "<inputClass name=\"" + INPUT_CLASS + "\"/>"
              + "</cas:job>");

      JobSpec spec = JobBuilder.buildJobSpec(file);

      assertNotNull(spec, "no job spec was built from a well-formed job file");
      assertEquals(id, spec.getJob().getId(), "the job id changed");
      assertEquals(name, spec.getJob().getName(), "the job name changed");
      assertEquals(instanceClass, spec.getJob().getJobInstanceClassName(),
          "the job instance class changed");
      assertEquals(INPUT_CLASS, spec.getJob().getJobInputClassName(),
          "the job input class changed");
      assertEquals(queue, spec.getJob().getQueueName(), "the queue changed");
      assertEquals(Integer.valueOf(load), spec.getJob().getLoadValue(),
          "the load value changed");
      assertNotNull(spec.getIn(), "no job input was constructed");
    } finally {
      delete(dir);
    }
  }

  /**
   * The properties declared on the input class reach the job input. These are
   * the job's own arguments — a missing one runs the job with the wrong
   * parameters, which is worse than not running it at all.
   */
  @HegelTest(testCases = 30)
  void inputPropertiesReachTheJobInput(TestCase tc) throws Exception {
    List<String> names = distinct(tc.draw(lists(WORD).minSize(1).maxSize(5), "names"));
    List<String> values = tc.draw(lists(WORD).minSize(1).maxSize(5), "values");

    Map<String, String> expected = new LinkedHashMap<>();
    StringBuilder props = new StringBuilder("<properties>");
    for (int i = 0; i < names.size(); i++) {
      String value = values.get(i % values.size());
      expected.put(names.get(i), value);
      props.append("<property name=\"").append(escape(names.get(i)))
          .append("\" value=\"").append(escape(value)).append("\"/>");
    }
    props.append("</properties>");

    File dir = freshDir();
    try {
      File file = writeJobFile(dir,
          "<cas:job xmlns:cas=\"http://oodt.apache.org\" id=\"j\" name=\"n\">"
              + "<instanceClass name=\"SomeInstance\"/>"
              + "<queue>quick</queue><load>1</load>"
              + "<inputClass name=\"" + INPUT_CLASS + "\">" + props + "</inputClass>"
              + "</cas:job>");

      JobSpec spec = JobBuilder.buildJobSpec(file);

      assertNotNull(spec, "no job spec was built");
      NameValueJobInput input = (NameValueJobInput) spec.getIn();
      for (Map.Entry<String, String> entry : expected.entrySet()) {
        assertEquals(entry.getValue(), input.getProps().getProperty(entry.getKey()),
            "input property [" + entry.getKey() + "] changed");
      }
    } finally {
      delete(dir);
    }
  }

  /**
   * A path that names no file is reported as "no job spec", which is what the
   * method's own error handling produces and what its callers check. A user who
   * mistypes a path on the command line should be told, not handed a stack
   * trace.
   */
  @HegelTest(testCases = 20)
  void aMissingJobFileYieldsNoSpec(TestCase tc) throws Exception {
    String name = tc.draw(WORD, "name");

    File dir = freshDir();
    try {
      assertNull(JobBuilder.buildJobSpec(new File(dir, name + "-absent.xml").getAbsolutePath()),
          "a spec was built from a file that does not exist");
    } finally {
      delete(dir);
    }
  }

  /**
   * A job file that is not well-formed XML is reported the same way, rather
   * than escaping as an unchecked exception from inside the parser.
   */
  @HegelTest(testCases = 20)
  void aMalformedJobFileYieldsNoSpec(TestCase tc) throws Exception {
    String junk = tc.draw(WORD, "junk");

    File dir = freshDir();
    try {
      File file = writeJobFile(dir, "<cas:job id=\"" + junk);
      assertNull(JobBuilder.buildJobSpec(file),
          "a spec was built from a file that is not XML");
    } finally {
      delete(dir);
    }
  }
}
