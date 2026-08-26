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

package org.apache.oodt.config.distributed;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.apache.oodt.config.Constants.DEFAULT_PROJECT;
import static org.apache.oodt.config.Constants.Properties.OODT_PROJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Arrays;
import java.util.List;
import org.apache.oodt.config.Component;
import org.apache.oodt.config.distributed.utils.ConfigUtils;

/**
 * Properties of the ZNode path arithmetic in {@link ZNodePaths}.
 *
 * <p>These paths are the addresses configuration is published to and read back
 * from, by two different processes. The publisher turns a local file name into
 * a ZNode path and the configuration manager turns that ZNode path back into a
 * local file name, so the two directions have to be inverses; and every path
 * has to be a legal Zookeeper path, which means a single leading slash and no
 * empty segments.
 */
class ZNodePathsPropertyTest {

  private static final String SEPARATOR = "/";

  /** A component name, or a project name, as an operator would write it. */
  private static Generator<String> name() {
    return text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");
  }

  /** A relative file name as it appears in a config-publisher bean file. */
  private static Generator<String> relativeFile() {
    return lists(text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd"))
        .minSize(1)
        .maxSize(3)
        .map(parts -> String.join(SEPARATOR, parts));
  }

  /**
   * Publishing a file and reading it back names the same file. The publisher
   * maps {@code etc/filemgr.properties} to a ZNode; the configuration manager
   * has to turn that ZNode back into {@code etc/filemgr.properties} to know
   * where to write it locally. Anything else puts the file in the wrong place.
   */
  @HegelTest
  void aPublishedFileNameSurvivesTheRoundTrip(TestCase tc) {
    String project = tc.draw(name(), "project");
    String component = tc.draw(name(), "component");
    String file = tc.draw(relativeFile(), "file");
    boolean leadingSlash = tc.draw(booleans(), "leadingSlash");
    boolean isProperties = tc.draw(booleans(), "isProperties");

    String written = leadingSlash ? SEPARATOR + file : file;
    ZNodePaths paths = new ZNodePaths(project, component);

    String zNodePath =
        isProperties ? paths.getPropertiesZNodePath(written) : paths.getConfigurationZNodePath(written);
    String local =
        isProperties
            ? paths.getLocalPropertiesFilePath(zNodePath)
            : paths.getLocalConfigFilePath(zNodePath);

    assertEquals(file, local, "the file name did not survive the trip through zookeeper");
  }

  /**
   * A published file lands underneath the area it belongs to. Clearing
   * configuration deletes the children of these two nodes, so a file published
   * outside them would never be cleared.
   */
  @HegelTest
  void aPublishedFileLandsUnderTheAreaItBelongsTo(TestCase tc) {
    String project = tc.draw(name(), "project");
    String component = tc.draw(name(), "component");
    String file = tc.draw(relativeFile(), "file");

    ZNodePaths paths = new ZNodePaths(project, component);

    assertTrue(
        paths.getPropertiesZNodePath(file).startsWith(paths.getPropertiesZNodePath() + SEPARATOR),
        "a properties file was published outside the properties area");
    assertTrue(
        paths
            .getConfigurationZNodePath(file)
            .startsWith(paths.getConfigurationZNodePath() + SEPARATOR),
        "a config file was published outside the configuration area");
  }

  /**
   * Every path Zookeeper is handed is a legal Zookeeper path: absolute, with no
   * empty segment and no trailing separator. Zookeeper rejects anything else,
   * and the publisher only finds out at publish time.
   */
  @HegelTest
  void everyPathIsALegalZookeeperPath(TestCase tc) {
    String project = tc.draw(name(), "project");
    String component = tc.draw(name(), "component");
    String file = tc.draw(relativeFile(), "file");

    ZNodePaths paths = new ZNodePaths(project, component);

    List<String> all =
        Arrays.asList(
            paths.getComponentZNodePath(),
            paths.getPropertiesZNodePath(),
            paths.getConfigurationZNodePath(),
            paths.getNotificationsZNodePath(),
            paths.getPropertiesZNodePath(file),
            paths.getConfigurationZNodePath(file));

    for (String path : all) {
      assertTrue(path.startsWith(SEPARATOR), "'" + path + "' is not absolute");
      assertFalse(path.endsWith(SEPARATOR), "'" + path + "' ends in a separator");
      assertFalse(path.contains("//"), "'" + path + "' contains an empty segment");
    }
  }

  /**
   * The three areas of a component are distinct nodes. Properties, other
   * configuration and the change notification all live side by side, and the
   * publisher writes to all three; a collision would have one overwrite
   * another.
   */
  @HegelTest
  void theThreeAreasOfAComponentAreDistinct(TestCase tc) {
    String project = tc.draw(name(), "project");
    String component = tc.draw(name(), "component");

    ZNodePaths paths = new ZNodePaths(project, component);

    assertNotEquals(paths.getPropertiesZNodePath(), paths.getConfigurationZNodePath());
    assertNotEquals(paths.getPropertiesZNodePath(), paths.getNotificationsZNodePath());
    assertNotEquals(paths.getConfigurationZNodePath(), paths.getNotificationsZNodePath());

    String root = paths.getComponentZNodePath() + SEPARATOR;
    assertTrue(paths.getPropertiesZNodePath().startsWith(root));
    assertTrue(paths.getConfigurationZNodePath().startsWith(root));
    assertTrue(paths.getNotificationsZNodePath().startsWith(root));
  }

  /**
   * Two components, or two projects, never share a node. Running two file
   * managers off one ensemble under different project names is the reason the
   * project segment exists.
   */
  @HegelTest
  void differentComponentsAndProjectsNeverShareANode(TestCase tc) {
    String projectA = tc.draw(name(), "projectA");
    String projectB = tc.draw(name(), "projectB");
    Component componentA = tc.draw(sampledFrom(Arrays.asList(Component.values())), "componentA");
    Component componentB = tc.draw(sampledFrom(Arrays.asList(Component.values())), "componentB");

    ZNodePaths a = new ZNodePaths(projectA, componentA.getName());
    ZNodePaths b = new ZNodePaths(projectB, componentB.getName());

    boolean sameConfiguration = projectA.equals(projectB) && componentA == componentB;
    assertEquals(
        sameConfiguration,
        a.getComponentZNodePath().equals(b.getComponentZNodePath()),
        "two different deployments share a component node");
  }

  /**
   * An unspecified project is the default project. {@code ConfigUtils} returns
   * null-free names, but the publisher's own constructor passes whatever it was
   * given, and both sides have to agree on where the default lives.
   */
  @HegelTest
  void anUnspecifiedProjectIsTheDefaultProject(TestCase tc) {
    String component = tc.draw(name(), "component");

    assertEquals(
        new ZNodePaths(DEFAULT_PROJECT, component).getComponentZNodePath(),
        new ZNodePaths(null, component).getComponentZNodePath());
  }

  /** A component with no name has no address, and that is reported at once. */
  @HegelTest
  void aComponentWithNoNameIsRejected(TestCase tc) {
    String project = tc.draw(name(), "project");

    assertThrows(IllegalArgumentException.class, () -> new ZNodePaths(project, null));
  }

  /**
   * The project name an operator supplies produces a path Zookeeper will
   * accept. This is exactly what {@code DistributedConfigurationManager} does
   * at start-up: it takes {@link ConfigUtils#getOODTProjectName()} and hands it
   * straight to {@link ZNodePaths}. A start script interpolating an unset shell
   * variable — {@code -Dorg.apache.oodt.config.project=$PROJECT} — supplies an
   * empty value, and the component has to either fall back to the default or
   * say what is wrong, not build a path and fail inside Zookeeper.
   */
  @HegelTest
  void theConfiguredProjectNameProducesAPathZookeeperAccepts(TestCase tc) {
    String configured =
        tc.draw(
            sampledFrom(Arrays.asList("", "   ", "default", "staging", "flight-ops")), "configured");
    Component component = tc.draw(sampledFrom(Arrays.asList(Component.values())), "component");

    String previous = System.getProperty(OODT_PROJECT);
    try {
      System.setProperty(OODT_PROJECT, configured);

      ZNodePaths paths = new ZNodePaths(ConfigUtils.getOODTProjectName(), component.getName());

      org.apache.zookeeper.common.PathUtils.validatePath(paths.getComponentZNodePath());
      org.apache.zookeeper.common.PathUtils.validatePath(paths.getPropertiesZNodePath());
      org.apache.zookeeper.common.PathUtils.validatePath(paths.getConfigurationZNodePath());
      org.apache.zookeeper.common.PathUtils.validatePath(paths.getNotificationsZNodePath());
    } finally {
      if (previous == null) {
        System.clearProperty(OODT_PROJECT);
      } else {
        System.setProperty(OODT_PROJECT, previous);
      }
    }
  }
}
