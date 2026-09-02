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

import org.apache.oodt.cas.workflow.util.AvroTypeFactory;

/**
 * The reason an instance is not running, which is what tells deferred from
 * abandoned once a restart has taken the engine's memory with it.
 */
public class TestWaitingOn extends TestCase {

  public void testAnInstanceStartsWaitingOnNothing() {
    assertNull(instance().getWaitingOn());
  }

  public void testTheReasonSurvivesTheWire() {
    WorkflowInstance inst = instance();
    inst.setWaitingOn("condition:urn:drat:MapsDone");

    WorkflowInstance back = AvroTypeFactory.getWorkflowInstance(
        AvroTypeFactory.getAvroWorkflowInstance(inst));

    assertEquals("condition:urn:drat:MapsDone", back.getWaitingOn());
  }

  /** Nothing to say is said as nothing, not as an empty string. */
  public void testNoReasonCrossesAsNoReason() {
    WorkflowInstance back = AvroTypeFactory.getWorkflowInstance(
        AvroTypeFactory.getAvroWorkflowInstance(instance()));
    assertNull(back.getWaitingOn());
  }

  /** Cleared when the instance stops waiting. */
  public void testTheReasonCanBeCleared() {
    WorkflowInstance inst = instance();
    inst.setWaitingOn("task:urn:drat:RepoCrawler");
    inst.setWaitingOn(null);
    assertNull(inst.getWaitingOn());
  }

  private WorkflowInstance instance() {
    WorkflowInstance inst = new WorkflowInstance();
    inst.setId("urn:oodt:waiting");
    inst.setSharedContext(new org.apache.oodt.cas.metadata.Metadata());
    return inst;
  }
}
