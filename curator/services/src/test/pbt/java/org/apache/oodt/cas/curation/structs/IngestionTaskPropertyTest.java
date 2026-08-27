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

package org.apache.oodt.cas.curation.structs;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Properties of {@link IngestionTask} and {@link ExtractorConfig}, the two
 * value objects the curation service builds from an HTTP request and later
 * writes back out as the description of an ingestion job.
 *
 * <p>They are round-tripped through a properties file and a JSON response, so
 * the only obligation that matters is that a task reads back as the task that
 * was described - nothing added, nothing dropped, and a task that has just been
 * created is safe to inspect before any field has been set.
 *
 * <p>No file named here is ever opened; {@link File} is used only as the
 * identifier type {@link ExtractorConfig} stores.
 */
class IngestionTaskPropertyTest {

  private static Generator<String> words() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  private static Generator<List<String>> fileNames() {
    return lists(integers().min(0).max(5).map(i -> "file" + i + ".dat")).maxSize(5);
  }

  /** Everything set on a task reads back unchanged. */
  @HegelTest
  void aTaskReadsBackAsItWasDescribed(TestCase tc) {
    String id = tc.draw(words(), "id");
    String policy = tc.draw(words(), "policy");
    String productType = tc.draw(words(), "productType");
    String status =
        tc.draw(
            sampledFrom(
                IngestionTaskStatus.NOT_STARTED,
                IngestionTaskStatus.STARTED,
                IngestionTaskStatus.FINISHED),
            "status");
    List<String> files = tc.draw(fileNames(), "files");
    long created = tc.draw(integers().min(0).max(1_000_000), "created").longValue();

    IngestionTask task = new IngestionTask();
    task.setId(id);
    task.setPolicy(policy);
    task.setProductType(productType);
    task.setStatus(status);
    task.setFileList(files);
    task.setCreateDate(new Date(created));

    assertEquals(id, task.getId());
    assertEquals(policy, task.getPolicy());
    assertEquals(productType, task.getProductType());
    assertEquals(status, task.getStatus());
    assertEquals(files, task.getFileList());
    assertEquals(new Date(created), task.getCreateDate());
  }

  /**
   * A newly created task is safe to inspect. The service builds one and then
   * fills it in field by field from the request, so every read in between has to
   * give an answer rather than fail.
   */
  @HegelTest
  void aFreshTaskIsSafeToInspect(TestCase tc) {
    String id = tc.draw(words(), "id");

    IngestionTask task = new IngestionTask();

    assertNotNull(task.getFileList(), "a fresh task has no file list");
    assertTrue(task.getFileList().isEmpty(), "a fresh task already has files");
    assertNotNull(task.getExtConf(), "a fresh task has no extractor configuration");

    task.setId(id);
    assertEquals(id, task.getId());
  }

  /** An extractor configuration reads back as the three things it was built from. */
  @HegelTest
  void anExtractorConfigReadsBackAsItWasBuilt(TestCase tc) {
    String identifier = tc.draw(words(), "identifier");
    String className = tc.draw(words(), "className");
    List<String> names = tc.draw(fileNames(), "configFileNames");

    List<File> configFiles = new ArrayList<File>();
    for (String name : names) {
      configFiles.add(new File(name));
    }

    ExtractorConfig config = new ExtractorConfig(identifier, className, configFiles);

    assertEquals(identifier, config.getIdentifier());
    assertEquals(className, config.getClassName());
    assertEquals(configFiles, config.getConfigFiles());
  }

  /** The task carries whichever extractor configuration it was given. */
  @HegelTest
  void aTaskCarriesItsExtractorConfig(TestCase tc) {
    String identifier = tc.draw(words(), "identifier");
    String className = tc.draw(words(), "className");

    IngestionTask task = new IngestionTask();
    task.setExtConf(new ExtractorConfig(identifier, className, new ArrayList<File>()));

    assertEquals(identifier, task.getExtConf().getIdentifier());
    assertEquals(className, task.getExtConf().getClassName());
  }
}
