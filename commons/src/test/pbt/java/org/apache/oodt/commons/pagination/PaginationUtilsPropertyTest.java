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

package org.apache.oodt.commons.pagination;

import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Properties of the paging arithmetic in {@link PaginationUtils}.
 *
 * <p>The class had no unit tests. Every property here is stated over the input
 * domain a caller can actually reach: a non-negative number of products and a
 * page size of at least one.
 */
class PaginationUtilsPropertyTest {

  /** Products 0..n-1, standing in for a page of catalog results. */
  private static List<Integer> listOfSize(int n) {
    List<Integer> xs = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      xs.add(i);
    }
    return xs;
  }

  /**
   * Walking every page in order reproduces the original list exactly: no
   * element dropped, none duplicated, none reordered. This is the property
   * that matters to a user clicking through a result set.
   */
  @HegelTest
  void pagesPartitionTheList(TestCase tc) {
    int size = tc.draw(integers().min(0).max(200), "size");
    int pageSize = tc.draw(integers().min(1).max(50), "pageSize");

    List<Integer> original = listOfSize(size);
    int totalPages = PaginationUtils.getTotalPage(original, pageSize);

    List<Integer> walked = new ArrayList<>();
    for (int page = 0; page < totalPages; page++) {
      walked.addAll(PaginationUtils.iterateFrom(page * pageSize, original, pageSize));
    }

    assertEquals(original, walked);
  }

  /**
   * The page count is the number of pages you actually need: enough to hold
   * every element, and not one more than that.
   */
  @HegelTest
  void totalPageCoversExactlyTheList(TestCase tc) {
    int size = tc.draw(integers().min(0).max(10_000), "size");
    int pageSize = tc.draw(integers().min(1).max(500), "pageSize");

    int pages = PaginationUtils.getTotalPage(size, pageSize);

    assertTrue((long) pages * pageSize >= size, "pages do not cover the list");
    if (pages > 0) {
      assertTrue((long) (pages - 1) * pageSize < size, "one page too many");
    }
  }

  /** The List overload and the int overload must not disagree. */
  @HegelTest
  void totalPageOverloadsAgree(TestCase tc) {
    int size = tc.draw(integers().min(0).max(500), "size");
    int pageSize = tc.draw(integers().min(1).max(50), "pageSize");

    assertEquals(
        PaginationUtils.getTotalPage(size, pageSize),
        PaginationUtils.getTotalPage(listOfSize(size), pageSize));
  }

  /**
   * The end index of a page is a usable index into the result set, so a caller
   * doing {@code list.get(endIdx)} cannot be handed something out of range.
   */
  @HegelTest
  void endIndexStaysInsideTheList(TestCase tc) {
    int total = tc.draw(integers().min(1).max(1_000), "total");
    int pageSize = tc.draw(integers().min(1).max(100), "pageSize");
    int totalPages = PaginationUtils.getTotalPage(total, pageSize);
    int page = tc.draw(integers().min(1).max(Math.max(1, totalPages)), "page");

    int endIdx = PaginationUtils.computeEndIdx(page, pageSize, total);

    assertTrue(endIdx >= 0, "end index " + endIdx + " is negative");
    assertTrue(endIdx <= total - 1, "end index " + endIdx + " past the last element");
  }

  /**
   * The page a start index falls on is a page that exists. Paging state is
   * built from this number, so a value outside [1, totalPages] means the UI is
   * describing a page the caller can never fetch.
   */
  @HegelTest
  void currentPageIsARealPage(TestCase tc) {
    int total = tc.draw(integers().min(1).max(1_000), "total");
    int pageSize = tc.draw(integers().min(1).max(100), "pageSize");
    int startIdx = tc.draw(integers().min(0).max(total - 1), "startIdx");

    int page = PaginationUtils.computeCurrentPage(startIdx, pageSize);
    int totalPages = PaginationUtils.getTotalPage(total, pageSize);

    assertTrue(page >= 1, "page " + page + " is below the first page");
    assertTrue(page <= totalPages, "page " + page + " is past the last page " + totalPages);
  }

  /**
   * Every start index a caller can legitimately hold — including one past the
   * last element, which is what an empty final page looks like — must be
   * safe to page from.
   */
  @HegelTest
  void iterateFromNeverThrowsOnReachableIndices(TestCase tc) {
    int size = tc.draw(integers().min(0).max(200), "size");
    int pageSize = tc.draw(integers().min(1).max(50), "pageSize");
    int startIndex = tc.draw(integers().min(0).max(size), "startIndex");

    List<Integer> page = PaginationUtils.iterateFrom(startIndex, listOfSize(size), pageSize);

    assertTrue(page.size() <= pageSize, "page larger than the page size");
    assertEquals(Math.min(pageSize, size - startIndex), page.size());
  }
}
