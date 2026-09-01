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
package org.apache.oodt.pcs.services;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import junit.framework.TestCase;

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

public class TestCatalogAndWorkflowJson extends TestCase {

  public void testEncodeType() {
    ProductType type = new ProductType();
    type.setProductTypeId("urn:test:Type");
    type.setName("EmploymentJob");
    type.setDescription("jobs");
    Map<String, Object> row = CatalogResource.encodeType(type);
    assertEquals("urn:test:Type", row.get("id"));
    assertEquals("EmploymentJob", row.get("name"));
    assertEquals("jobs", row.get("description"));
  }

  public void testEncodeProduct() {
    ProductType type = new ProductType();
    type.setName("EmploymentJob");
    Product product = new Product();
    product.setProductId("abc");
    product.setProductName("row.tsv");
    product.setProductType(type);
    product.setTransferStatus("RECEIVED");
    Map<String, Object> row = CatalogResource.encodeProduct(product, null);
    assertEquals("abc", row.get("id"));
    assertEquals("row.tsv", row.get("name"));
    assertEquals("RECEIVED", row.get("transferStatus"));
    assertEquals("EmploymentJob", ((Map<?, ?>) row.get("type")).get("name"));
  }

  public void testEncodeMetadataPreservesMultiValues() {
    Metadata met = new Metadata();
    met.addMetadata("title", "One");
    met.addMetadata("title", "Two");
    Map<String, Object> map = CatalogResource.encodeMetadata(met);
    assertTrue(map.get("title") instanceof List);
    assertEquals(2, ((List<?>) map.get("title")).size());
  }

  public void testEncodeReferences() {
    Reference ref = new Reference();
    ref.setOrigReference("file:///tmp/a.tsv");
    ref.setDataStoreReference("file:///archive/a.tsv");
    ref.setFileSize(12L);
    List<Map<String, Object>> rows = CatalogResource.encodeReferences(Arrays.asList(ref));
    assertEquals(1, rows.size());
    assertEquals("file:///tmp/a.tsv", rows.get(0).get("orig"));
    assertEquals(Long.valueOf(12), rows.get(0).get("fileSize"));
  }

  public void testEncodeInstance() {
    Workflow workflow = new Workflow();
    workflow.setId("urn:bt:Flow");
    workflow.setName("BigTranslateWorkflow");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:bt:Translate");
    task.setTaskName("Translate");
    workflow.setTasks(Arrays.asList(task));
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("inst-1");
    inst.setStatus("PGE EXEC");
    inst.setWorkflow(workflow);
    inst.setCurrentTaskId(task.getTaskId());
    Map<String, Object> row = WorkflowResource.encodeInstance(inst);
    assertEquals("inst-1", row.get("id"));
    assertEquals("PGE EXEC", row.get("status"));
    assertEquals("urn:bt:Flow", row.get("workflowId"));
    assertEquals("Translate", row.get("currentTaskName"));
    assertFalse(row.containsKey("productName"));
    Metadata met = new Metadata();
    met.addMetadata("InputFiles", "parent.tsv");
    met.addMetadata("Filename", "jobs.tsv.aaaa");
    inst.setSharedContext(met);
    row = WorkflowResource.encodeInstance(inst);
    assertEquals("jobs.tsv.aaaa", row.get("productName"));
  }

  public void testEncodeInstanceAbandonedWhenEngineDoesNotKnowId() {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("ghost");
    inst.setStatus("QUEUED");
    Set executing = new HashSet(Arrays.asList("live-1"));
    Map<String, Object> row = WorkflowResource.encodeInstance(inst, executing);
    assertEquals(Boolean.FALSE, row.get("running"));
    assertEquals(Boolean.TRUE, row.get("abandoned"));
    inst.setId("live-1");
    inst.setStatus("QUEUED");
    row = WorkflowResource.encodeInstance(inst, executing);
    assertEquals(Boolean.TRUE, row.get("running"));
    assertEquals(Boolean.FALSE, row.get("abandoned"));
    inst.setId("done");
    inst.setStatus("FINISHED");
    row = WorkflowResource.encodeInstance(inst, executing);
    assertEquals(Boolean.FALSE, row.get("running"));
    assertEquals(Boolean.FALSE, row.get("abandoned"));
  }

