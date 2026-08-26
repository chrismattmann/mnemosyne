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

package org.apache.oodt.config.distributed.utils;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.apache.oodt.config.Constants.DEFAULT_PROJECT;
import static org.apache.oodt.config.Constants.Properties.OODT_PROJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Arrays;
import org.apache.oodt.config.Component;

/**
 * Properties of the path and project resolution in {@link ConfigUtils}.
 *
 * <p>{@code fixForComponentHome} is what turns the relative file name in a
 * config-publisher bean file into the absolute path the running component will
 * read. It is the only place the {@code ${COMPONENT}_HOME} convention is
 * applied, so it decides where every downloaded configuration file lands.
 *
 * <p>The component home is read from a system property, so every property here
 * puts that property back the way it found it.
 */
class ConfigUtilsPropertyTest {

  private static final String SEPARATOR = "/";

  private static Generator<String> segment() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  /** A relative configuration file name, such as {@code etc/filemgr.properties}. */
  private static Generator<String> relativePath() {
    return lists(segment()).minSize(1).maxSize(3).map(parts -> String.join(SEPARATOR, parts));
  }

  private static Generator<Component> components() {
    return sampledFrom(Arrays.asList(Component.values()));
  }

  /** Runs {@code body} with the component's home set, then restores it. */
  private static void withHome(Component component, String home, Runnable body) {
    String previous = System.getProperty(component.getHome());
    try {
      if (home == null) {
        System.clearProperty(component.getHome());
      } else {
        System.setProperty(component.getHome(), home);
      }
      body.run();
    } finally {
      if (previous == null) {
        System.clearProperty(component.getHome());
      } else {
        System.setProperty(component.getHome(), previous);
      }
    }
  }

  /**
   * A file resolved against a component home lands inside that home, exactly
   * once. The component is started from its home directory and reads the file
   * from there, so a path that lost the prefix — or doubled it — points at
   * nothing.
   */
  @HegelTest
  void aFileResolvedAgainstAHomeLandsInsideIt(TestCase tc) {
    Component component = tc.draw(components(), "component");
    String home = tc.draw(relativePath(), "home");
    String suffix = tc.draw(relativePath(), "suffix");
    boolean absoluteSuffix = tc.draw(booleans(), "absoluteSuffix");
    boolean homeEndsInSeparator = tc.draw(booleans(), "homeEndsInSeparator");

    String prefix = SEPARATOR + home + (homeEndsInSeparator ? SEPARATOR : "");
    String written = absoluteSuffix ? SEPARATOR + suffix : suffix;

    withHome(
        component,
        prefix,
        () -> {
          String fixed = ConfigUtils.fixForComponentHome(component, written);

          assertEquals(SEPARATOR + home + SEPARATOR + suffix, fixed);
          assertTrue(fixed.startsWith(SEPARATOR + home), "the file left the component home");
          assertTrue(fixed.endsWith(suffix), "the file name was changed");
          assertFalse(fixed.contains("//"), "'" + fixed + "' contains an empty path segment");
        });
  }

  /**
   * Whitespace around a home directory is not part of the path. A
   * {@code FILEMGR_HOME} picked up from a shell script commonly carries a
   * trailing newline or space, and that must not become part of a directory
   * name.
   */
  @HegelTest
  void whitespaceAroundTheHomeIsNotPartOfThePath(TestCase tc) {
    Component component = tc.draw(components(), "component");
    String home = tc.draw(relativePath(), "home");
    String suffix = tc.draw(relativePath(), "suffix");
    int leading = tc.draw(integers().min(0).max(3), "leading");
    int trailing = tc.draw(integers().min(0).max(3), "trailing");

    String padded = " ".repeat(leading) + SEPARATOR + home + " ".repeat(trailing);

    withHome(
        component,
        padded,
        () ->
            assertEquals(
                SEPARATOR + home + SEPARATOR + suffix,
                ConfigUtils.fixForComponentHome(component, suffix)));
  }

  /**
   * With no home set, the file name is relative to wherever the component was
   * started. There is nothing to prefix with, so the name must come back
   * usable as-is rather than as an absolute path into the filesystem root.
   */
  @HegelTest
  void withNoHomeTheFileNameStaysRelative(TestCase tc) {
    Component component = tc.draw(components(), "component");
    tc.assume(System.getenv(component.getHome()) == null);
    String suffix = tc.draw(relativePath(), "suffix");
    boolean absoluteSuffix = tc.draw(booleans(), "absoluteSuffix");
    boolean blankHome = tc.draw(booleans(), "blankHome");

    String written = absoluteSuffix ? SEPARATOR + suffix : suffix;

    withHome(
        component,
        blankHome ? "   " : null,
        () -> {
          String fixed = ConfigUtils.fixForComponentHome(component, written);

          assertEquals(suffix, fixed);
          assertFalse(fixed.startsWith(SEPARATOR), "an unanchored file name became absolute");
        });
  }

  /**
   * Each component resolves against its own home. The three components share
   * one publisher process, and a file for the file manager must not be resolved
   * against the workflow manager's home.
   */
  @HegelTest
  void eachComponentResolvesAgainstItsOwnHome(TestCase tc) {
    Component owner = tc.draw(components(), "owner");
    Component other = tc.draw(components(), "other");
    String home = tc.draw(relativePath(), "home");
    String suffix = tc.draw(relativePath(), "suffix");

    withHome(
        owner,
        SEPARATOR + home,
        () -> {
          String forOwner = ConfigUtils.fixForComponentHome(owner, suffix);
          assertEquals(SEPARATOR + home + SEPARATOR + suffix, forOwner);

          if (other != owner && System.getProperty(other.getHome()) == null
              && System.getenv(other.getHome()) == null) {
            assertEquals(suffix, ConfigUtils.fixForComponentHome(other, suffix));
          }
        });
  }

  /**
   * The project name is the one the operator set, or the default. The project
   * is a segment of every ZNode path, so a component reading a different
   * project name from the publisher reads someone else's configuration.
   */
  @HegelTest
  void theProjectNameIsWhatWasSetOrTheDefault(TestCase tc) {
    tc.assume(System.getenv("OODT_PROJECT") == null);
    boolean configured = tc.draw(booleans(), "configured");
    String project = tc.draw(segment(), "project");

    String previous = System.getProperty(OODT_PROJECT);
    try {
      if (configured) {
        System.setProperty(OODT_PROJECT, project);
        assertEquals(project, ConfigUtils.getOODTProjectName());
      } else {
        System.clearProperty(OODT_PROJECT);
        assertEquals(DEFAULT_PROJECT, ConfigUtils.getOODTProjectName());
      }
    } finally {
      if (previous == null) {
        System.clearProperty(OODT_PROJECT);
      } else {
        System.setProperty(OODT_PROJECT, previous);
      }
    }
  }

}
