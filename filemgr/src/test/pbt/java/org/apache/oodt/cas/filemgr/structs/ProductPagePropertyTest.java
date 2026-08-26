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

package org.apache.oodt.cas.filemgr.structs;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Paging properties for {@link ProductPage}.
 *
 * <p>A client walks a result set by asking the catalog for the first page and
 * then for the next one until the page it holds says it is the last. The two
 * predicates {@code isFirstPage} and {@code isLastPage} are the whole of the
 * protocol, so what matters is that they stay consistent with the page numbers
 * they are derived from — including for {@link ProductPage#blankPage()}, which
 * is the page every catalog in the module returns for a query that matched
 * nothing and is therefore the page clients meet most often.
 */
class ProductPagePropertyTest {

  private static Generator<String> words() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  private static List<Product> products(TestCase tc, int count) {
    List<Product> products = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Product p = new Product();
      p.setProductId(tc.draw(words(), "product." + i + ".id"));
      p.setProductName(tc.draw(words(), "product." + i + ".name"));
      products.add(p);
    }
    return products;
  }

  /** Everything handed to the constructor must be readable back off the page. */
  @HegelTest
  void aPageReportsWhatItWasBuiltFrom(TestCase tc) {
    int totalPages = tc.draw(integers().min(1).max(50), "totalPages");
    int pageNum = tc.draw(integers().min(1).max(totalPages), "pageNum");
    int pageSize = tc.draw(integers().min(1).max(20), "pageSize");
    int count = tc.draw(integers().min(0).max(pageSize), "count");
    List<Product> onPage = products(tc, count);

    ProductPage page = new ProductPage(pageNum, totalPages, pageSize, onPage);

    assertEquals(pageNum, page.getPageNum());
    assertEquals(totalPages, page.getTotalPages());
    assertEquals(pageSize, page.getPageSize());
    assertEquals(count, page.getPageProducts().size());
  }

  /**
   * A page in the middle of a set is neither the first nor the last.
   *
   * <p>The two predicates have to disagree with each other everywhere except on
   * a set of exactly one page, or a client's walk either stops on the first
   * page it is given or never stops at all.
   */
  @HegelTest
  void firstAndLastAgreeWithThePageNumber(TestCase tc) {
    int totalPages = tc.draw(integers().min(1).max(50), "totalPages");
    int pageNum = tc.draw(integers().min(1).max(totalPages), "pageNum");

    ProductPage page = new ProductPage(pageNum, totalPages, 10, new ArrayList<>());

    assertEquals(pageNum == 1, page.isFirstPage(), "isFirstPage disagrees with the page number");
    assertEquals(
        pageNum == totalPages, page.isLastPage(), "isLastPage disagrees with the page number");
    if (totalPages > 1 && pageNum > 1 && pageNum < totalPages) {
      assertFalse(page.isFirstPage() || page.isLastPage(), "a middle page claimed to be an end");
    }
  }

  /** A result set that fits on one page gives a page that is both first and last. */
  @HegelTest
  void aSinglePageSetIsBothEnds(TestCase tc) {
    int pageSize = tc.draw(integers().min(1).max(20), "pageSize");
    int count = tc.draw(integers().min(0).max(pageSize), "count");

    ProductPage page = new ProductPage(1, 1, pageSize, products(tc, count));

    assertTrue(page.isFirstPage(), "the only page is not the first");
    assertTrue(page.isLastPage(), "the only page is not the last");
  }

  /**
   * A page that says it is not the first must have a page before it.
   *
   * <p>{@code !isFirstPage()} is what a client tests before offering to go
   * back, so a page that answers false there is asserting that page
   * {@code pageNum - 1} exists. The pages a client can actually be handed are
   * the ones a catalog builds and {@link ProductPage#blankPage()}, so both are
   * drawn here.
   */
  @HegelTest
  void aPageThatIsNotTheFirstHasAPredecessor(TestCase tc) {
    boolean blank = tc.draw(booleans(), "blank");
    ProductPage page;
    if (blank) {
      page = ProductPage.blankPage();
    } else {
      int totalPages = tc.draw(integers().min(1).max(50), "totalPages");
      int pageNum = tc.draw(integers().min(1).max(totalPages), "pageNum");
      page = new ProductPage(pageNum, totalPages, 10, new ArrayList<>());
    }
    tc.note("page " + page.getPageNum() + " of " + page.getTotalPages());

    if (!page.isFirstPage()) {
      assertTrue(
          page.getPageNum() >= 2,
          "page " + page.getPageNum() + " denies being the first but has nothing before it");
    }
  }

  /**
   * The blank page must be a usable empty result.
   *
   * <p>It is returned instead of null when a query matches nothing, so every
   * accessor a client would reach for on a real page has to answer on it too,
   * and a walk over it has to terminate immediately.
   */
  @HegelTest
  void theBlankPageIsAUsableEmptyResult(TestCase tc) {
    tc.note("blankPage carries no drawn input; it is a constant of the class");

    ProductPage blank = ProductPage.blankPage();

    assertNotNull(blank.getPageProducts(), "the blank page has no product list");
    assertEquals(0, blank.getPageProducts().size());
    assertEquals(0L, blank.getNumOfHits());
    assertTrue(blank.isLastPage(), "a walk over an empty result would not terminate");
  }
}
