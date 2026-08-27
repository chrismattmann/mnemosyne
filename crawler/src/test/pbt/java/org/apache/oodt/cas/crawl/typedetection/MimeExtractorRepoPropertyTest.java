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

package org.apache.oodt.cas.crawl.typedetection;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.FileNotFoundException;
import java.util.LinkedList;
import java.util.List;

/**
 * Properties of the mime-type to naming-convention mapping in
 * {@link MimeExtractorRepo}.
 *
 * <p>Only the in-memory side of the repository is stated here: the naming
 * convention chosen for a product's mime type, and the fallback to the default
 * extractors. The mime-type hierarchy walk needs a mime repository file on disk
 * and is left alone.
 */
class MimeExtractorRepoPropertyTest {

  private static final String OCTET_STREAM = "application/octet-stream";

  private static Generator<String> mimeType() {
    return text().minSize(1).maxSize(10).categories("Ll", "Nd").map(s -> "application/" + s);
  }

  private static Generator<String> beanId() {
    return text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");
  }

  private static MimeExtractorRepo emptyRepo() throws FileNotFoundException {
    return new MimeExtractorRepo();
  }

  /**
   * A naming convention registered for a mime type is the one that mime type
   * gets. This mapping decides what a product is renamed to before ingest, so a
   * lookup that missed would rename the product by the wrong rule.
   */
  @HegelTest
  void aRegisteredNamingConventionIsTheOneThatMimeTypeGets(TestCase tc)
      throws FileNotFoundException {
    String mimeType = tc.draw(mimeType(), "mimeType");
    String namingConventionId = tc.draw(beanId(), "namingConventionId");

    MimeExtractorRepo repo = emptyRepo();
    repo.setNamingConventionId(mimeType, namingConventionId);

    assertEquals(namingConventionId, repo.getNamingConventionId(mimeType));
  }

  /**
   * A mime type with no convention of its own falls back to the default. The
   * XML lets an author declare a default and then override it per mime type,
   * and every unmentioned type must land on the default.
   */
  @HegelTest
  void anUnmentionedMimeTypeFallsBackToTheDefault(TestCase tc) throws FileNotFoundException {
    String configured = tc.draw(mimeType(), "configured");
    String asked = tc.draw(mimeType(), "asked");
    String namingConventionId = tc.draw(beanId(), "namingConventionId");
    String defaultId = tc.draw(beanId(), "defaultId");

    MimeExtractorRepo repo = emptyRepo();
    repo.setDefaultNamingConventionId(defaultId);
    repo.setNamingConventionId(configured, namingConventionId);

    String expected = asked.equals(configured) ? namingConventionId : defaultId;
    assertEquals(expected, repo.getNamingConventionId(asked));
  }

  /**
   * Registering a convention for one mime type does not disturb another. Each
   * {@code <mime>} element in the config is independent of the others.
   */
  @HegelTest
  void mimeTypesDoNotDisturbEachOther(TestCase tc) throws FileNotFoundException {
    List<String> mimeTypes = tc.draw(lists(mimeType()).minSize(1).maxSize(6), "mimeTypes");
    String namingConventionId = tc.draw(beanId(), "namingConventionId");

    MimeExtractorRepo repo = emptyRepo();
    for (String mimeType : mimeTypes) {
      repo.setNamingConventionId(mimeType, namingConventionId + mimeType);
    }

    for (String mimeType : mimeTypes) {
      assertEquals(namingConventionId + mimeType, repo.getNamingConventionId(mimeType));
    }
  }

  /**
   * A file whose mime type could not be determined still gets the default
   * extractors. That is the whole point of declaring a {@code <default>} block:
   * an unrecognised file is still crawled.
   */
  @HegelTest
  void anUnrecognisedFileStillGetsTheDefaultExtractors(TestCase tc)
      throws FileNotFoundException {
    int count = tc.draw(dev.hegel.Generators.integers().min(0).max(4), "count");

    LinkedList<MetExtractorSpec> defaults = new LinkedList<>();
    for (int i = 0; i < count; i++) {
      defaults.add(new MetExtractorSpec());
    }

    MimeExtractorRepo repo = emptyRepo();
    repo.setDefaultMetExtractorSpecs(defaults);

    assertEquals(defaults, repo.getExtractorSpecsForMimeType(null), "no mime type");
    assertEquals(defaults, repo.getExtractorSpecsForMimeType(OCTET_STREAM), "octet-stream");
  }

  /**
   * The default extractor list is always a list. The crawler iterates it
   * directly for any file it cannot type, so a repository whose defaults were
   * never configured must still offer something to walk.
   */
  @HegelTest
  void theDefaultExtractorListIsAlwaysAListToWalk(TestCase tc) throws FileNotFoundException {
    tc.note("a config with no <default> block");

    MimeExtractorRepo repo = emptyRepo();
    repo.setDefaultMetExtractorSpecs(null);

    List<MetExtractorSpec> defaults = repo.getDefaultMetExtractorSpecs();
    assertNotNull(defaults, "the default extractor list was null");
    assertTrue(defaults.isEmpty());
    assertNotNull(repo.getExtractorSpecsForMimeType(null), "no specs offered for an untyped file");
  }
}
