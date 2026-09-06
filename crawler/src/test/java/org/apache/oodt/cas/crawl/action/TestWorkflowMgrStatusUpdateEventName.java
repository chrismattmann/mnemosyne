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
package org.apache.oodt.cas.crawl.action;

import org.apache.oodt.cas.metadata.Metadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Which event an ingest fires.
 *
 * <p>The historic answer is the product type plus a suffix, so an
 * <code>EmploymentStringChunk</code> fires
 * <code>EmploymentStringChunkIngest</code>. That assumes the workflow
 * repository declares events apart from workflows, which the XML
 * repository does and the packaged repository does not: there a workflow's
 * id <em>is</em> its event, so a derived name matches nothing and the
 * ingest starts no workflow at all, silently.
 */
public class TestWorkflowMgrStatusUpdateEventName {

  private Metadata forType(String productType) {
    Metadata metadata = new Metadata();
    metadata.addMetadata("ProductType", productType);
    return metadata;
  }

  @Test
  public void testTheDerivedNameIsUnchangedByDefault() throws Exception {
    WorkflowMgrStatusUpdate action = new WorkflowMgrStatusUpdate();
    assertEquals("EmploymentStringChunkIngest",
        action.eventNameFor(forType("EmploymentStringChunk")));
  }

  @Test
  public void testTheSuffixStillApplies() throws Exception {
    WorkflowMgrStatusUpdate action = new WorkflowMgrStatusUpdate();
    action.setIngestSuffix("Arrived");
    assertEquals("EmploymentStringChunkArrived",
        action.eventNameFor(forType("EmploymentStringChunk")));
  }

  @Test
  public void testAnExplicitNameWins() throws Exception {
    WorkflowMgrStatusUpdate action = new WorkflowMgrStatusUpdate();
    action.setEventName("urn:bigtranslate:TranslateChunkWorkflow");
    assertEquals("a packaged workflow is started by its id",
        "urn:bigtranslate:TranslateChunkWorkflow",
        action.eventNameFor(forType("EmploymentStringChunk")));
  }

  @Test
  public void testAnExplicitNameMayReferToMetadata() throws Exception {
    WorkflowMgrStatusUpdate action = new WorkflowMgrStatusUpdate();
    action.setEventName("urn:x:[ProductType]Workflow");
    assertEquals("urn:x:EmploymentStringChunkWorkflow",
        action.eventNameFor(forType("EmploymentStringChunk")));
  }

  @Test
  public void testAnEmptyNameFallsBackToTheDerivedOne() throws Exception {
    WorkflowMgrStatusUpdate action = new WorkflowMgrStatusUpdate();
    action.setEventName("   ");
    assertEquals("EmploymentStringChunkIngest",
        action.eventNameFor(forType("EmploymentStringChunk")));
  }
}
