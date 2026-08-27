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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.oodt.cas.filemgr.HsqlTestDatabase;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductPage;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.filemgr.validation.ValidationLayer;
import org.apache.oodt.cas.filemgr.validation.XMLValidationLayer;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties of the read half of {@link LenientDataSourceCatalog}: what it
 * reports for metadata already in the database, in both of the configurations
 * it is written for — with a validation layer, where the {@code element_id}
 * column holds an element identifier, and without one, where it holds the
 * metadata key itself.
 *
 * <p>These properties seed the metadata table by SQL rather than going through
 * {@code addMetadata}. That is deliberate. The lenient catalog's write path
 * files every value under {@code Map.Entry.toString()} — {@code key=value} —
 * instead of under the key, which is already recorded and which the existing
 * {@code LenientDataSourceCatalogPropertyTest} states by failing. Writing
 * through it here would only re-find that fault and would leave everything
 * downstream of it — the two branches of {@code populateProductMetadata}, the
 * two branches of {@code getReducedMetadata}, the counting the pager is built
 * on — permanently unexamined. Seeding the rows the write path was supposed
 * to produce is what lets those be stated at all.
 *
 * <p>The query path is left alone for the same reason: the lenient
 * {@code getSqlQuery} throws away the {@code SELECT} it builds and returns
 * only the trailing predicate, so no criteria-bearing {@code query} on this
 * class can produce runnable SQL. {@code getResultListSize}, which the pager
 * calls and which builds its own statement, is reached directly.
 */
class LenientDataSourceCatalogReadPropertyTest {

  private static final String TYPE_ID = "urn:oodt:GenericFile";
  private static final String TYPE_NAME = "GenericFile";

  /** Element names declared for GenericFile, with the ids they map to. */
  private static final Map<String, String> DECLARED = Map.of(
      "Filename", "urn:oodt:Filename",
      "FileLocation", "urn:oodt:FileLocation",
      "DataVersion", "urn:test:DataVersion");

  private static ValidationLayer validationLayer;

