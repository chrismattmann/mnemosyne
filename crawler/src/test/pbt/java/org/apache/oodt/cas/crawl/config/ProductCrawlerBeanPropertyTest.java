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

package org.apache.oodt.cas.crawl.config;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of the Spring-facing state in {@link ProductCrawlerBean}.
 *
 * <p>Everything here is what a crawler bean file promises the crawler: the
 * three metadata fields the file manager cannot ingest without are always
 * required, whatever else the author adds; and reading the bean's state back
 * cannot let a caller quietly change it.
 */
class ProductCrawlerBeanPropertyTest {

  /** {@link ProductCrawlerBean} is abstract only so that Spring subclasses it. */
  private static final class Bean extends ProductCrawlerBean {}

  private static Generator<String> metadataKey() {
    return text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");
  }

  /**
   * The three core keys are required no matter what the bean file says. The
   * crawler refuses to ingest a product missing any of them, and a bean file
   * that lists its own required fields must not be able to drop them.
   */
  @HegelTest
  void theCoreKeysAreAlwaysRequired(TestCase tc) {
    List<String> extra = tc.draw(lists(metadataKey()).maxSize(8), "extra");

    Bean bean = new Bean();
    bean.setRequiredMetadata(extra);

    List<String> required = bean.getRequiredMetadata();
    assertTrue(required.contains(ProductCrawlerBean.PRODUCT_TYPE), "ProductType is not required");
    assertTrue(required.contains(ProductCrawlerBean.FILENAME), "Filename is not required");
    assertTrue(required.contains(ProductCrawlerBean.FILE_LOCATION), "FileLocation is not required");
    assertTrue(required.containsAll(extra), "a configured required field was dropped");
  }

  /**
   * The required set is a set: a field named twice is checked once. The crawler
   * walks this list for every single product, and a duplicate would mean the
   * same lookup twice per file.
   */
  @HegelTest
  void requiredFieldsAreNotDuplicated(TestCase tc) {
    List<String> extra = tc.draw(lists(metadataKey()).maxSize(8), "extra");

    Bean bean = new Bean();
    bean.setRequiredMetadata(extra);

    List<String> required = bean.getRequiredMetadata();
    Set<String> distinct = new HashSet<>(required);
    assertEquals(distinct.size(), required.size(), "the required fields contain a duplicate: " + required);
  }

  /**
   * Reading the required fields hands out a copy. The crawler reads this list
   * once per product; if the list were the bean's own, a caller iterating it
   * could change what every later product is checked against.
   */
  @HegelTest
  void readingTheRequiredFieldsCannotChangeThem(TestCase tc) {
    List<String> extra = tc.draw(lists(metadataKey()).maxSize(6), "extra");
    String intruder = tc.draw(metadataKey(), "intruder");

    Bean bean = new Bean();
    bean.setRequiredMetadata(extra);

    List<String> handedOut = bean.getRequiredMetadata();
    handedOut.clear();
    handedOut.add(intruder);

    List<String> readAgain = bean.getRequiredMetadata();
    assertTrue(readAgain.contains(ProductCrawlerBean.PRODUCT_TYPE), "the required set was emptied");
    assertTrue(readAgain.containsAll(extra), "a configured required field was lost");
  }

  /**
   * Global metadata configured on the bean is metadata the crawler will see.
   * It is copied onto every product before extraction, so a key that does not
   * survive the setter never reaches a product.
   */
  @HegelTest
  void globalMetadataKeepsEveryFieldItIsGiven(TestCase tc) {
    List<String> keys = tc.draw(lists(metadataKey()).maxSize(6), "keys");
    String value = tc.draw(text().maxSize(20).categories("Lu", "Ll", "Nd"), "value");

    Metadata configured = new Metadata();
    for (String key : keys) {
      configured.replaceMetadata(key, value);
    }

    Bean bean = new Bean();
    bean.setGlobalMetadata(configured);

    Metadata global = bean.getGlobalMetadata();
    assertNotNull(global);
    for (String key : keys) {
      assertTrue(global.containsKey(key), "global metadata lost the field '" + key + "'");
      assertEquals(value, global.getMetadata(key), "global metadata changed the value of '" + key + "'");
    }
  }

  /**
   * A freshly constructed bean is in the state the crawler treats as "not
   * configured": no daemon, full recursion, ingest enabled. These defaults are
   * what a bean file that omits them silently relies on.
   */
  @HegelTest
  void anUnconfiguredBeanHasTheDocumentedDefaults(TestCase tc) {
    tc.note("a bean file that sets nothing");

    Bean bean = new Bean();

    assertEquals(-1, bean.getDaemonPort(), "a fresh bean claims a daemon port");
    assertEquals(-1, bean.getDaemonWait(), "a fresh bean claims a daemon wait");
    assertFalse(bean.isNoRecur(), "a fresh bean refuses to recurse");
    assertFalse(bean.isCrawlForDirs(), "a fresh bean crawls for directories");
    assertFalse(bean.isSkipIngest(), "a fresh bean skips ingest");
    assertNotNull(bean.getActionIds(), "a fresh bean has no action list");
    assertTrue(bean.getActionIds().isEmpty(), "a fresh bean already has actions");
    assertNotNull(bean.getGlobalMetadata(), "a fresh bean has no global metadata");
  }

  /** The simple settings are stored and read back exactly as configured. */
  @HegelTest
  void simpleSettingsAreReadBackUnchanged(TestCase tc) {
    boolean noRecur = tc.draw(booleans(), "noRecur");
    boolean crawlForDirs = tc.draw(booleans(), "crawlForDirs");
    boolean skipIngest = tc.draw(booleans(), "skipIngest");
    int daemonPort = tc.draw(integers().min(-1).max(65535), "daemonPort");
    int daemonWait = tc.draw(integers().min(-1).max(3600), "daemonWait");
    String productPath = tc.draw(text().maxSize(30).categories("Lu", "Ll", "Nd"), "productPath");

    Bean bean = new Bean();
    bean.setNoRecur(noRecur);
    bean.setCrawlForDirs(crawlForDirs);
    bean.setSkipIngest(skipIngest);
    bean.setDaemonPort(daemonPort);
    bean.setDaemonWait(daemonWait);
    bean.setProductPath(productPath);

    assertEquals(noRecur, bean.isNoRecur());
    assertEquals(crawlForDirs, bean.isCrawlForDirs());
    assertEquals(skipIngest, bean.isSkipIngest());
    assertEquals(daemonPort, bean.getDaemonPort());
    assertEquals(daemonWait, bean.getDaemonWait());
    assertEquals(productPath, bean.getProductPath());
  }
}