  public void testEncodeInstanceIncludesPgeProgress() throws Exception {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("inst-pge");
    inst.setStatus("PGE EXEC");
    Metadata met = new Metadata();
    met.addMetadata("PGETask_Done", "50");
    met.addMetadata("PGETask_Total", "612");
    met.addMetadata("PGETask_Progress", "encoded");
    inst.setSharedContext(met);
    Map<String, Object> row = WorkflowResource.encodeInstance(inst);
    Map<?, ?> progress = (Map<?, ?>) row.get("pgeProgress");
    assertEquals(Integer.valueOf(50), progress.get("done"));
    assertEquals(Integer.valueOf(612), progress.get("total"));
    assertEquals("encoded", progress.get("message"));

    File dir = File.createTempFile("jobdir", "pge");
    dir.delete();
    dir.mkdir();
    Files.write(new File(dir, ".progress").toPath(),
        "done=3\ntotal=10\nmsg=split\n".getBytes(StandardCharsets.UTF_8));
    Metadata fromFile = new Metadata();
    fromFile.addMetadata("JobDir", dir.getAbsolutePath());
    Map<String, Object> peeked = PgeProgressPeek.of(fromFile);
    assertEquals(Integer.valueOf(3), peeked.get("done"));
    assertEquals("split", peeked.get("message"));
  }

  public void testPgeProgressPrefersJobDirFileOverStaleKeys() throws Exception {
    File dir = File.createTempFile("jobdir", "stale");
    dir.delete();
    dir.mkdir();
    Files.write(new File(dir, ".progress").toPath(),
        "done=653\ntotal=653\nmsg=bg CLIP\n".getBytes(StandardCharsets.UTF_8));
    Metadata met = new Metadata();
    met.addMetadata("PGETask_Done", "0");
    met.addMetadata("PGETask_Total", "653");
    met.addMetadata("PGETask_Progress", "jaccard");
    met.addMetadata("JobDir", dir.getAbsolutePath());
    Map<String, Object> peeked = PgeProgressPeek.of(met);
    assertEquals(Integer.valueOf(653), peeked.get("done"));
    assertEquals("bg CLIP", peeked.get("message"));
  }

  public void testEncodeInstanceProductsSkipsNulls() {
    assertEquals(0, WorkflowResource.encodeInstanceProducts(null, null).size());
    Product product = new Product();
    product.setProductId("p1");
    product.setProductName("jobs.tsv.aaaa");
    List<Map<String, Object>> rows = WorkflowResource.encodeInstanceProducts(
        Arrays.asList(product, null), null);
    assertEquals(1, rows.size());
    assertEquals("jobs.tsv.aaaa", rows.get(0).get("name"));
  }

  public void testMatchesWorkflowByIdOrName() {
    Workflow workflow = new Workflow();
    workflow.setId("urn:bt:Split");
    workflow.setName("SplitWorkflow");
    WorkflowInstance inst = new WorkflowInstance();
    inst.setWorkflow(workflow);
    assertTrue(WorkflowResource.matchesWorkflow(inst, "urn:bt:Split"));
    assertTrue(WorkflowResource.matchesWorkflow(inst, "SplitWorkflow"));
    assertFalse(WorkflowResource.matchesWorkflow(inst, "BigTranslateWorkflow"));
    assertFalse(WorkflowResource.matchesWorkflow(inst, null));
  }

  public void testEncodeInstanceDetailIncludesMetadata() {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("inst-2");
    inst.setStatus("FINISHED");
    inst.setTimesBlocked(0);
    Metadata met = new Metadata();
    met.addMetadata("Filename", "jobs.tsv");
    met.addMetadata("ProductType", "EmploymentJobAggregatesTsv");
    inst.setSharedContext(met);
    Map<String, Object> row = WorkflowResource.encodeInstanceDetail(inst, null);
    assertEquals("inst-2", row.get("id"));
    assertEquals(Integer.valueOf(0), row.get("timesBlocked"));
    Map<?, ?> metadata = (Map<?, ?>) row.get("metadata");
    assertEquals("jobs.tsv", ((List<?>) metadata.get("Filename")).get(0));
    assertEquals("EmploymentJobAggregatesTsv",
        ((List<?>) metadata.get("ProductType")).get(0));
  }

