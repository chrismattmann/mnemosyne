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

package org.apache.oodt.config;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.apache.oodt.config.Constants.Properties.ENABLE_DISTRIBUTED_CONFIGURATION;
import static org.apache.oodt.config.Constants.Properties.ZK_CONNECT_STRING;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Arrays;
import java.util.List;
import org.apache.oodt.config.standalone.StandaloneConfigurationManager;

/**
 * Properties of the standalone/distributed choice in
 * {@link ConfigurationManagerFactory}.
 *
 * <p>Every OODT component asks the factory which configuration manager it
 * should use, and the answer is taken from a system property or an environment
 * variable. The property here is the one an operator relies on: unless
 * distributed configuration has been switched on explicitly, the component
 * starts standalone and never reaches for a zookeeper ensemble.
 *
 * <p>Both properties involved are global, so each case puts them back.
 */
class ConfigurationManagerFactoryPropertyTest {

  private static dev.hegel.Generator<Component> components() {
    return sampledFrom(Arrays.asList(Component.values()));
  }

  /** Property values an operator or a start script might actually write. */
  private static dev.hegel.Generator<String> switchValue() {
    return sampledFrom(Arrays.asList("false", "FALSE", "no", "0", "", "yes", "True "));
  }

  private static void restore(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }

  /**
   * Anything other than "true" leaves the component standalone. The switch is
   * read with {@code Boolean.parseBoolean}, so an operator who wrote
   * {@code yes} or {@code 1} gets the safe answer rather than a component that
   * blocks on a zookeeper connection at start-up.
   */
  @HegelTest
  void anythingButTrueLeavesTheComponentStandalone(TestCase tc) {
    Component component = tc.draw(components(), "component");
    String value = tc.draw(switchValue(), "value");
    List<String> propertiesFiles =
        tc.draw(lists(text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd")).maxSize(3), "files");

    String previous = System.getProperty(ENABLE_DISTRIBUTED_CONFIGURATION);
    try {
      System.setProperty(ENABLE_DISTRIBUTED_CONFIGURATION, value);

      ConfigurationManager manager =
          ConfigurationManagerFactory.getConfigurationManager(component, propertiesFiles);

      assertInstanceOf(StandaloneConfigurationManager.class, manager, "value was '" + value + "'");
      assertSame(component, manager.getComponent());
    } finally {
      restore(ENABLE_DISTRIBUTED_CONFIGURATION, previous);
    }
  }

  /**
   * With no properties files at all the component still gets a manager. A
   * component that reads all of its configuration from system properties passes
   * an empty list, or none.
   */
  @HegelTest
  void aComponentWithNoPropertiesFilesStillGetsAManager(TestCase tc) {
    Component component = tc.draw(components(), "component");
    boolean nullList = tc.draw(dev.hegel.Generators.booleans(), "nullList");

    String previous = System.getProperty(ENABLE_DISTRIBUTED_CONFIGURATION);
    try {
      System.setProperty(ENABLE_DISTRIBUTED_CONFIGURATION, "false");

      ConfigurationManager manager =
          ConfigurationManagerFactory.getConfigurationManager(
              component, nullList ? null : Arrays.<String>asList());

      assertInstanceOf(StandaloneConfigurationManager.class, manager);
      assertSame(component, manager.getComponent());
    } finally {
      restore(ENABLE_DISTRIBUTED_CONFIGURATION, previous);
    }
  }

  /**
   * Asking for distributed configuration with no ensemble to talk to is
   * refused, not attempted. The alternative is a component that hangs at
   * start-up waiting for a connection that was never configured.
   */
  @HegelTest
  void distributedConfigurationWithNoEnsembleIsRefused(TestCase tc) {
    tc.assume(System.getenv(Constants.Env.CONNECT_STRING) == null);
    Component component = tc.draw(components(), "component");

    String previousSwitch = System.getProperty(ENABLE_DISTRIBUTED_CONFIGURATION);
    String previousConnect = System.getProperty(ZK_CONNECT_STRING);
    try {
      System.setProperty(ENABLE_DISTRIBUTED_CONFIGURATION, "true");
      System.clearProperty(ZK_CONNECT_STRING);

      assertThrows(
          IllegalArgumentException.class,
          () -> ConfigurationManagerFactory.getConfigurationManager(component, null));
    } finally {
      restore(ENABLE_DISTRIBUTED_CONFIGURATION, previousSwitch);
      restore(ZK_CONNECT_STRING, previousConnect);
    }
  }
}
