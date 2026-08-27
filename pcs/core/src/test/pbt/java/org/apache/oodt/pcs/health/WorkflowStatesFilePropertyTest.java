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

package org.apache.oodt.pcs.health;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.oodt.pcs.PcsConfigFixture;
import org.apache.oodt.pcs.input.PGEConfigurationFile;
import org.apache.oodt.pcs.input.PGEGroup;
import org.apache.oodt.pcs.input.PGEVector;

/**
 * Properties of {@link WorkflowStatesFile}, the list of workflow states the
 * health monitor counts pipelines against.
 *
 * <p>The states in this file drive one line of the health report each, so the
 * list the operator wrote and the list the monitor iterates have to be the same
 * list, in the same order.
 */
class WorkflowStatesFilePropertyTest {

  /** A workflow state name, e.g. {@code FINISHED}. */
  private static final Generator<String> STATE =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  private static PGEConfigurationFile statesConfig(List<String> states) {
    PGEConfigurationFile conf = new PGEConfigurationFile();
    PGEGroup group = new PGEGroup(WorkflowStatesMetKeys.WORKFLOW_STATES_GROUP);
    group.addVector(new PGEVector(
        WorkflowStatesMetKeys.WORKFLOW_STATES_VECTOR, new ArrayList<Object>(states)));
    conf.getPgeSpecificGroups().put(group.getName(), group);
    return conf;
  }

  /**
   * The states come back in the order they were written, with no duplicates
   * collapsed and none dropped. The health report prints one line per state, so
   * order and multiplicity are both visible to the operator.
   */
  @HegelTest(testCases = 25)
  void statesSurviveTheFileInOrder(TestCase tc) throws Exception {
    List<String> states = tc.draw(lists(STATE).minSize(1).maxSize(8), "states");

    File dir = PcsConfigFixture.freshDir();
    try {
      File file = PcsConfigFixture.write(statesConfig(states), dir, "workflow-states.xml");
      WorkflowStatesFile statesFile = new WorkflowStatesFile(file.getAbsolutePath());

      assertEquals(states, statesFile.getStates(), "the workflow state list changed");
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }

  /**
   * A path that names no file is rejected by the constructor as an
   * {@link InstantiationException}, which is what the constructor documents and
   * what {@link org.apache.oodt.pcs.tools.PCSHealthMonitor} declares it throws.
   * A caller who mistypes a path should get that, not an unchecked failure.
   */
  @HegelTest(testCases = 20)
  void aMissingFileIsRejectedAsAnInstantiationFailure(TestCase tc) throws Exception {
    String name = tc.draw(STATE, "name");

    File dir = PcsConfigFixture.freshDir();
    try {
      String missing = new File(dir, name + "-does-not-exist.xml").getAbsolutePath();
      assertThrows(InstantiationException.class, () -> new WorkflowStatesFile(missing));
    } finally {
      PcsConfigFixture.delete(dir);
    }
  }
}