  public void testEncodeInstanceDetailIncludesTasks() {
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:bt:Translate");
    task.setTaskName("Translate");
    Workflow workflow = new Workflow();
    workflow.setId("urn:bt:Flow");
    workflow.setName("BigTranslateWorkflow");
    workflow.setTasks(Arrays.asList(task));
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("inst-3");
    inst.setWorkflow(workflow);
    inst.setCurrentTaskId(task.getTaskId());
    Map<String, Object> row = WorkflowResource.encodeInstanceDetail(inst, new Metadata());
    assertTrue(row.get("tasks") instanceof List);
    assertEquals("urn:bt:Translate", ((Map<?, ?>) ((List<?>) row.get("tasks")).get(0)).get("id"));
  }

  public void testEncodeResourceNodeAndJob() {
    try {
      java.net.URL url = new java.net.URL("http://localhost:2001");
      org.apache.oodt.cas.resource.structs.ResourceNode node =
          new org.apache.oodt.cas.resource.structs.ResourceNode("localhost", url, 8);
      Map<String, Object> nodeRow = ResourceResource.encodeNode(node, "0/8", Arrays.asList("quick"));
      assertEquals("localhost", nodeRow.get("id"));
      assertEquals("0/8", nodeRow.get("load"));
      org.apache.oodt.cas.resource.structs.Job job = new org.apache.oodt.cas.resource.structs.Job();
      job.setId("job-1");
      job.setName("Hello");
      job.setQueueName("quick");
      job.setLoadValue(Integer.valueOf(1));
      Map<String, Object> jobRow = ResourceResource.encodeJob(job, "localhost");
      assertEquals("Hello", jobRow.get("name"));
      assertEquals("localhost", jobRow.get("node"));
    } catch (java.net.MalformedURLException e) {
      fail(e.getMessage());
    }
  }

  public void testEncodeWorkflowWithTasks() {
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("t1");
    task.setTaskName("Split");
    Workflow workflow = new Workflow();
    workflow.setId("w1");
    workflow.setName("Flow");
    workflow.setTasks(Arrays.asList(task));
    Map<String, Object> summary = WorkflowResource.encodeWorkflow(workflow, false);
    assertEquals(Integer.valueOf(1), summary.get("taskCount"));
    assertFalse(summary.containsKey("tasks"));
    Map<String, Object> full = WorkflowResource.encodeWorkflow(workflow, true);
    assertTrue(full.get("tasks") instanceof List);
    assertEquals(1, ((List<?>) full.get("tasks")).size());
  }

  /**
   * A workflow can carry conditions of its own. The packaged (wengine) dialect
   * writes a workflow's <conditions> block onto the workflow, not onto any of
   * its tasks, and the encoder used to drop them -- so an operations view
   * showed a gated workflow as if nothing gated it.
   */
  public void testEncodeWorkflowIncludesItsOwnConditions() {
    WorkflowCondition settling = new WorkflowCondition();
    settling.setConditionId("urn:drat:MapsSettling");
    settling.setConditionName("Maps Settling");
    settling.setConditionInstanceClassName("org.example.LongCondition");
    WorkflowCondition done = new WorkflowCondition();
    done.setConditionId("urn:drat:MapsDone");
    done.setConditionName("Maps Done");
    done.setConditionInstanceClassName("org.example.LongCondition");

    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:drat:RatAggregator");
    task.setTaskName("RatAggregator");

    Workflow workflow = new Workflow();
    workflow.setId("urn:drat:AggregatePhase");
    workflow.setName("Aggregate Phase");
    workflow.setTasks(Arrays.asList(task));
    workflow.setPreConditions(Arrays.asList(settling, done));

    Map<String, Object> row = WorkflowResource.encodeWorkflow(workflow, true);

    assertTrue(row.get("preConditions") instanceof List);
    List<?> pre = (List<?>) row.get("preConditions");
    assertEquals("both conditions should be reported", 2, pre.size());
    Map<?, ?> first = (Map<?, ?>) pre.get(0);
    assertEquals("urn:drat:MapsSettling", first.get("id"));
    assertEquals("Maps Settling", first.get("name"));
    // Order is what the workflow declared: a sequential block runs them in
    // that order, so reporting them out of order would misdescribe the gate.
    assertEquals("urn:drat:MapsDone",
        ((Map<?, ?>) pre.get(1)).get("id"));
  }

