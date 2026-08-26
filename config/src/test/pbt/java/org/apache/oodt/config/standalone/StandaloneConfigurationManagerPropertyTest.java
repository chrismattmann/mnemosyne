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

package org.apache.oodt.config.standalone;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.oodt.config.Component;
import org.apache.oodt.config.ConfigEventType;
import org.apache.oodt.config.Constants;

/**
 * Properties of {@link StandaloneConfigurationManager} that do not read a file.
 *
 * <p>This is the manager every component gets unless distributed configuration
 * has been switched on, so it is the default start-up path. What matters is
 * that a component configured with no properties files at all still starts, and
 * that the standalone manager honestly reports owning no downloaded files —
 * the CLI deletes everything that list names.
 */
class StandaloneConfigurationManagerPropertyTest {

  private static dev.hegel.Generator<Component> components() {
    return sampledFrom(Arrays.asList(Component.values()));
  }

  /**
   * A component that names no properties files still starts and loading does
   * nothing. Both "no list" and "an empty list" reach here from the factory.
   */
  @HegelTest
  void aComponentWithNoPropertiesFilesLoadsNothing(TestCase tc) throws Exception {
    Component component = tc.draw(components(), "component");
    boolean nullList = tc.draw(booleans(), "nullList");

    StandaloneConfigurationManager manager =
        new StandaloneConfigurationManager(component, nullList ? null : new ArrayList<String>());

    int propertiesBefore = System.getProperties().size();
    manager.loadConfiguration();

    assertEquals(
        propertiesBefore,
        System.getProperties().size(),
        "loading no configuration changed the system properties");
    assertSame(component, manager.getComponent());
  }

  /**
   * The standalone manager owns no downloaded files. {@code ConfigPublisher}
   * and the shutdown path delete what this list names, so a non-empty answer
   * would delete files the manager never downloaded.
   */
  @HegelTest
  void theStandaloneManagerOwnsNoDownloadedFiles(TestCase tc) {
    Component component = tc.draw(components(), "component");

    StandaloneConfigurationManager manager =
        new StandaloneConfigurationManager(component, new ArrayList<String>());

    List<String> saved = manager.getSavedFiles();
    assertNotNull(saved, "the saved-file list was null");
    assertTrue(saved.isEmpty(), "the standalone manager claims downloaded files: " + saved);
    assertNotSame(saved, manager.getSavedFiles(), "two callers were handed the same list");
  }

  /**
   * Clearing configuration is a no-op that leaves the manager usable. The CLI
   * calls it unconditionally, including on a component that never loaded
   * anything.
   */
  @HegelTest
  void clearingLeavesTheManagerUsable(TestCase tc) throws Exception {
    Component component = tc.draw(components(), "component");
    ConfigEventType event =
        tc.draw(sampledFrom(Arrays.asList(ConfigEventType.values())), "event");

    StandaloneConfigurationManager manager =
        new StandaloneConfigurationManager(component, new ArrayList<String>());

    manager.clearConfiguration();
    manager.loadConfiguration();
    manager.clearConfiguration();

    tc.note("cleared after a " + event + " event");
    assertSame(component, manager.getComponent());
    assertTrue(manager.getSavedFiles().isEmpty());
  }

  /**
   * A standalone deployment is the default project. Nothing distinguishes one
   * standalone component from another, and the project name is what a caller
   * reads to report the deployment.
   */
  @HegelTest
  void aStandaloneDeploymentIsTheDefaultProject(TestCase tc) {
    Component component = tc.draw(components(), "component");
    tc.note("standalone manager for " + component);

    StandaloneConfigurationManager manager =
        new StandaloneConfigurationManager(component, new ArrayList<String>());

    assertEquals(Constants.DEFAULT_PROJECT, manager.getProject());
  }

  /**
   * The three components are told apart by name and by home directory. Both are
   * used to address configuration, so a collision would have two components
   * read each other's files.
   */
  @HegelTest
  void componentsAreToldApartByNameAndHome(TestCase tc) {
    Component a = tc.draw(components(), "a");
    Component b = tc.draw(components(), "b");

    assertEquals(a == b, a.getName().equals(b.getName()), "two components share a name");
    assertEquals(a == b, a.getHome().equals(b.getHome()), "two components share a home variable");
    assertTrue(a.getName().indexOf('/') < 0, "a component name contains a path separator");
    assertTrue(!a.getName().isEmpty(), "a component has an empty name");
    assertTrue(!a.getHome().isEmpty(), "a component has an empty home variable");
  }
}
