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

package org.apache.oodt.cas.workflow.lifecycle;

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

//JDK imports
import java.util.logging.Level;
import java.util.logging.LogManager;

//JUnit imports
import junit.framework.TestCase;

/**
 * The guard shipped so that a lifecycle can branch on metadata without anyone
 * writing Java for it.
 *
 * @author mattmann
 */
public class TestMetadataPreCondition extends TestCase {

  private MetadataPreCondition preCondition;

  private WorkflowInstance instance;

  public TestMetadataPreCondition() {
    LogManager.getLogManager().getLogger("").setLevel(Level.OFF);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.preCondition = new MetadataPreCondition();
    this.instance = new WorkflowInstance();
    this.instance.setSharedContext(new Metadata());
  }

  public void testPresenceIsEnoughWhenNoValueIsRequired() {
    assertFalse(isMet(config("key", "Staged")));
    instance.getSharedContext().addMetadata("Staged", "anything");
    assertTrue(isMet(config("key", "Staged")));
  }

  public void testValueMustMatchWhenOneIsRequired() {
    instance.getSharedContext().addMetadata("Region", "mx");
    assertTrue(isMet(config("key", "Region", "value", "mx")));
    assertFalse(isMet(config("key", "Region", "value", "ve")));
  }

  /**
   * A multi-valued key is satisfied by any of its values, which is how a key
   * accumulated by several tasks reads.
   */
  public void testAnyValueOfAMultiValuedKeyMatches() {
    instance.getSharedContext().addMetadata("Region", "mx");
    instance.getSharedContext().addMetadata("Region", "ve");
    assertTrue(isMet(config("key", "Region", "value", "ve")));
  }

  public void testNegateInvertsTheTest() {
    assertTrue("an absent key is what negate is for",
        isMet(config("key", "Staged", "negate", "true")));
    instance.getSharedContext().addMetadata("Staged", "true");
    assertFalse(isMet(config("key", "Staged", "negate", "true")));
  }

  /**
   * A guard that was not told what to look at cannot be satisfied. Reading it
   * the other way would turn a missing property into an open gate.
   */
  public void testMissingKeyPropertyIsNeverMet() {
    assertFalse(isMet(new WorkflowConditionConfiguration()));
    assertFalse(isMet(config("key", "   ")));
  }

  public void testInstanceWithoutASharedContextIsNeverMet() {
    this.instance.setSharedContext(null);
    assertFalse(isMet(config("key", "Staged")));
  }

  private boolean isMet(WorkflowConditionConfiguration config) {
    WorkflowState state = new WorkflowState();
    state.setName("UnderTest");
    return preCondition.isMet(state, instance, config);
  }

  private WorkflowConditionConfiguration config(String... nameValuePairs) {
    WorkflowConditionConfiguration config =
        new WorkflowConditionConfiguration();
    for (int i = 0; i < nameValuePairs.length; i += 2) {
      config.addConfigProperty(nameValuePairs[i], nameValuePairs[i + 1]);
    }
    return config;
  }
}