  /** Post-conditions on a workflow are reported the same way. */
  public void testEncodeWorkflowIncludesPostConditions() {
    WorkflowCondition cond = new WorkflowCondition();
    cond.setConditionId("urn:drat:Verified");
    cond.setConditionName("Verified");
    Workflow workflow = new Workflow();
    workflow.setId("w1");
    workflow.setName("Flow");
    workflow.setPostConditions(Arrays.asList(cond));

    Map<String, Object> row = WorkflowResource.encodeWorkflow(workflow, true);

    List<?> post = (List<?>) row.get("postConditions");
    assertEquals(1, post.size());
    assertEquals("urn:drat:Verified", ((Map<?, ?>) post.get(0)).get("id"));
  }

  /**
   * The XML dialect hangs conditions only on tasks, so its workflows report
   * empty lists rather than a missing key. One shape for both dialects means
   * a reader never has to ask which one produced the workflow.
   */
  public void testAworkflowWithoutItsOwnConditionsStillReportsTheKeys() {
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("t1");
    task.setTaskName("Split");
    Workflow workflow = new Workflow();
    workflow.setId("w1");
    workflow.setName("Flow");
    workflow.setTasks(Arrays.asList(task));

    Map<String, Object> full = WorkflowResource.encodeWorkflow(workflow, true);
    Map<String, Object> summary = WorkflowResource.encodeWorkflow(workflow, false);

    assertTrue(full.containsKey("preConditions"));
    assertTrue(full.containsKey("postConditions"));
    assertEquals(0, ((List<?>) full.get("preConditions")).size());
    // Also present on the summary form the workflow list uses, so the list
    // and the detail view agree about the shape.
    assertTrue(summary.containsKey("preConditions"));
  }

  /**
   * A condition is configured the way a task is. Reporting only its class name
   * says what code runs but not what it was told to do, and two conditions of
   * the same class then read as duplicates of each other.
   */
  public void testEncodeConditionIncludesItsConfiguration() {
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    config.addConfigProperty("MinutesToWait", "5");
    config.addConfigProperty("ProductType", "GenericFile");
    WorkflowCondition cond = new WorkflowCondition();
    cond.setConditionId("urn:drat:MapsDone");
    cond.setConditionName("Maps Done");
    cond.setConditionInstanceClassName("org.example.LongCondition");
    cond.setCondConfig(config);
    cond.setOrder(2);
    cond.setTimeoutSeconds(90L);

    Map<String, Object> row = WorkflowResource.encodeCondition(cond);

    assertEquals("urn:drat:MapsDone", row.get("id"));
    assertEquals(Integer.valueOf(2), row.get("order"));
    assertEquals(Long.valueOf(90L), row.get("timeoutSeconds"));
    Map<?, ?> props = (Map<?, ?>) row.get("properties");
    assertEquals(2, props.size());
    assertEquals("5", props.get("MinutesToWait"));
    assertEquals("GenericFile", props.get("ProductType"));
  }

  /** No configuration is an empty map, not a missing key. */
  public void testAconditionWithoutPropertiesStillReportsTheKey() {
    WorkflowCondition cond = new WorkflowCondition();
    cond.setConditionId("urn:drat:Ready");
    cond.setConditionName("Ready");

    Map<String, Object> row = WorkflowResource.encodeCondition(cond);

    assertTrue(row.containsKey("properties"));
    assertEquals(0, ((Map<?, ?>) row.get("properties")).size());
    assertTrue(row.containsKey("timeoutSeconds"));
  }

