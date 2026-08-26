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

package org.apache.oodt.cas.crawl;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static dev.hegel.Generators.tuples;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import dev.hegel.Tuple2;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.oodt.cas.crawl.action.CrawlerAction;
import org.apache.oodt.cas.crawl.status.IngestStatus;
import org.apache.oodt.cas.crawl.structs.exceptions.CrawlerActionException;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of the metadata assembly and action dispatch in
 * {@link ProductCrawler}.
 *
 * <p>None of this touches the file manager or walks a directory. It covers the
 * three decisions the crawler makes about a product it has already found: which
 * metadata it fills in for you, whether the result is complete enough to ingest,
 * and what happens to the run when an action fails.
 */
class ProductCrawlerPropertyTest {

  /** A crawler with the disk- and network-facing steps stubbed out. */
  private static final class StubCrawler extends ProductCrawler {
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

  /** An action that reports a fixed answer, or throws instead. */
  private static final class StubAction extends CrawlerAction {
    private final boolean result;
    private final boolean throwing;
    private int calls;

    StubAction(String id, boolean result, boolean throwing) {
      setId(id);
      setPhases(Arrays.asList("preIngest"));
      this.result = result;
      this.throwing = throwing;
    }

    @Override
    public boolean performAction(File product, Metadata productMetadata)
        throws CrawlerActionException {
      calls++;
      if (throwing) {
        throw new CrawlerActionException("stub action " + getId() + " failed");
      }
      return result;
    }
  }

  private static Generator<String> word() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  /** A product path with a parent directory, as a crawl always produces. */
  private static File drawProduct(TestCase tc) {
    String dir = tc.draw(word(), "dir");
    String name = tc.draw(word(), "name");
    return new File(dir, name);
  }

  /**
   * The crawler fills in the four fields it knows how to work out for itself.
   * A met extractor is not obliged to produce any of them, and the file manager
   * cannot ingest without them.
   */
  @HegelTest
  void theCrawlerSuppliesTheFieldsItCanWorkOutItself(TestCase tc) {
    File product = drawProduct(tc);

    Metadata metadata = new Metadata();
    new StubCrawler().addKnownMetadata(product, metadata);

    assertTrue(metadata.containsKey(ProductCrawler.PRODUCT_NAME), "ProductName was not supplied");
    assertTrue(metadata.containsKey(ProductCrawler.FILENAME), "Filename was not supplied");
    assertTrue(metadata.containsKey(ProductCrawler.FILE_LOCATION), "FileLocation was not supplied");
    assertTrue(metadata.containsKey(ProductCrawler.FILE_SIZE), "FileSize was not supplied");
    assertEquals(product.getName(), metadata.getMetadata(ProductCrawler.FILENAME));
    assertEquals(product.getName(), metadata.getMetadata(ProductCrawler.PRODUCT_NAME));
  }

  /**
   * What the met extractor said wins. The crawler only fills gaps, so a
   * product type that renames its files, or a met file that already carries the
   * canonical location, must not be overwritten by the file's own name.
   */
  @HegelTest
  void extractedMetadataIsNeverOverwritten(TestCase tc) {
    File product = drawProduct(tc);
    List<String> present =
        tc.draw(
            lists(
                    sampledFrom(
                        Arrays.asList(
                            ProductCrawler.PRODUCT_NAME,
                            ProductCrawler.FILENAME,
                            ProductCrawler.FILE_LOCATION,
                            ProductCrawler.FILE_SIZE)))
                .maxSize(4),
            "present");
    String extracted = tc.draw(word(), "extracted");

    Metadata metadata = new Metadata();
    for (String key : present) {
      metadata.replaceMetadata(key, extracted);
    }

    new StubCrawler().addKnownMetadata(product, metadata);

    for (String key : present) {
      assertEquals(extracted, metadata.getMetadata(key), "the crawler overwrote '" + key + "'");
    }
  }

