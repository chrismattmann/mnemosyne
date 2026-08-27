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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Properties of {@link WorkflowTaskConfiguration} and its twin
 * {@link WorkflowConditionConfiguration}.
 *
 * <p>These two are the only place a workflow file's {@code <configuration>}
 * block ends up: everything a task or condition is told about how to behave
 * arrives through them, and every struct factory in this module writes them
 * out and reads them back. They are a thin wrapper over {@link Properties},
 * and the whole of what a caller relies on is that what went in comes out
 * again under the same name.
 */
class WorkflowTaskConfigurationPropertyTest {

  /** Property names and values, drawn from an alphabet that collides. */
  private static String word(TestCase tc, String label) {
    return tc.draw(text().minSize(1).maxSize(4).categories("Lu", "Nd"), label);
  }

  /**
   * A property that was added is readable under its name, and one that was
   * never added reads back as absent. Every task in OODT begins by asking its
   * configuration for a property it expects the workflow file to have set.
   */
  @HegelTest
  void anAddedPropertyIsReadableAndAnAbsentOneIsNot(TestCase tc) {
    int count = tc.draw(integers().min(0).max(6), "count");
    Map<String, String> added = new LinkedHashMap<>();
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();

    for (int i = 0; i < count; i++) {
      String name = word(tc, "name" + i);
      String value = word(tc, "value" + i);
      config.addConfigProperty(name, value);
      added.put(name, value);
    }

    for (Map.Entry<String, String> entry : added.entrySet()) {
      assertEquals(entry.getValue(), config.getProperty(entry.getKey()),
          entry.getKey() + " did not come back as it was set");
    }
    String absent = word(tc, "absent");
    if (!added.containsKey(absent)) {
      assertNull(config.getProperty(absent),
          absent + " was never set but has a value");
    }
  }

  /**
   * Setting the same property twice leaves the second value. A workflow file
   * that declares a property twice, or a task that overrides one at runtime,
   * gets the later declaration rather than an accumulation of both.
   */
  @HegelTest
  void settingAPropertyTwiceKeepsTheSecondValue(TestCase tc) {
    String name = word(tc, "name");
    String first = word(tc, "first");
    String second = word(tc, "second");
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();

    config.addConfigProperty(name, first);
    config.addConfigProperty(name, second);

    assertEquals(second, config.getProperty(name),
        name + " kept the first value it was given");
    assertEquals(1, config.getProperties().size(),
        "setting " + name + " twice left two properties behind");
  }

  /**
   * The {@link Properties} handed out holds exactly what was added, and is the
   * configuration's own: the struct factories iterate it directly rather than
   * going through {@code getProperty}, so a view that disagreed with the
   * lookups would send something different over the wire than the task sees.
   */
  @HegelTest
  void thePropertiesViewAgreesWithTheLookups(TestCase tc) {
    int count = tc.draw(integers().min(0).max(6), "count");
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    for (int i = 0; i < count; i++) {
      config.addConfigProperty(word(tc, "name" + i), word(tc, "value" + i));
    }

    Properties properties = config.getProperties();

    assertNotNull(properties, "a configuration handed out no properties");
    for (Object key : properties.keySet()) {
      String name = (String) key;
      assertEquals(properties.getProperty(name), config.getProperty(name),
          name + " reads differently through the two routes");
    }
    assertTrue(properties.size() <= count,
        "the configuration invented properties nobody added");
  }

  /**
   * A configuration built around an existing {@link Properties} reads from it.
   * {@code TaskJobInput} loads a properties file straight into the object the
   * configuration is holding, so the configuration has to be a view of that
   * object rather than a copy made at construction time.
   */
  @HegelTest
  void aConfigurationBuiltAroundPropertiesReadsFromThem(TestCase tc) {
    String name = word(tc, "name");
    String value = word(tc, "value");
    Properties properties = new Properties();
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration(properties);

    properties.setProperty(name, value);

    assertEquals(value, config.getProperty(name),
        name + " was set on the properties but the configuration missed it");
  }

  /**
   * The condition configuration behaves as the task configuration does. They
   * are the same class twice over, and {@code WorkflowCondition} hands its one
   * to condition instances expecting exactly the task-side behaviour.
   */
  @HegelTest
  void theConditionConfigurationBehavesTheSameWay(TestCase tc) {
    int count = tc.draw(integers().min(0).max(6), "count");
    Map<String, String> added = new LinkedHashMap<>();
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    WorkflowTaskConfiguration twin = new WorkflowTaskConfiguration();

    for (int i = 0; i < count; i++) {
      String name = word(tc, "name" + i);
      String value = word(tc, "value" + i);
      config.addConfigProperty(name, value);
      twin.addConfigProperty(name, value);
      added.put(name, value);
    }

    for (Map.Entry<String, String> entry : added.entrySet()) {
      assertEquals(entry.getValue(), config.getProperty(entry.getKey()),
          entry.getKey() + " did not come back as it was set");
    }
    assertEquals(twin.getProperties(), config.getProperties(),
        "the two configurations disagree about the same properties");
  }
}
