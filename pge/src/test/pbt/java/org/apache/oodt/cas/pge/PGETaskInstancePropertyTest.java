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

package org.apache.oodt.cas.pge;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.apache.oodt.cas.pge.metadata.PgeTaskMetKeys.LOG_FILENAME_PATTERN;
import static org.apache.oodt.cas.pge.metadata.PgeTaskMetKeys.NAME;
import static org.apache.oodt.cas.pge.metadata.PgeTaskMetKeys.PROPERTY_ADDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.crawl.ProductCrawler;
import org.apache.oodt.cas.crawl.status.IngestStatus;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.pge.config.DynamicConfigFile;
import org.apache.oodt.cas.pge.config.OutputDir;
import org.apache.oodt.cas.pge.config.PgeConfig;
import org.apache.oodt.cas.pge.exceptions.PGEException;
import org.apache.oodt.cas.pge.metadata.PgeMetadata;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * Properties of {@link PGETaskInstance}, the CAS-PGE task body.
 *
 * <p>The class is built to be extended — its constructor and almost all of its
 * behaviour are {@code protected} — so the properties here drive it through a
 * small subclass, exactly as a real PGE integration would. The workflow manager
 * is an in-memory {@link StubWorkflowManagerClient} and every directory is a
 * fresh temporary one, deleted in a {@code finally} block.
 */
class PGETaskInstancePropertyTest {

  /** A word a workflow author would write into a task configuration. */
  private static final Generator<String> WORD =
      text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");

  /** A task subclass that lets a property set the state {@code run} would build. */
  private static final class TestableTask extends PGETaskInstance {
    TestableTask(PgeMetadata pgeMetadata, PgeConfig pgeConfig) {
      this.pgeMetadata = pgeMetadata;
      this.pgeConfig = pgeConfig;
    }

    PgeMetadata metadata() {
      return pgeMetadata;
    }

    PgeConfig config() {
      return pgeConfig;
    }

    PgeMetadata buildMetadata(Metadata dynamic, WorkflowTaskConfiguration config) {
      return createPgeMetadata(dynamic, config);
    }

    void makeExeDir() throws PGEException {
      createExeDir();
    }

    void makeOutputDirs() throws PGEException {
      createOuputDirsIfRequested();
    }

    void writeDynamicConfigFiles() throws IOException, PGEException {
      createDynamicConfigFiles();
    }

    void report(String status) throws Exception {
      updateStatus(status);
    }

    void checkIngests(ProductCrawler crawler) throws PGEException {
      verifyIngests(crawler);
    }

    String logFileName() {
      return createLogFileName();
    }
  }

  /** A crawler that reports the ingest results a property gave it. */
  private static final class StubCrawler extends ProductCrawler {
    StubCrawler(List<IngestStatus> statuses) {
      this.ingestStatus.addAll(statuses);
    }

    @Override
    protected boolean passesPreconditions(File product) {
      return true;
    }

    @Override
    protected Metadata getMetadataForProduct(File product) {
      return new Metadata();
    }

    @Override
    protected File renameProduct(File product, Metadata productMetadata) {
      return product;
    }
  }

  /** An ingest outcome for one file. */
  private static final class StubIngestStatus implements IngestStatus {
    private final File product;
    private final Result result;
    private final String message;

    StubIngestStatus(File product, Result result, String message) {
      this.product = product;
      this.result = result;
      this.message = message;
    }

    @Override
    public File getProduct() {
      return product;
    }

    @Override
    public Result getResult() {
      return result;
    }

    @Override
    public String getMessage() {
      return message;
    }
  }

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