  /**
   * The completeness check is exactly "are all the required fields present".
   * It is the last gate before ingest, so it must not pass a product missing a
   * required field, nor block one that has them all.
   */
  @HegelTest
  void completenessIsExactlyThePresenceOfTheRequiredFields(TestCase tc) {
    List<String> required = tc.draw(lists(word()).minSize(1).maxSize(6), "required");
    List<String> supplied = tc.draw(lists(word()).maxSize(6), "supplied");
    String value = tc.draw(word(), "value");

    StubCrawler crawler = new StubCrawler();
    crawler.setRequiredMetadata(required);

    Metadata metadata = new Metadata();
    for (String key : supplied) {
      metadata.replaceMetadata(key, value);
    }

    boolean expected = true;
    for (String key : crawler.getRequiredMetadata()) {
      if (!metadata.containsKey(key)) {
        expected = false;
        break;
      }
    }

    assertEquals(expected, crawler.containsRequiredMetadata(metadata));
  }

  /**
   * A product carrying every field the crawler itself supplies, plus a product
   * type, is complete. This is the default configuration, and it is the path
   * every crawl takes.
   */
  @HegelTest
  void theDefaultRequirementsAreMetOnceTheProductTypeIsKnown(TestCase tc) {
    File product = drawProduct(tc);
    String productType = tc.draw(word(), "productType");

    StubCrawler crawler = new StubCrawler();
    Metadata metadata = new Metadata();
    metadata.replaceMetadata(ProductCrawler.PRODUCT_TYPE, productType);
    crawler.addKnownMetadata(product, metadata);

    assertTrue(
        crawler.containsRequiredMetadata(metadata),
        "a product with a type and the crawler's own fields was called incomplete");
  }

  /**
   * Every action runs and the run succeeds only if all of them did. The crawler
   * deliberately continues past a failing action, so a caller reading the
   * result needs it to be the conjunction and needs the later actions to have
   * happened.
   */
  @HegelTest
  void everyActionRunsAndTheResultIsTheConjunction(TestCase tc) {
    List<Tuple2<Boolean, Boolean>> outcomes =
        tc.draw(lists(tuples(booleans(), booleans())).maxSize(6), "outcomes");
    File product = drawProduct(tc);

    List<StubAction> actions = new ArrayList<>();
    boolean expected = true;
    for (int i = 0; i < outcomes.size(); i++) {
      boolean result = outcomes.get(i).value1();
      boolean throwing = outcomes.get(i).value2();
      actions.add(new StubAction("a" + i, result, throwing));
      expected &= result && !throwing;
    }

    boolean actual =
        new StubCrawler()
            .performProductCrawlerActions(
                new ArrayList<CrawlerAction>(actions), product, new Metadata());

    assertEquals(expected, actual, "the reported result is not the conjunction");
    for (StubAction action : actions) {
      assertEquals(1, action.calls, "action " + action.getId() + " did not run exactly once");
    }
  }

  /** With no actions configured at all, nothing has failed. */
  @HegelTest
  void noActionsMeansNothingFailed(TestCase tc) {
    File product = drawProduct(tc);

    assertTrue(
        new StubCrawler()
            .performProductCrawlerActions(
                new ArrayList<CrawlerAction>(), product, new Metadata()));
  }

  /**
   * A status reports the product, verdict and message it was made with. The
   * crawl's whole result is a list of these, and the daemon reports them
   * onwards.
   */
  @HegelTest
  void anIngestStatusReportsWhatItWasMadeWith(TestCase tc) {
    File product = drawProduct(tc);
    IngestStatus.Result result =
        tc.draw(sampledFrom(Arrays.asList(IngestStatus.Result.values())), "result");
    String message = tc.draw(text().maxSize(40), "message");

    IngestStatus status = new StubCrawler().createIngestStatus(product, result, message);

    assertSame(product, status.getProduct());
    assertEquals(result, status.getResult());
    assertEquals(message, status.getMessage());
  }

  /** The crawl result a caller is handed cannot be edited behind the crawler's back. */
  @HegelTest
  void theCrawlResultIsNotEditableByItsReader(TestCase tc) {
    File product = drawProduct(tc);

    StubCrawler crawler = new StubCrawler();
    List<IngestStatus> reported = crawler.getIngestStatus();

    assertTrue(reported.isEmpty(), "a crawler that has not run reports statuses");
    boolean rejected = false;
    try {
      reported.add(crawler.createIngestStatus(product, IngestStatus.Result.SUCCESS, "forged"));
    } catch (UnsupportedOperationException e) {
      rejected = true;
    }
    assertTrue(rejected, "a caller was able to add a status to the crawl result");
    assertFalse(crawler.getIngestStatus().iterator().hasNext(), "a forged status was recorded");
  }
}
