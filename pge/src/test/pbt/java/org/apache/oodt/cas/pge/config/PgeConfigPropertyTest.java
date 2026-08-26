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

package org.apache.oodt.cas.pge.config;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Properties of {@link PgeConfig}, the assembled description of one PGE run:
 * where the executable lives, what to run, which shell to run it under, which
 * files to write first and which directories to harvest afterwards.
 *
 * <p>The config is built up field by field by {@code XmlFilePgeConfigBuilder}
 * and then read back by the task instance that runs the PGE. Whatever was put in
 * has to come back, in the order it was put in where order matters, and the
 * defaults the class chooses for a caller who says nothing have to be usable.
 */
class PgeConfigPropertyTest {

  /** Two usable shells and the two settings the class is documented to ignore. */
  private static final String[] SHELL_TYPE_SETTINGS = {"bash", "csh", "", null};

  private static Generator<String> paths() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  private static Generator<List<String>> commands() {
    return lists(text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd")).maxSize(5);
  }

  /**
   * The executable directory and commands come back exactly as they went in, in
   * order. These are concatenated into a shell script, so a reordered or dropped
   * command runs a different program than the one that was configured.
   */
  @HegelTest
  void theExecutableSectionRoundTrips(TestCase tc) {
    String exeDir = tc.draw(paths(), "exeDir");
    List<String> exeCmds = tc.draw(commands(), "exeCmds");

    PgeConfig config = new PgeConfig();
    config.setExeDir(exeDir);
    config.setExeCmds(exeCmds);

    assertEquals(exeDir, config.getExeDir());
    assertEquals(exeCmds, config.getExeCmds());
  }

  /**
   * The shell type is never left blank. The class defaults it to {@code sh} and
   * deliberately ignores a null or empty setting, because the value is used
   * directly as the interpreter for the generated script - an empty one produces
   * a script that cannot be run.
   */
  @HegelTest
  void theShellTypeIsNeverBlank(TestCase tc) {
    List<Integer> choices =
        tc.draw(lists(integers().min(0).max(3)).maxSize(4), "shellTypeSettings");

    List<String> settings = new ArrayList<String>();
    for (Integer choice : choices) {
      settings.add(SHELL_TYPE_SETTINGS[choice]);
    }

    PgeConfig config = new PgeConfig();
    assertEquals("sh", config.getShellType(), "a fresh config has no default shell");

    String lastUsable = "sh";
    for (String setting : settings) {
      config.setShellType(setting);
      if (setting != null && !setting.isEmpty()) {
        lastUsable = setting;
      }
      assertEquals(lastUsable, config.getShellType(), "after setting '" + setting + "'");
      assertTrue(config.getShellType().length() > 0, "shell type went blank");
    }
  }

  /**
   * Output directories come back in the order they were added. The PGE harvests
   * them in turn and the first matching expression wins, so order is part of the
   * configuration's meaning.
   */
  @HegelTest
  void outputDirsComeBackInTheOrderTheyWereAdded(TestCase tc) {
    List<String> paths = tc.draw(commands(), "paths");

    PgeConfig config = new PgeConfig();
    List<String> expected = new ArrayList<String>();
    for (String path : paths) {
      boolean createBeforeExe = tc.draw(booleans(), "createBeforeExe." + expected.size());
      config.addOuputDirAndExpressions(new OutputDir(path, createBeforeExe));
      expected.add(path + ":" + createBeforeExe);
    }

    List<String> actual = new ArrayList<String>();
    for (OutputDir dir : config.getOuputDirs()) {
      actual.add(dir.getPath() + ":" + dir.isCreateBeforeExe());
    }
    assertEquals(expected, actual);
  }

  /** Dynamic config files come back in the order they were added. */
  @HegelTest
  void dynamicConfigFilesComeBackInTheOrderTheyWereAdded(TestCase tc) {
    List<String> paths = tc.draw(commands(), "paths");

    PgeConfig config = new PgeConfig();
    for (String path : paths) {
      config.addDynamicConfigFile(new DynamicConfigFile(path, "writer", new Object[0]));
    }

    List<String> actual = new ArrayList<String>();
    for (DynamicConfigFile file : config.getDynamicConfigFiles()) {
      actual.add(file.getFilePath());
    }
    assertEquals(paths, actual);
  }

  /**
   * The custom arguments handed to the property adder are never null. The caller
   * spreads them into a varargs invocation, and a config that never set them is
   * the common case.
   */
  @HegelTest
  void propertyAdderArgsAreNeverNull(TestCase tc) {
    boolean setThem = tc.draw(booleans(), "setThem");
    List<String> args = tc.draw(commands(), "args");

    PgeConfig config = new PgeConfig();
    if (setThem) {
      config.setPropertyAdderCustomArgs(args.toArray());
    }

    assertNotNull(config.getPropertyAdderCustomArgs());
    assertEquals(setThem ? args.size() : 0, config.getPropertyAdderCustomArgs().length);
  }

  /** A dynamic config file gives back the three things it was built from. */
  @HegelTest
  void aDynamicConfigFileRoundTrips(TestCase tc) {
    String path = tc.draw(paths(), "path");
    String writer = tc.draw(paths(), "writer");
    List<String> args = tc.draw(commands(), "args");

    DynamicConfigFile file = new DynamicConfigFile(path, writer, args.toArray());

    assertEquals(path, file.getFilePath());
    assertEquals(writer, file.getWriterClass());
    assertEquals(args, new ArrayList<Object>(java.util.Arrays.asList(file.getArgs())));
  }

  /** The staging section comes back as it was set. */
  @HegelTest
  void theFileStagingSectionRoundTrips(TestCase tc) {
    String stagingDir = tc.draw(paths(), "stagingDir");
    boolean force = tc.draw(booleans(), "force");

    PgeConfig config = new PgeConfig();
    config.setFileStagingInfo(new FileStagingInfo(stagingDir, force));

    assertEquals(stagingDir, config.getFileStagingInfo().getStagingDir());
    assertEquals(force, config.getFileStagingInfo().isForceStaging());
  }
}
