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

package org.apache.oodt.cas.filemgr.catalog;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;
import org.apache.oodt.cas.filemgr.HsqlTestDatabase;
import org.apache.oodt.cas.filemgr.structs.BooleanQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductPage;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.QueryCriteria;
import org.apache.oodt.cas.filemgr.structs.RangeQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.filemgr.validation.ValidationLayer;
import org.apache.oodt.cas.filemgr.validation.XMLValidationLayer;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of the read and query half of {@link DataSourceCatalog}: the
 * paths a client exercises when it asks the file manager a question rather
 * than when it ingests.
 *
 * <p>The existing {@code DataSourceCatalogPropertyTest} states what survives a
 * round trip. This one is about the answers: what {@code query} returns for a
 * range or a boolean combination, what {@code pagedQuery} reports about itself
 * against what it actually yields, what {@code getTopNProducts} means, what
 * {@code getReducedMetadata} leaves out, and what any of them say when the
 * catalog is empty or the identifier is unknown. Those are the paths a browser
 * and a {@code query-tool} client live on, and they are also where the class
 * builds its most elaborate SQL.
 *
 * <p>Several properties are <em>differential</em>: the same commands are run
 * against catalogs built with different constructor arguments — page size,
 * {@code orderedValues} — and the two are required to answer the same question
 * the same way. The arguments change the generated SQL, so an answer that
 * moves with them is an answer that depends on the deployment's configuration
 * rather than on its data.
 *
 * <p>Every value generated here is drawn from letters and digits. The catalog
 * concatenates caller data straight into SQL and drops values containing an
 * apostrophe on the floor (already recorded), so an apostrophe in a generator
 * here would only re-find that and would hide whatever else the property was
 * meant to say.
 */
class DataSourceCatalogQueryPropertyTest {

  private static final String TYPE_ID = "urn:oodt:GenericFile";
  private static final String TYPE_NAME = "GenericFile";
  private static final String ELEMENT_NAME = "Filename";
  private static final String OTHER_ELEMENT_NAME = "FileLocation";
  private static final String THIRD_ELEMENT_NAME = "DataVersion";

  private static ValidationLayer validationLayer;

  private static synchronized ValidationLayer validationLayer() {
    if (validationLayer == null) {
      URL dir =
          DataSourceCatalogQueryPropertyTest.class.getResource("/xmlrpc-struct-factory");
      validationLayer =
          new XMLValidationLayer(
              List.of("file://" + new File(dir.getFile()).getAbsolutePath()));
    }
    return validationLayer;
  }

  private static ProductType genericFile() {
    ProductType type = new ProductType();
    type.setName(TYPE_NAME);
    type.setProductTypeId(TYPE_ID);
    return type;
  }

  private static Product product(String name) {
    Product product = Product.getDefaultFlatProduct(name, TYPE_ID);
    product.getProductType().setName(TYPE_NAME);
    return product;
  }

  /** Names and values made only of letters and digits. */
  private static Generator<String> plainText() {
    return text().minSize(1).maxSize(6).categories("Lu", "Ll", "Nd");
  }

  private static DataSourceCatalog catalog(HsqlTestDatabase db, int pageSize) {
    return new DataSourceCatalog(
        db.dataSource(), validationLayer(), true, pageSize, 0L, false, false);
  }

  private static DataSourceCatalog orderedCatalog(HsqlTestDatabase db, int pageSize) {
    return new DataSourceCatalog(
        db.dataSource(), validationLayer(), true, pageSize, 0L, false, true);
  }

  private static HsqlTestDatabase freshCatalogDb() throws Exception {
    HsqlTestDatabase db = HsqlTestDatabase.create("catq");
    try {
      db.runScript("/testcat.sql");
    } catch (Exception e) {
      db.close();
      throw e;
    }
    return db;
  }

  /**
   * The same schema with a surrogate key on the metadata table.
   *
   * <p>{@code orderedValues} makes the catalog append {@code ORDER BY pkey} to
   * its metadata reads, which is the only way a multi-valued key comes back in
   * the order it was written. The shipped {@code testcat.sql} has no such
   * column, so a deployment that turns the flag on has to have added one; this
   * is that deployment.
   */
  private static HsqlTestDatabase freshOrderedCatalogDb() throws Exception {
    HsqlTestDatabase db = freshCatalogDb();
    try {
      db.executeAll(
          "DROP TABLE GenericFile_metadata IF EXISTS",
          "CREATE TABLE GenericFile_metadata ("
              + "pkey INTEGER GENERATED BY DEFAULT AS IDENTITY "
              + "(START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
              + "product_id int NOT NULL, "
              + "element_id varchar(1000) NOT NULL, "
              + "metadata_value varchar(2500) NOT NULL)");
    } catch (Exception e) {
      db.close();
      throw e;
    }
    return db;
  }

  private static void addWithValue(DataSourceCatalog cat, Product p, String key,
      String value) throws Exception {
    cat.addProduct(p);
    Metadata met = new Metadata();
    met.addMetadata(key, value);
    cat.addMetadata(met, p);
  }