  private static synchronized ValidationLayer validationLayer() {
    if (validationLayer == null) {
      URL dir = LenientDataSourceCatalogReadPropertyTest.class
          .getResource("/xmlrpc-struct-factory");
      validationLayer = new XMLValidationLayer(
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

  private static Generator<String> plainText() {
    return text().minSize(1).maxSize(6).categories("Lu", "Ll", "Nd");
  }

  /**
   * A lenient catalog. {@code withLayer} chooses between the two
   * configurations the class exists to serve.
   */
  private static LenientDataSourceCatalog catalog(HsqlTestDatabase db,
      boolean withLayer, int pageSize, boolean orderedValues) {
    return new LenientDataSourceCatalog(db.dataSource(),
        withLayer ? validationLayer() : null, true, pageSize, 0L, false,
        orderedValues);
  }

  private static HsqlTestDatabase freshCatalogDb() throws Exception {
    HsqlTestDatabase db = HsqlTestDatabase.create("lenientread");
    try {
      db.runScript("/testcat.sql");
    } catch (Exception e) {
      db.close();
      throw e;
    }
    return db;
  }

  /** The same schema with the surrogate key {@code orderedValues} orders by. */
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

  /**
   * Writes the row the catalog's own write path was supposed to write.
   *
   * <p>With a validation layer the column holds the element identifier; with
   * none it holds the metadata key, which is what the class's own reader
   * expects in each case.
   */
  private static void seedMetadata(HsqlTestDatabase db, String productId,
      String columnValue, String value) throws Exception {
    db.execute("INSERT INTO GenericFile_metadata "
        + "(product_id, element_id, metadata_value) VALUES (" + productId
        + ", '" + columnValue + "', '" + value + "')");
  }

  /** The value the {@code element_id} column takes in a given configuration. */
  private static String columnFor(boolean withLayer, String key) {
    return withLayer ? DECLARED.get(key) : key;
  }

  /**
   * Metadata already in the database must be read back under the key the
   * configuration says the column holds.
   *
   * <p>Without a validation layer the lenient catalog is meant to accept and
   * return any key at all; with one it is meant to translate element
   * identifiers back into element names. Those are two different readings of
   * the same column and the class chooses between them on the fly, so both
   * are worth stating.
   */
  @HegelTest(testCases = 25)
  void metadataIsReadBackUnderTheKeyTheConfigurationImplies(TestCase tc)
      throws Exception {
    boolean withLayer = tc.draw(booleans(), "withValidationLayer");
    List<String> keys = tc.draw(
        lists(sampledFrom(new ArrayList<String>(new TreeSet<String>(DECLARED.keySet()))))
            .minSize(1).maxSize(3), "keys");
    String value = tc.draw(plainText(), "value");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      LenientDataSourceCatalog cat = catalog(db, withLayer, 5, false);
      Product p = product("read");
      cat.addProduct(p);

      Set<String> distinctKeys = new LinkedHashSet<String>(keys);
      for (String key : distinctKeys) {
        seedMetadata(db, p.getProductId(), columnFor(withLayer, key),
            value + key);
      }

      Metadata met = cat.getMetadata(p);
      assertNotNull(met, "getMetadata returned nothing");
      assertEquals(distinctKeys, new LinkedHashSet<String>(met.getAllKeys()),
          "the catalog reported a different set of keys than the database "
              + "holds");
      for (String key : distinctKeys) {
        assertEquals(value + key, met.getMetadata(key),
            "the value stored under " + key + " changed on the way back");
      }
    } finally {
      db.close();
    }
  }

  /**
   * A product nothing has been catalogued about must produce empty metadata
   * rather than null or an error.
   *
   * <p>Between {@code addProduct} and the first {@code addMetadata} every
   * product is in this state, and the ingest path reads it there.
   */
  @HegelTest(testCases = 20)
  void aProductWithNoMetadataProducesEmptyMetadata(TestCase tc)
      throws Exception {
    boolean withLayer = tc.draw(booleans(), "withValidationLayer");
    String name = tc.draw(plainText(), "name");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      LenientDataSourceCatalog cat = catalog(db, withLayer, 5, false);
      Product p = product(name);
      cat.addProduct(p);

      Metadata met = cat.getMetadata(p);
      assertNotNull(met, "getMetadata returned nothing for a bare product");
      assertEquals(0, met.getAllKeys().size(),
          "a product with no metadata rows reported metadata anyway");

      Metadata reduced = cat.getReducedMetadata(p, List.of("Filename"));
      assertNotNull(reduced, "getReducedMetadata returned nothing");
      assertEquals(0, reduced.getAllKeys().size(),
          "a product with no metadata rows reported reduced metadata anyway");
    } finally {
      db.close();
    }
  }

  /**
   * {@code getReducedMetadata} must be the full metadata restricted to the
   * keys asked for, in both configurations.
   *
   * <p>The lenient catalog builds a different {@code WHERE} clause for each —
   * one matching element identifiers, one matching keys — so the projection
   * can be right in one configuration and empty in the other.
   */
  @HegelTest(testCases = 25)
  void reducedMetadataIsTheFullMetadataRestrictedToTheKeysAsked(TestCase tc)
      throws Exception {
    boolean withLayer = tc.draw(booleans(), "withValidationLayer");
    List<String> asked = tc.draw(
        lists(sampledFrom(new ArrayList<String>(new TreeSet<String>(DECLARED.keySet()))))
            .minSize(1).maxSize(3), "asked");
    String value = tc.draw(plainText(), "value");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      LenientDataSourceCatalog cat = catalog(db, withLayer, 5, false);
      Product p = product("reduced");
      cat.addProduct(p);

      for (String key : new TreeSet<String>(DECLARED.keySet())) {
        seedMetadata(db, p.getProductId(), columnFor(withLayer, key),
            value + key);
      }

      Set<String> wanted = new LinkedHashSet<String>(asked);
      Metadata reduced =
          cat.getReducedMetadata(p, new ArrayList<String>(wanted));
      assertNotNull(reduced, "getReducedMetadata returned nothing");

      assertEquals(wanted, new LinkedHashSet<String>(reduced.getAllKeys()),
          "the projection did not return exactly the keys asked for");
      Metadata full = cat.getMetadata(p);
      for (String key : wanted) {
        assertEquals(full.getAllMetadata(key), reduced.getAllMetadata(key),
            "the projection disagrees with the full metadata about " + key);
      }
    } finally {
      db.close();
    }
  }