  /**
   * Everything the workflow put in the task configuration is visible to the
   * PGE, and a key CAS-PGE knows to be a list arrives as a list rather than as
   * one comma-glued string. Property adders are exactly such a key: each entry
   * is a class name to load, so a configuration of three that arrives as one
   * runs none of them.
   */
  @HegelTest
  void theTaskConfigurationBecomesMetadataTheePgeCanSee(TestCase tc) {
    String pgeName = tc.draw(WORD, "pgeName");
    List<String> adders = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "adders"));
    String dynamicKey = tc.draw(WORD, "dynamicKey");
    String dynamicValue = tc.draw(WORD, "dynamicValue");

    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty(NAME.getName(), pgeName);
    config.addConfigProperty(PROPERTY_ADDERS.getName(), String.join(",", adders));

    Metadata dynamic = new Metadata();
    dynamic.addMetadata(dynamicKey, dynamicValue);

    PgeMetadata built =
        new TestableTask(null, null).buildMetadata(dynamic, config);

    assertEquals(pgeName, built.getMetadata(NAME.getName()),
        "the PGE name did not survive the configuration");
    assertEquals(adders, built.getAllMetadata(PROPERTY_ADDERS.getName()),
        "the property adder list was not split into separate entries");
    assertEquals(dynamicValue, built.getMetadata(dynamicKey),
        "the workflow's own metadata was lost");
  }

  /**
   * Preparing the execution directory leaves a directory there, whether or not
   * one already existed. CAS-PGE writes the run script, the logs and the
   * metadata dump into it, so the step has to be safe to repeat — a retried
   * workflow runs it again.
   */
  @HegelTest(testCases = 30)
  void preparingTheExecutionDirectoryIsRepeatable(TestCase tc) throws Exception {
    List<String> segments = tc.draw(lists(WORD).minSize(1).maxSize(3), "segments");

    File root = freshDir();
    try {
      File exeDir = root;
      for (String segment : segments) {
        exeDir = new File(exeDir, segment);
      }

      PgeConfig config = new PgeConfig();
      config.setExeDir(exeDir.getAbsolutePath());
      TestableTask task = new TestableTask(new PgeMetadata(), config);

      task.makeExeDir();
      assertTrue(exeDir.isDirectory(), "the execution directory was not created");

      task.makeExeDir();
      assertTrue(exeDir.isDirectory(), "running the step twice removed the directory");
    } finally {
      delete(root);
    }
  }

  /**
   * Exactly the output directories flagged for creation are created, and the
   * others are left alone. The flag exists because some output directories are
   * produced by the PGE itself; creating one early can change what the PGE
   * does, and failing to create a flagged one makes the PGE fail.
   */
  @HegelTest(testCases = 30)
  void onlyTheFlaggedOutputDirectoriesAreCreated(TestCase tc) throws Exception {
    List<String> names = distinct(tc.draw(lists(WORD).minSize(1).maxSize(5), "names"));
    List<Boolean> flags = tc.draw(lists(booleans()).minSize(1).maxSize(5), "flags");

    File root = freshDir();
    try {
      PgeConfig config = new PgeConfig();
      config.setExeDir(root.getAbsolutePath());

      List<File> dirs = new ArrayList<>();
      List<Boolean> expected = new ArrayList<>();
      for (int i = 0; i < names.size(); i++) {
        File dir = new File(root, names.get(i));
        boolean create = flags.get(i % flags.size());
        dirs.add(dir);
        expected.add(create);
        config.addOuputDirAndExpressions(new OutputDir(dir.getAbsolutePath(), create));
      }

      new TestableTask(new PgeMetadata(), config).makeOutputDirs();

      for (int i = 0; i < dirs.size(); i++) {
        assertEquals(expected.get(i), dirs.get(i).isDirectory(),
            "output directory [" + dirs.get(i) + "] was "
                + (expected.get(i) ? "not created" : "created without being asked for"));
      }
    } finally {
      delete(root);
    }
  }

  /**
   * Every dynamic config file the configuration declares ends up on disk. The
   * PGE reads these as its input; one missing is a PGE started without part of
   * its input.
   */
  @HegelTest(testCases = 25)
  void everyDeclaredConfigFileIsWritten(TestCase tc) throws Exception {
    List<String> names = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "names"));
    String template = tc.draw(WORD, "template");

    File root = freshDir();
    try {
      PgeConfig config = new PgeConfig();
      config.setExeDir(root.getAbsolutePath());

      List<File> targets = new ArrayList<>();
      for (String name : names) {
        File target = new File(new File(root, name), name + ".txt");
        targets.add(target);
        config.addDynamicConfigFile(new DynamicConfigFile(
            target.getAbsolutePath(),
            "org.apache.oodt.cas.pge.writers.TextConfigFileWriter",
            new String[] {template}));
      }

      new TestableTask(new PgeMetadata(), config).writeDynamicConfigFiles();

      for (File target : targets) {
        assertTrue(target.isFile(), "config file [" + target + "] was never written");
      }
    } finally {
      delete(root);
    }
  }

  /**
   * A status reaches the workflow manager verbatim, and a workflow manager that
   * refuses the update stops the task. CAS-PGE's status line is the only view a
   * user has of a running PGE; an update that is silently dropped leaves the
   * workflow showing the wrong state forever.
   */
  @HegelTest
  void statusUpdatesAreForwardedAndRefusalsAreFatal(TestCase tc) throws Exception {
    String instanceId = tc.draw(WORD, "instanceId");
    String status = tc.draw(WORD, "status");
    boolean accepting = tc.draw(booleans(), "accepting");

    StubWorkflowManagerClient client = new StubWorkflowManagerClient(accepting);
    TestableTask task = new TestableTask(new PgeMetadata(), new PgeConfig());
    task.setWmClient(client);
    task.setWorkflowInstId(instanceId);

    if (accepting) {
      task.report(status);
    } else {
      assertThrows(PGEException.class, () -> task.report(status));
    }

    assertEquals(1, client.getStatusUpdates().size(), "the status update never arrived");
    assertEquals(instanceId, client.getStatusUpdates().get(0)[0], "the instance id changed");
    assertEquals(status, client.getStatusUpdates().get(0)[1], "the status changed");
  }

  /**
   * Ingest verification fails exactly when something failed to ingest, and when
   * it fails it names every file that failed.
   *
   * <p>This is the roll-up an operator reads after a PGE run. A SKIPPED or
   * PRECONDS_FAILED product is not a failure — it is a product the crawler
   * deliberately passed over — so only FAILURE may stop the task, and every
   * FAILURE has to be in the message or the operator cannot tell which product
   * to look at.
   */
  @HegelTest(testCases = 40)
  void ingestVerificationFailsExactlyOnFailuresAndNamesThemAll(TestCase tc) throws Exception {
    List<String> names = distinct(tc.draw(lists(WORD).minSize(0).maxSize(6), "names"));
    List<IngestStatus.Result> results = tc.draw(
        lists(dev.hegel.Generators.sampledFrom(List.of(
            IngestStatus.Result.SUCCESS,
            IngestStatus.Result.FAILURE,
            IngestStatus.Result.SKIPPED,
            IngestStatus.Result.PRECONDS_FAILED)))
            .minSize(1).maxSize(6),
        "results");

    File root = freshDir();
    try {
      List<IngestStatus> statuses = new ArrayList<>();
      List<File> failed = new ArrayList<>();
      for (int i = 0; i < names.size(); i++) {
        File product = new File(root, names.get(i));
        IngestStatus.Result result = results.get(i % results.size());
        if (result == IngestStatus.Result.FAILURE) {
          failed.add(product);
        }
        statuses.add(new StubIngestStatus(product, result, "because"));
      }

      TestableTask task = new TestableTask(new PgeMetadata(), new PgeConfig());
      StubCrawler crawler = new StubCrawler(statuses);

      if (failed.isEmpty()) {
        task.checkIngests(crawler);
      } else {
        PGEException thrown =
            assertThrows(PGEException.class, () -> task.checkIngests(crawler));
        for (File product : failed) {
          assertTrue(thrown.getMessage().contains(product.getAbsolutePath()),
              "the failure report does not name [" + product + "]: " + thrown.getMessage());
        }
      }
    } finally {
      delete(root);
    }
  }

  /**
   * The log file name is the configured pattern when there is one, and is built
   * from the PGE name when there is not. Operators find a run's log by this
   * name; a configured pattern that is ignored puts the log somewhere nobody
   * looks.
   */
  @HegelTest
  void theLogFileNameHonoursTheConfiguredPattern(TestCase tc) {
    // The literal "null" is excluded as a PGE name: the last assertion below
    // is about an absent value leaking into the filename, and a PGE genuinely
    // called "null" would trip it for the wrong reason.
    String pgeName = tc.draw(WORD.filter(word -> !"null".equals(word)), "pgeName");
    String pattern = tc.draw(WORD, "pattern");
    boolean patternConfigured = tc.draw(booleans(), "patternConfigured");

    Metadata staticMetadata = new Metadata();
    staticMetadata.addMetadata(NAME.getName(), pgeName);
    if (patternConfigured) {
      staticMetadata.addMetadata(LOG_FILENAME_PATTERN.getName(), pattern);
    }

    TestableTask task = new TestableTask(
        new PgeMetadata(staticMetadata, new Metadata()), new PgeConfig());

    String name = task.logFileName();
    if (patternConfigured) {
      assertEquals(pattern, name, "the configured log filename pattern was ignored");
    } else {
      assertTrue(name.startsWith(pgeName + "."),
          "the log file name does not identify the PGE: " + name);
      assertTrue(name.endsWith(".log"), "the log file name is not a log file: " + name);
      assertFalse(name.contains("null"), "the log file name contains the text 'null': " + name);
    }
  }
}
