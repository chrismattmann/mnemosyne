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

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Properties of the listener registry in {@link ConfigurationManager}.
 *
 * <p>A component registers itself here so that it is told when its
 * configuration is republished or cleared, and it reloads on being told. A
 * missed notification means the component keeps running on stale configuration
 * with no sign that anything is wrong, so registration and delivery are the
 * whole contract.
 */
class ConfigurationManagerPropertyTest {

  /** A configuration manager with nothing to load; only the registry is under test. */
  private static final class StubManager extends ConfigurationManager {
    StubManager(Component component) {
      super(component);
    }

    StubManager(Component component, String project) {
      super(component, project);
    }

    @Override
    public void loadConfiguration() {}

    @Override
    public void clearConfiguration() {}

    @Override
    public List<String> getSavedFiles() {
      return new ArrayList<>();
    }

    void announce(ConfigEventType type) {
      notifyConfigurationChange(type);
    }
  }

  /** A component recording every notification it is told about. */
  private static final class RecordingListener implements ConfigurationListener {
    private final List<ConfigEventType> heard = new ArrayList<>();

    @Override
    public void configurationChanged(ConfigEventType type) {
      heard.add(type);
    }
  }

  private static dev.hegel.Generator<Component> components() {
    return sampledFrom(Arrays.asList(Component.values()));
  }

  private static dev.hegel.Generator<ConfigEventType> eventTypes() {
    return sampledFrom(Arrays.asList(ConfigEventType.values()));
  }

  /**
   * Every registered component hears every event, once, with the event it was
   * sent. Two components in one process — a file manager and a workflow
   * manager sharing a JVM — both have to be told.
   */
  @HegelTest
  void everyRegisteredComponentHearsEveryEventOnce(TestCase tc) {
    Component component = tc.draw(components(), "component");
    int listeners = tc.draw(integers().min(0).max(5), "listeners");
    List<ConfigEventType> events = new ArrayList<>();
    int eventCount = tc.draw(integers().min(0).max(4), "eventCount");
    for (int i = 0; i < eventCount; i++) {
      events.add(tc.draw(eventTypes(), "event" + i));
    }

    StubManager manager = new StubManager(component);
    List<RecordingListener> registered = new ArrayList<>();
    for (int i = 0; i < listeners; i++) {
      RecordingListener listener = new RecordingListener();
      registered.add(listener);
      manager.addConfigurationListener(listener);
    }

    for (ConfigEventType event : events) {
      manager.announce(event);
    }

    for (RecordingListener listener : registered) {
      assertEquals(events, listener.heard, "a component did not hear exactly what was announced");
    }
  }

  /**
   * Registering the same component twice does not make it reload twice. The
   * registry is a set, and a doubled reload would have the component read its
   * own configuration files while it is still writing them.
   */
  @HegelTest
  void registeringTwiceDoesNotDoubleTheNotification(TestCase tc) {
    Component component = tc.draw(components(), "component");
    ConfigEventType event = tc.draw(eventTypes(), "event");
    int registrations = tc.draw(integers().min(1).max(4), "registrations");

    StubManager manager = new StubManager(component);
    RecordingListener listener = new RecordingListener();
    for (int i = 0; i < registrations; i++) {
      manager.addConfigurationListener(listener);
    }

    manager.announce(event);

    assertEquals(1, listener.heard.size(), "one component heard the event " + listener.heard.size() + " times");
  }

  /**
   * A component that has deregistered hears nothing. It has usually shut down
   * by then, and delivering to it would run a reload against a dead component.
   */
  @HegelTest
  void aDeregisteredComponentHearsNothing(TestCase tc) {
    Component component = tc.draw(components(), "component");
    ConfigEventType event = tc.draw(eventTypes(), "event");
    boolean registerFirst = tc.draw(booleans(), "registerFirst");

    StubManager manager = new StubManager(component);
    RecordingListener leaving = new RecordingListener();
    RecordingListener staying = new RecordingListener();

    if (registerFirst) {
      manager.addConfigurationListener(leaving);
    }
    manager.addConfigurationListener(staying);
    manager.removeConfigurationListener(leaving);

    manager.announce(event);

    assertTrue(leaving.heard.isEmpty(), "a deregistered component was still notified");
    assertEquals(Arrays.asList(event), staying.heard);
  }

  /** Announcing to nobody is harmless; a publisher may notify before anyone starts. */
  @HegelTest
  void announcingToNobodyIsHarmless(TestCase tc) {
    Component component = tc.draw(components(), "component");
    ConfigEventType event = tc.draw(eventTypes(), "event");

    new StubManager(component).announce(event);
  }

  /**
   * A manager knows which component and project it speaks for. Both are used to
   * build the ZNode path it reads from, so they have to be the ones it was
   * constructed with, and an unspecified project has to be the default.
   */
  @HegelTest
  void aManagerKnowsWhichDeploymentItSpeaksFor(TestCase tc) {
    Component component = tc.draw(components(), "component");
    boolean explicitProject = tc.draw(booleans(), "explicitProject");
    String project = tc.draw(text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd"), "project");

    StubManager manager =
        explicitProject ? new StubManager(component, project) : new StubManager(component);

    assertSame(component, manager.getComponent());
    assertEquals(explicitProject ? project : Constants.DEFAULT_PROJECT, manager.getProject());
  }
}