  /** The conditions reported on a workflow carry their configuration too. */
  public void testWorkflowConditionsCarryTheirProperties() {
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    config.addConfigProperty("MinutesToWait", "5");
    WorkflowCondition cond = new WorkflowCondition();
    cond.setConditionId("urn:drat:MapsSettling");
    cond.setConditionName("Maps Settling");
    cond.setCondConfig(config);
    Workflow workflow = new Workflow();
    workflow.setId("urn:drat:AggregatePhase");
    workflow.setName("Aggregate Phase");
    workflow.setPreConditions(Arrays.asList(cond));

    Map<String, Object> row = WorkflowResource.encodeWorkflow(workflow, true);

    Map<?, ?> first = (Map<?, ?>) ((List<?>) row.get("preConditions")).get(0);
    assertEquals("5", ((Map<?, ?>) first.get("properties")).get("MinutesToWait"));
  }

  public void testEncodeTaskIncludesConfiguration() {
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("PGETask_Name", "BigTranslate_Task");
    config.addConfigProperty("TranslateBatchSize", "32");
    WorkflowCondition cond = new WorkflowCondition();
    cond.setConditionId("urn:bt:Ready");
    cond.setConditionName("Ready");
    cond.setConditionInstanceClassName("org.example.Ready");
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:bt:Translate");
    task.setTaskName("Translate");
    task.setTaskInstanceClassName("org.apache.oodt.cas.pge.StdPGETaskInstance");
    task.setTaskConfig(config);
    task.setRequiredMetFields(Arrays.asList("Filename"));
    task.setPreConditions(Arrays.asList(cond));
    Map<String, Object> row = WorkflowResource.encodeTask(task);
    assertEquals("org.apache.oodt.cas.pge.StdPGETaskInstance", row.get("className"));
    Map<?, ?> props = (Map<?, ?>) row.get("properties");
    assertEquals("32", props.get("TranslateBatchSize"));
    assertEquals("Filename", ((List<?>) row.get("requiredMetFields")).get(0));
    assertEquals("urn:bt:Ready", ((Map<?, ?>) ((List<?>) row.get("preConditions")).get(0)).get("id"));
    assertNull(row.get("pgeConfig"));
  }

  public void testEncodeTaskPeeksPgeConfig() throws Exception {
    File xml = File.createTempFile("pge-encode-", ".xml");
    xml.deleteOnExit();
    Files.write(xml.toPath(),
        ("<pgeConfig><exe shell=\"/bin/bash\"><cmd>index-imagespace-fgbg.sh</cmd></exe></pgeConfig>")
            .getBytes(StandardCharsets.UTF_8));
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("PGETask_ConfigFilePath", xml.getAbsolutePath());
    WorkflowTask task = new WorkflowTask();
    task.setTaskId("urn:memex:IndexImageSpaceFgBg");
    task.setTaskName("IndexImageSpaceFgBg");
    task.setTaskInstanceClassName("org.apache.oodt.cas.pge.StdPGETaskInstance");
    task.setTaskConfig(config);
    Map<String, Object> row = WorkflowResource.encodeTask(task);
    Map<?, ?> pge = (Map<?, ?>) row.get("pgeConfig");
    assertEquals(xml.getAbsolutePath(), pge.get("path"));
    assertEquals("/bin/bash", pge.get("shell"));
    assertEquals("index-imagespace-fgbg.sh", ((List<?>) pge.get("commands")).get(0));
  }

  public void testPedigreeSkipsUnknownPlaceholders() {
    assertFalse(PedigreeResource.isCataloged(null));
    Product missing = new Product();
    assertFalse(PedigreeResource.isCataloged(missing));
    Product invented = Product.getDefaultFlatProduct("job-1.json", "UNKNOWN");
    assertFalse(PedigreeResource.isCataloged(invented));
    ProductType type = new ProductType();
    type.setName("EmploymentJobAggregatesTsvSplit");
    Product split = new Product();
    split.setProductName("jobs.tsv.aaaa");
    split.setProductType(type);
    assertTrue(PedigreeResource.isCataloged(split));
  }
}