  private static Set<String> idsOf(List<Product> products) {
    Set<String> ids = new LinkedHashSet<String>();
    if (products != null) {
      for (Product p : products) {
        ids.add(p.getProductId());
      }
    }
    return ids;
  }

  /** Fixed-width decimal strings, so that string order is numeric order. */
  private static String key(int value) {
    return String.format("%03d", value);
  }

  /**
   * Walking the pages of a {@code pagedQuery} must visit exactly the products
   * {@code query} returns for the same criteria, each once.
   *
   * <p>A browser reads a result set a page at a time; a script reads it whole.
   * They are looking at the same catalog and must be told the same thing, or
   * a product is visible one way and invisible the other.
   */
  @HegelTest(testCases = 25)
  void pagedQueryYieldsExactlyWhatQueryReturns(TestCase tc) throws Exception {
    List<String> values = tc.draw(lists(plainText()).minSize(1).maxSize(8), "values");
    int pageSize = tc.draw(integers().min(1).max(4), "pageSize");
    String wanted = values.get(tc.draw(integers().min(0).max(7), "which") % values.size());

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, pageSize);
      ProductType type = genericFile();

      for (int i = 0; i < values.size(); i++) {
        addWithValue(cat, product("p" + i), ELEMENT_NAME, values.get(i));
      }

      Query query = new Query();
      query.addCriterion(new TermQueryCriteria(ELEMENT_NAME, wanted));

      Set<String> whole = new LinkedHashSet<String>(cat.query(query, type));

      Set<String> paged = new LinkedHashSet<String>();
      ProductPage page = cat.pagedQuery(query, type, 1);
      assertNotNull(page, "pagedQuery returned nothing at all");
      for (int guard = 0; guard <= values.size() + 2; guard++) {
        if (page.getPageProducts() == null || page.getPageProducts().isEmpty()) {
          break;
        }
        for (Product p : page.getPageProducts()) {
          assertTrue(paged.add(p.getProductId()),
              "product " + p.getProductId() + " appeared on two pages of the "
                  + "same query");
        }
        if (page.isLastPage()) {
          break;
        }
        page = cat.pagedQuery(query, type, page.getPageNum() + 1);
        if (page == null) {
          break;
        }
        if (guard == values.size() + 2) {
          fail("paging a query did not terminate");
        }
      }

