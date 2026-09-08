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
package org.apache.oodt.cas.workflow.structs;

import junit.framework.TestCase;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.resource.structs.AvroTypeFactory;
import org.apache.oodt.cas.resource.structs.JobInput;
import org.apache.oodt.cas.resource.structs.avrotypes.AvroJobInput;

/**
 * A task job input has to survive the wire.
 *
 * <p>It did not. AvroTypeFactory converted only NameValueJobInput, and every
 * other job input crossed as an empty object of the right class: the batch
 * stub received a TaskJobInput with no task config, no metadata and no
 * instance class name, and failed with a NullPointerException naming none of
 * them. The ResourceRunner could not run a workflow task on a remote node at
 * all, which is a thing nobody could see because the failure looked like a
 * provisioning problem.</p>
 *
 * <p>This test is the one that was missing.</p>
 */
public class TestTaskJobInputOverAvro extends TestCase {

  private TaskJobInput original() {
    TaskJobInput in = new TaskJobInput();
    in.setWorkflowTaskInstanceClassName("org.apache.oodt.cas.pge.StdPGETaskInstance");

    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("QueueName", "translate");
    config.addConfigProperty("PGETask_Name", "Translate_Chunk_Task");
    in.setTaskConfig(config);

    Metadata met = new Metadata();
    met.addMetadata("Filename", "chunk-00100.json");
    met.addMetadata("ChunkFile", "chunk-00100.json");
    in.setDynMetadata(met);
    return in;
  }

  private TaskJobInput roundTrip(TaskJobInput in) {
    AvroJobInput avro = AvroTypeFactory.getAvroJobInput(in);
    JobInput back = AvroTypeFactory.getJobInput(avro);
    assertTrue("the class on the far side must be a TaskJobInput",
        back instanceof TaskJobInput);
    return (TaskJobInput) back;
  }

  public void testTheInstanceClassNameSurvives() {
    // Without this the batch stub has no task to build, which is exactly
    // where it failed: NullPointerException in TaskJob.execute.
    assertEquals("org.apache.oodt.cas.pge.StdPGETaskInstance",
        roundTrip(original()).getWorkflowTaskInstanceClassName());
  }

  public void testTheTaskConfigurationSurvives() {
    WorkflowTaskConfiguration config = roundTrip(original()).getTaskConfig();
    assertNotNull("a task with no configuration cannot run", config);
    assertEquals("translate", config.getProperty("QueueName"));
    assertEquals("Translate_Chunk_Task", config.getProperty("PGETask_Name"));
  }

  public void testTheDynamicMetadataSurvives() {
    Metadata met = roundTrip(original()).getDynMetadata();
    assertNotNull("a task with no metadata does not know what to work on", met);
    assertEquals("chunk-00100.json", met.getMetadata("Filename"));
    assertEquals("chunk-00100.json", met.getMetadata("ChunkFile"));
  }

  public void testAnEmptyInputIsStillCarried() {
    // Nothing to carry is not the same as failing to carry it.
    TaskJobInput back = roundTrip(new TaskJobInput());
    assertNotNull(back);
  }
}
