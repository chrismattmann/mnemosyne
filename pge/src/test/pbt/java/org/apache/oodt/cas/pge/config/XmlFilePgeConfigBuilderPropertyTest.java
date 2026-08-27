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
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.apache.oodt.cas.pge.metadata.PgeTaskMetKeys.CONFIG_FILE_PATH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.pge.metadata.PgeMetadata;

/**
 * Parse properties of {@link XmlFilePgeConfigBuilder}.
 *
 * <p>This is the class that turns a PGE's XML configuration into the
 * {@link PgeConfig} CAS-PGE then acts on: which command to run, in which
 * directory, into which output directories. Each property generates such a
 * file into a fresh temporary directory and asserts that the configuration
 * built from it says what the file said.
 *
 * <p>No generated value contains a square bracket. An unresolvable
 * {@code [...]} expression sends
 * {@link org.apache.oodt.cas.pge.util.XmlHelper#fillIn} into a loop that
 * allocates while it spins; that is a separate known defect, and a test that
 * provoked it could not be recovered from.
 */
class XmlFilePgeConfigBuilderPropertyTest {

  /** A word a PGE author would write into a configuration file. */
  private static final Generator<String> WORD =
      text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");

  private static File freshDir() throws IOException {
    return Files.createTempDirectory("pge-pbt").toFile();
  }

  private static void delete(File dir) {
    File[] children = dir.listFiles();
    if (children != null) {
      for (File child : children) {
        delete(child);
      }
    }
    if (!dir.delete()) {
      dir.deleteOnExit();
    }
  }

  private static List<String> distinct(List<String> values) {
    Set<String> set = new LinkedHashSet<>(values);
    return new ArrayList<>(set);
  }

  /** Builds the config described by {@code xml}, written into {@code dir}. */
  private static PgeConfig build(File dir, String xml) throws Exception {
    File configFile = new File(dir, "pge-config.xml");
    Files.write(configFile.toPath(), xml.getBytes(StandardCharsets.UTF_8));

    Metadata staticMetadata = new Metadata();
    staticMetadata.addMetadata(CONFIG_FILE_PATH.getName(), configFile.getAbsolutePath());
    return new XmlFilePgeConfigBuilder().build(new PgeMetadata(staticMetadata, new Metadata()));
  }

  /**
   * The command CAS-PGE will run is the command the file specifies: same shell,
   * same working directory, same commands in the same order. This is the whole
   * point of the configuration file — everything else is preparation for
   * running it.
   */
  @HegelTest(testCases = 25)
  void theExecutionSectionIsReadAsWritten(TestCase tc) throws Exception {
    String shell = tc.draw(WORD, "shell");
    List<String> commands = tc.draw(lists(WORD).minSize(1).maxSize(5), "commands");

    File dir = freshDir();
    try {
      StringBuilder cmds = new StringBuilder();
      for (String command : commands) {
        cmds.append("<cmd>").append(command).append("</cmd>");
      }
      String xml = "<pgeConfig><exe dir=\"" + dir.getAbsolutePath()
          + "\" shellType=\"" + shell + "\">" + cmds + "</exe></pgeConfig>";

      PgeConfig config = build(dir, xml);

      assertEquals(dir.getAbsolutePath(), config.getExeDir(),
          "the execution directory changed");
      assertEquals(shell, config.getShellType(), "the shell type changed");
      assertEquals(commands, config.getExeCmds(), "the command list changed");
    } finally {
      delete(dir);
    }
  }