      tc.note("query -> " + whole + ", paged -> " + paged);
      assertEquals(whole, paged,
          "reading the same query a page at a time found a different set of "
              + "products than reading it whole");
    } finally {
      db.close();
    }
  }

  /**
   * The number of pages a {@code pagedQuery} reports must be the number of
   * pages it can actually fill.
   *
   * <p>{@code totalPages} is what a browser draws its pager from and what a
   * client loops to. The catalog computes it from {@code getResultListSize}
   * but fills the pages from {@code paginateQuery}, and the two build
   * different SQL for the same criteria, so this is a statement that they
   * agree about which products match.
   *
   * <p>The values are built so that some of them extend the one being searched
   * for — {@code v} alongside {@code vSomething} — which is the ordinary case
   * for filenames and version strings sharing a stem.
   */
  @HegelTest(testCases = 25)
  void aQueryReportsAsManyPagesAsItCanFill(TestCase tc) throws Exception {
    String wanted = tc.draw(plainText(), "wanted");
    List<String> suffixes =
        tc.draw(lists(text().minSize(0).maxSize(3).categories("Ll"))
            .minSize(1).maxSize(6), "suffixes");
    int pageSize = tc.draw(integers().min(1).max(4), "pageSize");

    List<String> values = new ArrayList<String>();
    values.add(wanted);
    for (String suffix : suffixes) {
      values.add(wanted + suffix);
    }

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, pageSize);
      ProductType type = genericFile();

      for (int i = 0; i < values.size(); i++) {
        addWithValue(cat, product("p" + i), ELEMENT_NAME, values.get(i));
      }

      Query query = new Query();
      query.addCriterion(new TermQueryCriteria(ELEMENT_NAME, wanted));

      List<String> matching = cat.query(query, type);
      int expectedPages =
          (matching.size() + pageSize - 1) / pageSize;

      ProductPage first = cat.pagedQuery(query, type, 1);
      assertNotNull(first, "pagedQuery returned nothing at all");
      tc.note("values=" + values + " wanted=" + wanted + " matching="
          + matching.size() + " reported=" + first.getTotalPages());

      assertEquals(expectedPages, first.getTotalPages(),
          "the query reported " + first.getTotalPages() + " pages but only "
              + matching.size() + " products match it, which fills "
              + expectedPages);
    } finally {
      db.close();
    }
  }

  /**
   * A range query must find exactly the products whose value lies in the
   * range, both inclusive and exclusive.
   *
   * <p>Range queries are how every time-bounded search reaches the catalog —
   * "everything received between these two dates". The values here are
   * fixed-width decimal strings, so string comparison and numeric comparison
   * agree and the expected answer is not a matter of opinion.
   */
  @HegelTest(testCases = 25)
  void aRangeQueryFindsExactlyTheValuesInTheRange(TestCase tc) throws Exception {
    List<Integer> values =
        tc.draw(lists(integers().min(0).max(200)).minSize(1).maxSize(8), "values");
    int low = tc.draw(integers().min(0).max(200), "low");
    int high = tc.draw(integers().min(0).max(200), "high");
    boolean inclusive = tc.draw(booleans(), "inclusive");
    tc.assume(low <= high);

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      ProductType type = genericFile();

      Map<String, Integer> idToValue = new LinkedHashMap<String, Integer>();
      for (int i = 0; i < values.size(); i++) {
        Product p = product("p" + i);
        addWithValue(cat, p, ELEMENT_NAME, key(values.get(i)));
        idToValue.put(p.getProductId(), values.get(i));
      }

      Set<String> expected = new LinkedHashSet<String>();
      for (Map.Entry<String, Integer> entry : idToValue.entrySet()) {
        int v = entry.getValue();
        boolean inRange = inclusive ? (v >= low && v <= high) : (v > low && v < high);
        if (inRange) {
          expected.add(entry.getKey());
        }
      }

      Query query = new Query();
      query.addCriterion(
          new RangeQueryCriteria(ELEMENT_NAME, key(low), key(high), inclusive));

      tc.note("range " + key(low) + ".." + key(high) + " inclusive=" + inclusive);
      assertEquals(expected,
          new LinkedHashSet<String>(cat.query(query, type)),
          "the range query did not return the products whose value is in the "
              + "range");
    } finally {
      db.close();
    }
  }

  /**
   * A half-open range — a start with no end, or an end with no start — must
   * behave as the one-sided comparison it names.
   *
   * <p>"Everything since Tuesday" is a range with no end, and it is the most
   * common query a monitoring client makes.
   */
  @HegelTest(testCases = 25)
  void aOneSidedRangeQueryComparesOnTheSideItNames(TestCase tc)
      throws Exception {
    List<Integer> values =
        tc.draw(lists(integers().min(0).max(200)).minSize(1).maxSize(8), "values");
    int bound = tc.draw(integers().min(0).max(200), "bound");
    boolean fromTheStart = tc.draw(booleans(), "fromTheStart");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      ProductType type = genericFile();

      Map<String, Integer> idToValue = new LinkedHashMap<String, Integer>();
      for (int i = 0; i < values.size(); i++) {
        Product p = product("p" + i);
        addWithValue(cat, p, ELEMENT_NAME, key(values.get(i)));
        idToValue.put(p.getProductId(), values.get(i));
      }

      Set<String> expected = new LinkedHashSet<String>();
      for (Map.Entry<String, Integer> entry : idToValue.entrySet()) {
        int v = entry.getValue();
        if (fromTheStart ? v >= bound : v <= bound) {
          expected.add(entry.getKey());
        }
      }

      Query query = new Query();
      query.addCriterion(fromTheStart
          ? new RangeQueryCriteria(ELEMENT_NAME, key(bound), null, true)
          : new RangeQueryCriteria(ELEMENT_NAME, null, key(bound), true));

      assertEquals(expected,
          new LinkedHashSet<String>(cat.query(query, type)),
          "a one-sided range query did not compare on the side it named");
    } finally {
      db.close();
    }
  }

  /**
   * Boolean combinations of criteria must behave as the set operations they
   * name: AND as intersection, OR as union, NOT as complement.
   *
   * <p>The catalog implements them as {@code INTERSECT}, {@code UNION} and
   * {@code NOT IN} over sub-selects. A caller writing {@code a AND b} is
   * asking for the products that satisfy both, and there is no other reading.
   */
  @HegelTest(testCases = 25)
  void booleanCriteriaBehaveAsSetOperations(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(1).max(7), "count");
    List<String> first =
        tc.draw(lists(sampledFrom(List.of("aa", "bb"))).minSize(7).maxSize(7),
            "firstValues");
    List<String> second =
        tc.draw(lists(sampledFrom(List.of("xx", "yy"))).minSize(7).maxSize(7),
            "secondValues");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      ProductType type = genericFile();

      Set<String> all = new LinkedHashSet<String>();
      Set<String> isAa = new LinkedHashSet<String>();
      Set<String> isXx = new LinkedHashSet<String>();
      for (int i = 0; i < count; i++) {
        Product p = product("p" + i);
        cat.addProduct(p);
        Metadata met = new Metadata();
        met.addMetadata(ELEMENT_NAME, first.get(i));
        met.addMetadata(OTHER_ELEMENT_NAME, second.get(i));
        cat.addMetadata(met, p);
        all.add(p.getProductId());
        if ("aa".equals(first.get(i))) {
          isAa.add(p.getProductId());
        }
        if ("xx".equals(second.get(i))) {
          isXx.add(p.getProductId());
        }
      }

      QueryCriteria aa = new TermQueryCriteria(ELEMENT_NAME, "aa");
      QueryCriteria xx = new TermQueryCriteria(OTHER_ELEMENT_NAME, "xx");

      Set<String> expectedAnd = new LinkedHashSet<String>(isAa);
      expectedAnd.retainAll(isXx);
      assertEquals(expectedAnd, queryFor(cat, type,
              new BooleanQueryCriteria(termsOf(aa, xx), BooleanQueryCriteria.AND)),
          "AND did not return the intersection");

      Set<String> expectedOr = new LinkedHashSet<String>(isAa);
      expectedOr.addAll(isXx);
      assertEquals(expectedOr, queryFor(cat, type,
              new BooleanQueryCriteria(termsOf(aa, xx), BooleanQueryCriteria.OR)),
          "OR did not return the union");

      Set<String> expectedNot = new LinkedHashSet<String>(all);
      expectedNot.removeAll(isAa);
      assertEquals(expectedNot, queryFor(cat, type,
              new BooleanQueryCriteria(termsOf(aa), BooleanQueryCriteria.NOT)),
          "NOT did not return the complement");
    } finally {
      db.close();
    }
  }

  private static List<QueryCriteria> termsOf(QueryCriteria... criteria) {
    List<QueryCriteria> terms = new Vector<QueryCriteria>();
    Collections.addAll(terms, criteria);
    return terms;
  }

  private static Set<String> queryFor(DataSourceCatalog cat, ProductType type,
      QueryCriteria criteria) throws Exception {
    Query query = new Query();
    query.addCriterion(criteria);
    return new LinkedHashSet<String>(cat.query(query, type));
  }

  /**
   * A query with no criteria at all must return every product of the type that
   * carries any metadata.
   *
   * <p>This is the query {@code getFirstPage} issues, so it is what the
   * browser's opening screen is built from.
   */
  @HegelTest(testCases = 25)
  void anEmptyQueryReturnsEveryProductWithMetadata(TestCase tc)
      throws Exception {
    int withMetadata = tc.draw(integers().min(0).max(6), "withMetadata");
    int withoutMetadata = tc.draw(integers().min(0).max(4), "withoutMetadata");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      ProductType type = genericFile();

      Set<String> expected = new LinkedHashSet<String>();
      for (int i = 0; i < withMetadata; i++) {
        Product p = product("has" + i);
        addWithValue(cat, p, ELEMENT_NAME, "v" + i);
        expected.add(p.getProductId());
      }
      for (int i = 0; i < withoutMetadata; i++) {
        cat.addProduct(product("bare" + i));
      }

      assertEquals(expected,
          new LinkedHashSet<String>(cat.query(new Query(), type)),
          "a query with no criteria did not list exactly the products that "
              + "carry metadata");
    } finally {
      db.close();
    }
  }

  /**
   * {@code getTopNProducts} must return the {@code n} most recently added
   * products, newest first, and nothing more.
   *
   * <p>This is what a monitoring page shows as "latest ingests". Asking for
   * more than the catalog holds must give everything it holds rather than
   * failing.
   */
  @HegelTest(testCases = 25)
  void topNReturnsTheMostRecentlyAddedProductsNewestFirst(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(1).max(8), "count");
    int n = tc.draw(integers().min(1).max(10), "n");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);

      List<String> inOrderAdded = new ArrayList<String>();
      for (int i = 0; i < count; i++) {
        Product p = product("p" + i);
        cat.addProduct(p);
        inOrderAdded.add(p.getProductId());
      }

      List<Product> top = cat.getTopNProducts(n);
      assertNotNull(top, "getTopNProducts found nothing in a catalog with "
          + count + " products");
      assertEquals(Math.min(n, count), top.size(),
          "getTopNProducts returned a different number of products than the "
              + "smaller of what was asked for and what exists");

      List<String> expected = new ArrayList<String>();
      for (int i = inOrderAdded.size() - 1;
          i >= 0 && expected.size() < Math.min(n, count); i--) {
        expected.add(inOrderAdded.get(i));
      }
      List<String> actual = new ArrayList<String>();
      for (Product p : top) {
        actual.add(p.getProductId());
      }
      assertEquals(expected, actual,
          "getTopNProducts did not return the most recently added products "
              + "newest first");
    } finally {
      db.close();
    }
  }

  /**
   * {@code getTopNProducts} restricted to a product type must return only
   * products of that type.
   *
   * <p>A deployment holds many types in one {@code products} table; showing an
   * operator another instrument's newest files under their own is a
   * correctness fault, not a cosmetic one.
   */
  @HegelTest(testCases = 25)
  void topNRestrictedToATypeReturnsOnlyThatType(TestCase tc) throws Exception {
    int mine = tc.draw(integers().min(1).max(6), "mine");
    int others = tc.draw(integers().min(0).max(6), "others");
    int n = tc.draw(integers().min(1).max(8), "n");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      ProductType type = genericFile();

      Set<String> mineIds = new LinkedHashSet<String>();
      for (int i = 0; i < mine; i++) {
        Product p = product("mine" + i);
        cat.addProduct(p);
        mineIds.add(p.getProductId());
      }
      for (int i = 0; i < others; i++) {
        Product p = Product.getDefaultFlatProduct("other" + i, "urn:oodt:OtherFile");
        p.getProductType().setName(TYPE_NAME);
        cat.addProduct(p);
      }

      List<Product> top = cat.getTopNProducts(n, type);
      assertNotNull(top, "getTopNProducts found none of the " + mine
          + " products of the type it was asked about");
      assertEquals(Math.min(n, mine), top.size(),
          "getTopNProducts returned the wrong number of products for the type");
      for (Product p : top) {
        assertTrue(mineIds.contains(p.getProductId()),
            "getTopNProducts returned product " + p.getProductId()
                + ", which belongs to another product type");
      }
    } finally {
      db.close();
    }
  }

  /**
   * {@code getReducedMetadata} must return the full metadata restricted to the
   * keys asked for, and nothing else.
   *
   * <p>The reduced form exists so that a client fetching a page of results
   * does not drag every element of every product across the wire. It is a
   * projection: a key it drops that was asked for is data lost, and a key it
   * keeps that was not asked for is the saving undone.
   */
  @HegelTest(testCases = 25)
  void reducedMetadataIsTheFullMetadataRestrictedToTheKeysAsked(TestCase tc)
      throws Exception {
    String filename = tc.draw(plainText(), "filename");
    String location = tc.draw(plainText(), "location");
    String version = tc.draw(plainText(), "version");
    List<String> asked = tc.draw(
        lists(sampledFrom(List.of(ELEMENT_NAME, OTHER_ELEMENT_NAME, THIRD_ELEMENT_NAME)))
            .minSize(1).maxSize(3), "asked");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      Product p = product("reduced");
      cat.addProduct(p);

      Metadata met = new Metadata();
      met.addMetadata(ELEMENT_NAME, filename);
      met.addMetadata(OTHER_ELEMENT_NAME, location);
      met.addMetadata(THIRD_ELEMENT_NAME, version);
      cat.addMetadata(met, p);

      Set<String> wanted = new LinkedHashSet<String>(asked);
      Metadata reduced = cat.getReducedMetadata(p, new ArrayList<String>(wanted));
      assertNotNull(reduced, "getReducedMetadata returned nothing");

      Metadata full = cat.getMetadata(p);
      for (String elementName : wanted) {
        assertEquals(full.getAllMetadata(elementName),
            reduced.getAllMetadata(elementName),
            "the reduced metadata does not agree with the full metadata "
                + "about " + elementName);
      }
      Set<String> extra = new TreeSet<String>(reduced.getAllKeys());
      extra.removeAll(wanted);
      assertEquals(Set.of(), extra,
          "getReducedMetadata returned keys nobody asked for: " + extra);
    } finally {
      db.close();
    }
  }

  /**
   * Asking for a reduced metadata with no keys at all returns the whole of it.
   *
   * <p>A caller that computed an empty projection — because the client asked
   * for no columns, or because a configuration listed none — gets the
   * unreduced answer rather than an error or an empty one. Whichever of those
   * the class means, a caller has to be able to rely on it, and this pins
   * which it is.
   */
  @HegelTest(testCases = 20)
  void reducedMetadataWithNoKeysIsTheFullMetadata(TestCase tc) throws Exception {
    String filename = tc.draw(plainText(), "filename");
    String location = tc.draw(plainText(), "location");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      Product p = product("noKeys");
      cat.addProduct(p);
      Metadata met = new Metadata();
      met.addMetadata(ELEMENT_NAME, filename);
      met.addMetadata(OTHER_ELEMENT_NAME, location);
      cat.addMetadata(met, p);

      Metadata reduced = cat.getReducedMetadata(p, new ArrayList<String>());
      Metadata full = cat.getMetadata(p);
      assertEquals(new TreeSet<String>(full.getAllKeys()),
          new TreeSet<String>(reduced.getAllKeys()),
          "an empty projection returned a different set of keys than the "
              + "full metadata");
      assertEquals(full.getAllMetadata(ELEMENT_NAME),
          reduced.getAllMetadata(ELEMENT_NAME),
          "an empty projection returned different values than the full "
              + "metadata");
    } finally {
      db.close();
    }
  }

  /**
   * With {@code orderedValues} configured, a multi-valued key must come back
   * in the order it was written.
   *
   * <p>The order of a hierarchical product's {@code Filename} entries is the
   * order of its files, and a versioner walking them relies on it. The flag
   * exists for exactly this, and it is the only thing that makes the answer
   * defined at all: without it the catalog issues no {@code ORDER BY} and SQL
   * promises nothing.
   */
  @HegelTest(testCases = 25)
  void orderedValuesPreservesTheOrderMetadataWasWrittenIn(TestCase tc)
      throws Exception {
    List<String> values = tc.draw(lists(plainText()).minSize(2).maxSize(6), "values");

    HsqlTestDatabase db = freshOrderedCatalogDb();
    try {
      DataSourceCatalog cat = orderedCatalog(db, 5);
      Product p = product("ordered");
      cat.addProduct(p);

      Metadata met = new Metadata();
      met.addMetadata(ELEMENT_NAME, values);
      cat.addMetadata(met, p);

      assertEquals(values, cat.getMetadata(p).getAllMetadata(ELEMENT_NAME),
          "an ordered catalog did not return the values in the order they "
              + "were written");
      assertEquals(values,
          cat.getReducedMetadata(p, List.of(ELEMENT_NAME))
              .getAllMetadata(ELEMENT_NAME),
          "an ordered catalog's reduced metadata is in a different order than "
              + "its full metadata");
    } finally {
      db.close();
    }
  }

  /**
   * Ordering the values changes the order they come back in and nothing else:
   * an ordered catalog and a plain one must agree about which products match a
   * query and about the set of values a key carries.
   *
   * <p>{@code orderedValues} rewrites the SQL the catalog issues for every
   * metadata read. A deployment turning it on is asking for an ordering
   * guarantee, not for different data.
   */
  @HegelTest(testCases = 20)
  void orderingValuesDoesNotChangeWhichProductsMatch(TestCase tc)
      throws Exception {
    List<String> values = tc.draw(lists(plainText()).minSize(1).maxSize(6), "values");
    String wanted = values.get(0);

    HsqlTestDatabase plainDb = freshCatalogDb();
    HsqlTestDatabase orderedDb = freshOrderedCatalogDb();
    try {
      DataSourceCatalog plain = catalog(plainDb, 3);
      DataSourceCatalog ordered = orderedCatalog(orderedDb, 3);
      ProductType type = genericFile();

      List<String> plainIds = new ArrayList<String>();
      List<String> orderedIds = new ArrayList<String>();
      for (int i = 0; i < values.size(); i++) {
        Product a = product("p" + i);
        addWithValue(plain, a, ELEMENT_NAME, values.get(i));
        plainIds.add(a.getProductId());
        Product b = product("p" + i);
        addWithValue(ordered, b, ELEMENT_NAME, values.get(i));
        orderedIds.add(b.getProductId());
      }

      Query query = new Query();
      query.addCriterion(new TermQueryCriteria(ELEMENT_NAME, wanted));

      Set<Integer> plainMatched = positionsOf(plain.query(query, type), plainIds);
      Set<Integer> orderedMatched =
          positionsOf(ordered.query(query, type), orderedIds);

      assertEquals(plainMatched, orderedMatched,
          "an ordered catalog matched a different set of products than a "
              + "plain one holding the same data");

      assertEquals(plain.getNumProducts(type), ordered.getNumProducts(type),
          "an ordered catalog counted a different number of products");
    } finally {
      plainDb.close();
      orderedDb.close();
    }
  }

  /** Maps ids back to the position in which their product was added. */
  private static Set<Integer> positionsOf(List<String> matched, List<String> ids) {
    Set<Integer> positions = new TreeSet<Integer>();
    for (String id : matched) {
      positions.add(ids.indexOf(id));
    }
    return positions;
  }

  /**
   * Page size changes how a result set is cut up and nothing else: walking the
   * pages at any page size must visit exactly the same products.
   *
   * <p>{@code pageSize} is a deployment setting. An operator raising it to cut
   * round trips must not thereby change what the catalog says exists.
   */
  @HegelTest(testCases = 20)
  void pageSizeDoesNotChangeWhichProductsAreReachable(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(0).max(9), "count");
    int smallPage = tc.draw(integers().min(1).max(3), "smallPage");
    int largePage = tc.draw(integers().min(4).max(9), "largePage");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      ProductType type = genericFile();
      DataSourceCatalog small = catalog(db, smallPage);
      DataSourceCatalog large = catalog(db, largePage);

      Set<String> expected = new LinkedHashSet<String>();
      for (int i = 0; i < count; i++) {
        Product p = product("p" + i);
        addWithValue(small, p, ELEMENT_NAME, "v" + i);
        expected.add(p.getProductId());
      }

      assertEquals(expected, walkPages(small, type, count),
          "walking at page size " + smallPage + " missed a product");
      assertEquals(expected, walkPages(large, type, count),
          "walking at page size " + largePage + " missed a product");
    } finally {
      db.close();
    }
  }

  private static Set<String> walkPages(DataSourceCatalog cat, ProductType type,
      int count) {
    Set<String> seen = new LinkedHashSet<String>();
    ProductPage page = cat.getFirstPage(type);
    for (int guard = 0; page != null && guard <= count + 2; guard++) {
      if (page.getPageProducts() == null || page.getPageProducts().isEmpty()) {
        break;
      }
      for (Product p : page.getPageProducts()) {
        seen.add(p.getProductId());
      }
      if (page.isLastPage()) {
        break;
      }
      page = cat.getNextPage(type, page);
    }
    return seen;
  }

  /**
   * Paging backwards from the last page must visit the same products as paging
   * forwards from the first.
   *
   * <p>A browser's "previous" button is {@code getPrevPage}, and it is a
   * separate code path from {@code getNextPage}. Reaching a product only by
   * going forwards makes half the pager a lie.
   */
  @HegelTest(testCases = 25)
  void pagingBackwardsVisitsTheSameProductsAsPagingForwards(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(1).max(9), "count");
    int pageSize = tc.draw(integers().min(1).max(4), "pageSize");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, pageSize);
      ProductType type = genericFile();
      for (int i = 0; i < count; i++) {
        addWithValue(cat, product("p" + i), ELEMENT_NAME, "v" + i);
      }

      Set<String> forwards = walkPages(cat, type, count);

      Set<String> backwards = new LinkedHashSet<String>();
      ProductPage page = cat.getLastProductPage(type);
      for (int guard = 0; page != null && guard <= count + 2; guard++) {
        if (page.getPageProducts() == null || page.getPageProducts().isEmpty()) {
          break;
        }
        for (Product p : page.getPageProducts()) {
          backwards.add(p.getProductId());
        }
        if (page.isFirstPage()) {
          break;
        }
        ProductPage previous = cat.getPrevPage(type, page);
        assertNotNull(previous,
            "getPrevPage returned nothing before page " + page.getPageNum());
        assertEquals(page.getPageNum() - 1, previous.getPageNum(),
            "getPrevPage did not step back by one");
        page = previous;
      }

      assertEquals(forwards, backwards,
          "paging backwards reached a different set of products than paging "
              + "forwards");
    } finally {
      db.close();
    }
  }

  /**
   * An empty catalog must answer every read consistently: nothing is there,
   * and saying so must not throw.
   *
   * <p>Every deployment passes through this state on its first day, and a
   * browser drawing its opening page hits all of these calls at once.
   */
  @HegelTest(testCases = 20)
  void anEmptyCatalogAnswersEveryReadWithNothing(TestCase tc) throws Exception {
    int pageSize = tc.draw(integers().min(1).max(6), "pageSize");
    String name = tc.draw(plainText(), "name");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, pageSize);
      ProductType type = genericFile();

      assertNull(cat.getProducts(), "an empty catalog listed products");
      assertNull(cat.getProductsByProductType(type),
          "an empty catalog listed products for a type");
      assertNull(cat.getTopNProducts(pageSize),
          "an empty catalog produced a top-N listing");
      assertNull(cat.getProductByName(name),
          "an empty catalog found a product by name");
      assertEquals(0, cat.getNumProducts(type),
          "an empty catalog counted products");
      assertEquals(List.of(), cat.query(new Query(), type),
          "an empty catalog matched a query");

      ProductPage first = cat.getFirstPage(type);
      assertNotNull(first, "an empty catalog produced no first page at all");
      assertTrue(first.getPageProducts() == null
              || first.getPageProducts().isEmpty(),
          "an empty catalog produced a first page holding products");
    } finally {
      db.close();
    }
  }

  /**
   * A product identifier that names nothing must produce nothing, on every
   * route that takes one.
   *
   * <p>A client holding a stale identifier — from a bookmark, from a log, from
   * a product removed since — reaches all of these. Answering with somebody
   * else's product would be worse than answering with none.
   */
  @HegelTest(testCases = 25)
  void anUnknownIdentifierProducesNothingOnEveryRoute(TestCase tc)
      throws Exception {
    int present = tc.draw(integers().min(0).max(4), "present");
    int absentId = tc.draw(integers().min(1000).max(9999), "absentId");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      for (int i = 0; i < present; i++) {
        addWithValue(cat, product("p" + i), ELEMENT_NAME, "v" + i);
      }

      Product ghost = product("ghost");
      ghost.setProductId(String.valueOf(absentId));

      assertNull(cat.getProductById(ghost.getProductId()),
          "an identifier that names nothing produced a product");
      assertEquals(0, cat.getMetadata(ghost).getAllKeys().size(),
          "an identifier that names nothing produced metadata");
      assertEquals(0,
          cat.getReducedMetadata(ghost, List.of(ELEMENT_NAME)).getAllKeys().size(),
          "an identifier that names nothing produced reduced metadata");
      List<?> references = cat.getProductReferences(ghost);
      assertTrue(references == null || references.isEmpty(),
          "an identifier that names nothing produced references");
    } finally {
      db.close();
    }
  }

  /**
   * Removing a product that has already been removed must leave the catalog as
   * it was, and removing metadata that was never written must do likewise.
   *
   * <p>Ingest retries and cleanup scripts issue both of these routinely. A
   * second removal that took another product's rows with it would be silent
   * data loss.
   */
  @HegelTest(testCases = 25)
  void removingSomethingTwiceLeavesTheRestAlone(TestCase tc) throws Exception {
    int others = tc.draw(integers().min(1).max(5), "others");
    String value = tc.draw(plainText(), "value");
    String neverWritten = tc.draw(plainText(), "neverWritten");
    tc.assume(!value.equals(neverWritten));

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      ProductType type = genericFile();

      Set<String> survivors = new LinkedHashSet<String>();
      for (int i = 0; i < others; i++) {
        Product p = product("keep" + i);
        addWithValue(cat, p, ELEMENT_NAME, "keep" + i);
        survivors.add(p.getProductId());
      }

      Product doomed = product("doomed");
      addWithValue(cat, doomed, ELEMENT_NAME, value);

      Metadata absent = new Metadata();
      absent.addMetadata(ELEMENT_NAME, neverWritten);
      cat.removeMetadata(absent, doomed);
      assertEquals(List.of(value),
          cat.getMetadata(doomed).getAllMetadata(ELEMENT_NAME),
          "removing a value that was never written removed one that was");

      cat.removeProduct(doomed);
      cat.removeProduct(doomed);

      assertNull(cat.getProductById(doomed.getProductId()),
          "the product survived being removed twice");
      assertEquals(survivors, idsOf(cat.getProducts()),
          "removing a product twice took another product with it");
      assertEquals(others, cat.getNumProducts(type),
          "removing a product twice left the count wrong");
    } finally {
      db.close();
    }
  }

  /**
   * {@code getProductsByProductType} must list exactly the products of that
   * type, whether or not they carry any metadata.
   *
   * <p>Unlike the paged reads, this one goes at the {@code products} table
   * directly and is the only listing that includes a product nothing has yet
   * been catalogued about — which is the state a product is in between
   * {@code addProduct} and {@code addMetadata}.
   */
  @HegelTest(testCases = 25)
  void productsByTypeListsEveryProductOfThatTypeAndNoOther(TestCase tc)
      throws Exception {
    int mine = tc.draw(integers().min(1).max(6), "mine");
    int mineBare = tc.draw(integers().min(0).max(3), "mineBare");
    int others = tc.draw(integers().min(0).max(4), "others");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      ProductType type = genericFile();

      Set<String> expected = new LinkedHashSet<String>();
      for (int i = 0; i < mine; i++) {
        Product p = product("mine" + i);
        addWithValue(cat, p, ELEMENT_NAME, "v" + i);
        expected.add(p.getProductId());
      }
      for (int i = 0; i < mineBare; i++) {
        Product p = product("bare" + i);
        cat.addProduct(p);
        expected.add(p.getProductId());
      }
      ProductType otherType = new ProductType();
      otherType.setName(TYPE_NAME);
      otherType.setProductTypeId("urn:oodt:OtherFile");
      for (int i = 0; i < others; i++) {
        Product p = Product.getDefaultFlatProduct("other" + i, "urn:oodt:OtherFile");
        p.getProductType().setName(TYPE_NAME);
        cat.addProduct(p);
      }

      assertEquals(expected, idsOf(cat.getProductsByProductType(type)),
          "getProductsByProductType did not list exactly the products of the "
              + "type it was asked about");

      List<Product> otherProducts = cat.getProductsByProductType(otherType);
      assertEquals(others, otherProducts == null ? 0 : otherProducts.size(),
          "getProductsByProductType miscounted the other type");
    } finally {
      db.close();
    }
  }

  /**
   * Asking for a page number outside the range the catalog reported must not
   * produce a product that is not on that page.
   *
   * <p>A stale bookmark, a client that kept incrementing, or a catalog that
   * shrank between two calls all arrive here. The page may legitimately be
   * empty; what it may not do is hand back products belonging to some other
   * page, which is what a mis-computed cursor offset would look like.
   */
  @HegelTest(testCases = 25)
  void aPageNumberOutsideTheRangeYieldsNoProductsFromAnotherPage(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(1).max(8), "count");
    int pageSize = tc.draw(integers().min(1).max(4), "pageSize");
    int beyond = tc.draw(integers().min(1).max(5), "beyond");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, pageSize);
      ProductType type = genericFile();
      for (int i = 0; i < count; i++) {
        addWithValue(cat, product("p" + i), ELEMENT_NAME, "v" + i);
      }

      Query query = new Query();
      ProductPage first = cat.pagedQuery(query, type, 1);
      int totalPages = first.getTotalPages();

      ProductPage past = cat.pagedQuery(query, type, totalPages + beyond);
      assertNotNull(past, "asking past the last page returned nothing at all");
      List<Product> products = past.getPageProducts();
      tc.note("totalPages=" + totalPages + " asked for "
          + (totalPages + beyond) + " got "
          + (products == null ? 0 : products.size()));
      assertTrue(products == null || products.isEmpty(),
          "page " + (totalPages + beyond) + " of a " + totalPages
              + "-page result held " + (products == null ? 0 : products.size())
              + " products");
    } finally {
      db.close();
    }
  }
}