  /**
   * {@code removeMetadata} must delete exactly the values it was given and
   * leave every other value alone.
   *
   * <p>Deleting a value is how a re-ingest replaces one. Taking a neighbour
   * with it is silent data loss, and leaving the value behind means the
   * product ends up carrying both the old and the new.
   */
  @HegelTest(testCases = 25)
  void removeMetadataDeletesExactlyTheValuesItWasGiven(TestCase tc)
      throws Exception {
    boolean withLayer = tc.draw(booleans(), "withValidationLayer");
    List<String> values = tc.draw(lists(plainText()).minSize(2).maxSize(5),
        "values");
    String otherValue = tc.draw(plainText(), "otherValue");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      LenientDataSourceCatalog cat = catalog(db, withLayer, 5, false);
      Product p = product("removal");
      cat.addProduct(p);

      Set<String> distinct = new LinkedHashSet<String>(values);
      for (String value : distinct) {
        seedMetadata(db, p.getProductId(), columnFor(withLayer, "Filename"),
            value);
      }
      seedMetadata(db, p.getProductId(),
          columnFor(withLayer, "FileLocation"), otherValue);

      String doomed = new ArrayList<String>(distinct).get(0);
      Metadata toRemove = new Metadata();
      toRemove.addMetadata("Filename", doomed);
      cat.removeMetadata(toRemove, p);

      Set<String> expected = new LinkedHashSet<String>(distinct);
      expected.remove(doomed);

      Metadata left = cat.getMetadata(p);
      List<String> remaining = left.getAllMetadata("Filename");
      assertEquals(expected,
          remaining == null ? Set.of() : new LinkedHashSet<String>(remaining),
          "removing one value did not leave exactly the others");
      assertEquals(List.of(otherValue), left.getAllMetadata("FileLocation"),
          "removing a value under one key removed one under another");
    } finally {
      db.close();
    }
  }

  /**
   * The count the pager is built on must be the number of products whose value
   * for that element contains the term.
   *
   * <p>{@code getResultListSize} is what {@code pagedQuery} divides by the page
   * size to decide how many pages there are, and it builds its own SQL rather
   * than sharing the query path's. It matches on a substring — {@code LIKE
   * '%term%'} — which is a deliberate choice for a text search, so the
   * property states it as such.
   */
  @HegelTest(testCases = 25)
  void theResultCountIsTheNumberOfProductsWhoseValueContainsTheTerm(
      TestCase tc) throws Exception {
    boolean withLayer = tc.draw(booleans(), "withValidationLayer");
    List<String> values = tc.draw(
        lists(text().minSize(1).maxSize(3).categories("Ll")).minSize(1).maxSize(7),
        "values");
    String term = tc.draw(text().minSize(1).maxSize(2).categories("Ll"), "term");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      LenientDataSourceCatalog cat = catalog(db, withLayer, 5, false);
      ProductType type = genericFile();

      int expected = 0;
      for (int i = 0; i < values.size(); i++) {
        Product p = product("p" + i);
        cat.addProduct(p);
        seedMetadata(db, p.getProductId(), columnFor(withLayer, "Filename"),
            values.get(i));
        if (values.get(i).contains(term)) {
          expected++;
        }
      }

      Query query = new Query();
      query.addCriterion(new TermQueryCriteria("Filename", term));

      tc.note("values=" + values + " term=" + term + " expected=" + expected);
      assertEquals(expected, cat.getResultListSize(query, type),
          "the count the pager is built on is not the number of products "
              + "whose value contains the term");
    } finally {
      db.close();
    }
  }

  /**
   * With several criteria the count must be the number of products matching
   * all of them.
   *
   * <p>Two or more criteria take a different route through the class — the
   * first becomes a {@code WHERE} clause and the rest become joined
   * sub-selects — so a conjunction can be wrong where a single term is right.
   */
  @HegelTest(testCases = 25)
  void severalCriteriaCountTheProductsMatchingAllOfThem(TestCase tc)
      throws Exception {
    boolean withLayer = tc.draw(booleans(), "withValidationLayer");
    int count = tc.draw(integers().min(1).max(7), "count");
    List<String> firsts = tc.draw(
        lists(sampledFrom(List.of("aa", "bb"))).minSize(7).maxSize(7), "firsts");
    List<String> seconds = tc.draw(
        lists(sampledFrom(List.of("xx", "yy"))).minSize(7).maxSize(7), "seconds");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      LenientDataSourceCatalog cat = catalog(db, withLayer, 5, false);
      ProductType type = genericFile();

      int expected = 0;
      for (int i = 0; i < count; i++) {
        Product p = product("p" + i);
        cat.addProduct(p);
        seedMetadata(db, p.getProductId(), columnFor(withLayer, "Filename"),
            firsts.get(i));
        seedMetadata(db, p.getProductId(),
            columnFor(withLayer, "FileLocation"), seconds.get(i));
        if ("aa".equals(firsts.get(i)) && "xx".equals(seconds.get(i))) {
          expected++;
        }
      }

      Query query = new Query();
      query.addCriterion(new TermQueryCriteria("Filename", "aa"));
      query.addCriterion(new TermQueryCriteria("FileLocation", "xx"));

      assertEquals(expected, cat.getResultListSize(query, type),
          "a conjunction of criteria did not count the products matching "
              + "both");
    } finally {
      db.close();
    }
  }

  /**
   * With no criteria at all the count must be the number of products carrying
   * any metadata.
   *
   * <p>That is the count {@code getFirstPage} is drawn from, so it decides
   * how many pages a browser offers on its opening screen.
   */
  @HegelTest(testCases = 20)
  void anEmptyQueryCountsTheProductsCarryingMetadata(TestCase tc)
      throws Exception {
    boolean withLayer = tc.draw(booleans(), "withValidationLayer");
    int withMetadata = tc.draw(integers().min(0).max(6), "withMetadata");
    int bare = tc.draw(integers().min(0).max(4), "bare");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      LenientDataSourceCatalog cat = catalog(db, withLayer, 5, false);
      ProductType type = genericFile();

      for (int i = 0; i < withMetadata; i++) {
        Product p = product("has" + i);
        cat.addProduct(p);
        seedMetadata(db, p.getProductId(), columnFor(withLayer, "Filename"),
            "v" + i);
      }
      for (int i = 0; i < bare; i++) {
        cat.addProduct(product("bare" + i));
      }

      assertEquals(withMetadata, cat.getResultListSize(new Query(), type),
          "an empty query did not count exactly the products carrying "
              + "metadata");
    } finally {
      db.close();
    }
  }

  /**
   * Walking the pages of a lenient catalog must visit every catalogued product
   * exactly once, in either configuration.
   *
   * <p>The pager is inherited but the count it divides is the lenient class's
   * own, so the two halves can disagree and produce a page that is never
   * reached.
   */
  @HegelTest(testCases = 25)
  void pagingVisitsEveryCataloguedProductExactlyOnce(TestCase tc)
      throws Exception {
    boolean withLayer = tc.draw(booleans(), "withValidationLayer");
    int count = tc.draw(integers().min(0).max(9), "count");
    int pageSize = tc.draw(integers().min(1).max(4), "pageSize");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      LenientDataSourceCatalog cat = catalog(db, withLayer, pageSize, false);
      ProductType type = genericFile();

      Set<String> expected = new LinkedHashSet<String>();
      for (int i = 0; i < count; i++) {
        Product p = product("p" + i);
        cat.addProduct(p);
        seedMetadata(db, p.getProductId(), columnFor(withLayer, "Filename"),
            "v" + i);
        expected.add(p.getProductId());
      }

      Set<String> seen = new LinkedHashSet<String>();
      ProductPage page = cat.getFirstPage(type);
      assertNotNull(page, "getFirstPage returned nothing at all");
      for (int guard = 0; page != null && guard <= count + 2; guard++) {
        if (page.getPageProducts() == null || page.getPageProducts().isEmpty()) {
          break;
        }
        assertTrue(page.getPageProducts().size() <= pageSize,
            "page " + page.getPageNum() + " held more than " + pageSize
                + " products");
        for (Product p : page.getPageProducts()) {
          assertTrue(seen.add(p.getProductId()),
              "product " + p.getProductId() + " appeared on two pages");
        }
        if (page.isLastPage()) {
          break;
        }
        page = cat.getNextPage(type, page);
      }

      assertEquals(expected, seen,
          "the pages do not partition the catalogued products");
    } finally {
      db.close();
    }
  }

  /**
   * With {@code orderedValues} configured, the values of one key must come
   * back in the order the rows were written.
   *
   * <p>A hierarchical product's file list is only meaningful in order, and the
   * flag is the only thing that makes the order defined: without it the
   * catalog issues no {@code ORDER BY} at all.
   */
  @HegelTest(testCases = 25)
  void orderedValuesReturnsTheValuesInTheOrderTheRowsWereWritten(TestCase tc)
      throws Exception {
    boolean withLayer = tc.draw(booleans(), "withValidationLayer");
    List<String> values = tc.draw(lists(plainText()).minSize(2).maxSize(6),
        "values");

    HsqlTestDatabase db = freshOrderedCatalogDb();
    try {
      LenientDataSourceCatalog cat = catalog(db, withLayer, 5, true);
      Product p = product("ordered");
      cat.addProduct(p);
      for (String value : values) {
        seedMetadata(db, p.getProductId(), columnFor(withLayer, "Filename"),
            value);
      }

      assertEquals(values, cat.getMetadata(p).getAllMetadata("Filename"),
          "an ordered lenient catalog did not return the values in the order "
              + "the rows were written");
      assertEquals(values,
          cat.getReducedMetadata(p, List.of("Filename"))
              .getAllMetadata("Filename"),
          "an ordered lenient catalog's projection is in a different order "
              + "than its full metadata");
    } finally {
      db.close();
    }
  }

  /**
   * A lenient catalog and a strict one holding the same rows must report the
   * same metadata for a declared element, and the same page count.
   *
   * <p>Leniency is meant to widen what the catalog accepts, not to change what
   * it says about input the strict catalog already handles. Both classes are
   * pointed at the same database here, so any difference is a difference in
   * the code rather than in the data.
   */
  @HegelTest(testCases = 25)
  void aLenientCatalogAgreesWithAStrictOneAboutDeclaredElements(TestCase tc)
      throws Exception {
    List<String> values = tc.draw(lists(plainText()).minSize(1).maxSize(5),
        "values");
    int pageSize = tc.draw(integers().min(1).max(4), "pageSize");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      LenientDataSourceCatalog lenient = catalog(db, true, pageSize, false);
      DataSourceCatalog strict = new DataSourceCatalog(db.dataSource(),
          validationLayer(), true, pageSize, 0L, false, false);
      ProductType type = genericFile();

      Map<String, String> idToValue = new LinkedHashMap<String, String>();
      for (int i = 0; i < values.size(); i++) {
        Product p = product("p" + i);
        lenient.addProduct(p);
        seedMetadata(db, p.getProductId(), DECLARED.get("Filename"),
            values.get(i));
        idToValue.put(p.getProductId(), values.get(i));
      }

      for (Map.Entry<String, String> entry : idToValue.entrySet()) {
        Product p = product("p");
        p.setProductId(entry.getKey());
        assertEquals(strict.getMetadata(p).getAllMetadata("Filename"),
            lenient.getMetadata(p).getAllMetadata("Filename"),
            "the lenient and strict catalogs disagree about the metadata of "
                + "product " + entry.getKey());
        assertEquals(
            strict.getReducedMetadata(p, List.of("Filename"))
                .getAllMetadata("Filename"),
            lenient.getReducedMetadata(p, List.of("Filename"))
                .getAllMetadata("Filename"),
            "the lenient and strict catalogs disagree about the projected "
                + "metadata of product " + entry.getKey());
      }

      assertEquals(strict.getResultListSize(new Query(), type),
          lenient.getResultListSize(new Query(), type),
          "the lenient and strict catalogs disagree about how many products "
              + "an empty query matches");
      assertEquals(strict.getFirstPage(type).getTotalPages(),
          lenient.getFirstPage(type).getTotalPages(),
          "the lenient and strict catalogs offer a different number of pages "
              + "over the same rows");
    } finally {
      db.close();
    }
  }
}