  /**
   * Every output directory declared in the file is in the configuration, with
   * the create-before-exe flag it was given. CAS-PGE creates these directories
   * before the run and crawls them afterwards, so a directory dropped here is a
   * directory whose products are never ingested.
   */
  @HegelTest(testCases = 25)
  void everyOutputDirectoryIsReadAsWritten(TestCase tc) throws Exception {
    List<String> names = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "names"));
    boolean createBeforeExe = tc.draw(booleans(), "createBeforeExe");

    File dir = freshDir();
    try {
      StringBuilder dirs = new StringBuilder();
      List<String> expectedPaths = new ArrayList<>();
      for (String name : names) {
        String path = new File(dir, name).getAbsolutePath();
        expectedPaths.add(path);
        dirs.append("<dir path=\"").append(path).append("\" createBeforeExe=\"")
            .append(createBeforeExe).append("\"/>");
      }
      String xml = "<pgeConfig><output>" + dirs + "</output></pgeConfig>";

      PgeConfig config = build(dir, xml);

      assertEquals(expectedPaths.size(), config.getOuputDirs().size(),
          "an output directory was gained or lost");
      List<String> actualPaths = new ArrayList<>();
      for (OutputDir outputDir : config.getOuputDirs()) {
        actualPaths.add(outputDir.getPath());
        assertEquals(createBeforeExe, outputDir.isCreateBeforeExe(),
            "the create-before-exe flag for [" + outputDir.getPath() + "] changed");
      }
      assertEquals(expectedPaths, actualPaths, "the output directory list changed");
    } finally {
      delete(dir);
    }
  }

  /**
   * Custom metadata declared in the file is metadata the PGE can see. These
   * entries are how a PGE author parameterises a run, and every later section
   * of the same file substitutes from them.
   */
  @HegelTest(testCases = 25)
  void customMetadataIsReadAsWritten(TestCase tc) throws Exception {
    List<String> keys = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "keys"));
    List<String> values = tc.draw(lists(WORD).minSize(1).maxSize(4), "values");

    File dir = freshDir();
    try {
      StringBuilder entries = new StringBuilder();
      for (int i = 0; i < keys.size(); i++) {
        entries.append("<metadata key=\"").append(keys.get(i))
            .append("\" val=\"").append(values.get(i % values.size())).append("\"/>");
      }
      String xml = "<pgeConfig><customMetadata>" + entries + "</customMetadata></pgeConfig>";

      File configFile = new File(dir, "pge-config.xml");
      Files.write(configFile.toPath(), xml.getBytes(StandardCharsets.UTF_8));

      Metadata staticMetadata = new Metadata();
      staticMetadata.addMetadata(CONFIG_FILE_PATH.getName(), configFile.getAbsolutePath());
      PgeMetadata pgeMetadata = new PgeMetadata(staticMetadata, new Metadata());

      assertNotNull(new XmlFilePgeConfigBuilder().build(pgeMetadata),
          "no configuration was built");

      for (int i = 0; i < keys.size(); i++) {
        assertEquals(values.get(i % values.size()), pgeMetadata.getMetadata(keys.get(i)),
            "custom metadata [" + keys.get(i) + "] changed");
      }
    } finally {
      delete(dir);
    }
  }

  /**
   * Every dynamic config file declared in the file is in the configuration,
   * with its path, its writer class and its arguments. CAS-PGE writes one file
   * per entry before the run; a lost entry means a PGE input file that is never
   * written.
   */
  @HegelTest(testCases = 25)
  void everyDynamicConfigFileIsReadAsWritten(TestCase tc) throws Exception {
    List<String> names = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "names"));
    String writerClass = "org.apache.oodt.cas.pge.writers.TextConfigFileWriter";
    String arg = tc.draw(WORD, "arg");

    File dir = freshDir();
    try {
      StringBuilder files = new StringBuilder();
      List<String> expectedPaths = new ArrayList<>();
      for (String name : names) {
        String path = new File(dir, name + ".txt").getAbsolutePath();
        expectedPaths.add(path);
        files.append("<file path=\"").append(path)
            .append("\" writerClass=\"").append(writerClass)
            .append("\" args=\"").append(arg).append("\"/>");
      }
      String xml = "<pgeConfig><dynInputFiles>" + files + "</dynInputFiles></pgeConfig>";

      PgeConfig config = build(dir, xml);

      assertEquals(expectedPaths.size(), config.getDynamicConfigFiles().size(),
          "a dynamic config file was gained or lost");
      List<String> actualPaths = new ArrayList<>();
      for (DynamicConfigFile dynamicConfigFile : config.getDynamicConfigFiles()) {
        actualPaths.add(dynamicConfigFile.getFilePath());
        assertEquals(writerClass, dynamicConfigFile.getWriterClass(),
            "the writer class changed for [" + dynamicConfigFile.getFilePath() + "]");
        assertEquals(1, dynamicConfigFile.getArgs().length,
            "the argument list changed length");
        assertEquals(arg, dynamicConfigFile.getArgs()[0], "the argument changed");
      }
      assertEquals(expectedPaths, actualPaths, "the dynamic config file list changed");
    } finally {
      delete(dir);
    }
  }

  /**
   * A configuration file that is not well-formed XML is reported as a failure
   * to build the configuration, not as an unchecked exception from somewhere
   * inside the parser. CAS-PGE catches {@link IOException} and fails the task
   * with a message an operator can act on.
   */
  @HegelTest(testCases = 20)
  void aMalformedFileIsReportedAsAFailureToBuild(TestCase tc) throws Exception {
    String junk = tc.draw(WORD, "junk");

    File dir = freshDir();
    try {
      String notXml = "<pgeConfig><exe dir=\"" + junk;
      assertThrows(IOException.class, () -> build(dir, notXml));
    } finally {
      delete(dir);
    }
  }

  /**
   * A configuration file that is not there at all is reported the same way. A
   * mistyped path in a workflow definition is the most likely way this class
   * ever fails in production.
   */
  @HegelTest(testCases = 20)
  void aMissingFileIsReportedAsAFailureToBuild(TestCase tc) throws Exception {
    String name = tc.draw(WORD, "name");

    File dir = freshDir();
    try {
      Metadata staticMetadata = new Metadata();
      staticMetadata.addMetadata(CONFIG_FILE_PATH.getName(),
          new File(dir, name + "-absent.xml").getAbsolutePath());
      PgeMetadata pgeMetadata = new PgeMetadata(staticMetadata, new Metadata());

      assertThrows(IOException.class, () -> new XmlFilePgeConfigBuilder().build(pgeMetadata));
    } finally {
      delete(dir);
    }
  }
}
