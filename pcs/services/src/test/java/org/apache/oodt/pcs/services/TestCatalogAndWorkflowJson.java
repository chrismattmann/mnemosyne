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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

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
}
