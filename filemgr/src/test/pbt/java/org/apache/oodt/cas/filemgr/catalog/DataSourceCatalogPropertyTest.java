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
import org.apache.oodt.cas.filemgr.HsqlTestDatabase;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductPage;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.filemgr.validation.ValidationLayer;
import org.apache.oodt.cas.filemgr.validation.XMLValidationLayer;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Stateful properties for {@link DataSourceCatalog}, the JDBC-backed catalog
 * that every file manager deployment on a relational store runs through.
 *
 * <p>The class has no unit-test coverage at all, and it builds every statement
 * it issues by concatenating caller data into SQL text. The properties below
 * state the contracts a caller genuinely relies on — a product that was added
 * can be fetched back, metadata survives a round trip, removing a product
 * leaves nothing behind, and walking the pages visits every product once — and
 * run each of them against a throwaway HSQLDB in its own temporary directory,
 * built from the same {@code testcat.sql} schema the existing suite uses.
 *
 * <p>The centrepiece is {@code catalogAgreesWithAnInMemoryModel}, which
 * generates a sequence of catalog commands, applies each to both the real
 * catalog and a {@link Map} standing in as a model, and compares the two after
 * every single step. Ordering and transaction faults show up there that no
 * single-call test would reach.
 *
 * <p>Sample counts are deliberately modest: every case creates, populates and
 * tears down a real database.
 */
class DataSourceCatalogPropertyTest {

  private static final String TYPE_ID = "urn:oodt:GenericFile";
  private static final String TYPE_NAME = "GenericFile";

  /** The element the properties hang generated metadata off; declared for GenericFile. */
  private static final String ELEMENT_NAME = "Filename";

  private static final String ELEMENT_ID = "urn:oodt:Filename";

  private static ValidationLayer validationLayer;

  /**
   * The XML validation layer is read-only for these properties and parsing it
   * per case would dominate the run, so it is built once and shared.
   */
  private static synchronized ValidationLayer validationLayer() {
    if (validationLayer == null) {
      URL dir = DataSourceCatalogPropertyTest.class.getResource("/xmlrpc-struct-factory");
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

  /** Names and values made only of letters and digits: nothing SQL could mistake for syntax. */
  private static Generator<String> plainText() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  /**
   * Text carrying characters that matter to SQL or to a {@code LIKE} pattern
   * but that are not the string delimiter: percent, underscore, backslash,
   * double quote, and non-ASCII letters.
   */
  private static Generator<String> awkwardText() {
    return text()
        .minSize(1)
        .maxSize(6)
        .categories("Ll")
        .includeCharacters("%_\\\"[]éü漢");
  }

  private static DataSourceCatalog catalog(HsqlTestDatabase db, int pageSize) {
    return new DataSourceCatalog(
        db.dataSource(), validationLayer(), true, pageSize, 0L, false, false);
  }

  private static HsqlTestDatabase freshCatalogDb() throws Exception {
    HsqlTestDatabase db = HsqlTestDatabase.create("cat");
    try {
      db.runScript("/testcat.sql");
    } catch (Exception e) {
      db.close();
      throw e;
    }
    return db;
  }

  /**
   * A product that was added is reachable by its assigned id, by its name, and
   * from {@code getProducts()}, with the fields it was given.
   *
   * <p>This is the catalog's most basic promise: the ingest path calls
   * {@code addProduct} and everything downstream — the transfer, the client,
   * the browser — finds the product again by id or name.
   */
  @HegelTest(testCases = 30)
  void addedProductIsRetrievableByIdAndByName(TestCase tc) throws Exception {
    String name = tc.draw(plainText(), "name");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      Product added = product(name);
      cat.addProduct(added);

      String id = added.getProductId();
      assertNotNull(id, "addProduct left the product without an id");
      assertTrue(!id.isEmpty(), "addProduct assigned an empty id");

      Product byId = cat.getProductById(id);
      assertNotNull(byId, "product " + id + " was not found by id");
      assertEquals(name, byId.getProductName());
      assertEquals(Product.STRUCTURE_FLAT, byId.getProductStructure());
      assertEquals(Product.STATUS_TRANSFER, byId.getTransferStatus());

      Product byName = cat.getProductByName(name);
      assertNotNull(byName, "product '" + name + "' was not found by name");
      assertEquals(id, byName.getProductId());

      List<Product> all = cat.getProducts();
      assertNotNull(all, "getProducts() returned null with one product in the catalog");
      assertEquals(1, all.size());
      assertEquals(id, all.get(0).getProductId());
    } finally {
      db.close();
    }
  }

  /**
   * A generated sequence of catalog commands must leave the catalog agreeing
   * with a plain in-memory model of what those commands should have done,
   * checked after every step rather than only at the end.
   *
   * <p>The model is a map from product id to name and transfer status. After
   * each command the property asks the catalog for every product it believes
   * exists, for the products it believes were removed, for the count, and for
   * the whole listing, and requires all four to line up. A command that half
   * commits, or that leaves a stale row behind, shows up on the very next step.
   */
  @HegelTest(testCases = 25)
  void catalogAgreesWithAnInMemoryModel(TestCase tc) throws Exception {
    List<String> commands =
        tc.draw(
            lists(sampledFrom(List.of("add", "rename", "remove", "status")))
                .minSize(1)
                .maxSize(14),
            "commands");
    List<String> names =
        tc.draw(lists(plainText()).minSize(14).maxSize(14), "names");
    List<Integer> targets =
        tc.draw(lists(integers().min(0).max(999)).minSize(14).maxSize(14), "targets");
    List<String> statuses =
        tc.draw(
            lists(
                    sampledFrom(
                        List.of(Product.STATUS_TRANSFER, Product.STATUS_RECEIVED)))
                .minSize(14)
                .maxSize(14),
            "statuses");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      ProductType type = genericFile();

      // id -> [name, transferStatus]
      Map<String, String[]> model = new LinkedHashMap<String, String[]>();
      Set<String> removedIds = new LinkedHashSet<String>();

      for (int step = 0; step < commands.size(); step++) {
        String command = commands.get(step);
        List<String> live = new ArrayList<String>(model.keySet());

        if ("add".equals(command) || live.isEmpty()) {
          // Unique per step, so getProductByName has one unambiguous answer.
          String name = names.get(step) + "_" + step;
          Product p = product(name);
          cat.addProduct(p);
          model.put(p.getProductId(), new String[] {name, Product.STATUS_TRANSFER});
        } else {
          String id = live.get(targets.get(step) % live.size());
          String[] state = model.get(id);
          Product p = product(state[0]);
          p.setProductId(id);
          p.setTransferStatus(state[1]);

          if ("rename".equals(command)) {
            String name = names.get(step) + "_r" + step;
            p.setProductName(name);
            cat.modifyProduct(p);
            model.put(id, new String[] {name, state[1]});
          } else if ("remove".equals(command)) {
            cat.removeProduct(p);
            model.remove(id);
            removedIds.add(id);
          } else {
            String status = statuses.get(step);
            p.setTransferStatus(status);
            cat.setProductTransferStatus(p);
            model.put(id, new String[] {state[0], status});
          }
        }

        tc.note("step " + step + " (" + command + ") -> model " + model.keySet());

        for (Map.Entry<String, String[]> entry : model.entrySet()) {
          Product got = cat.getProductById(entry.getKey());
          assertNotNull(got, "step " + step + ": product " + entry.getKey() + " went missing");
          assertEquals(
              entry.getValue()[0], got.getProductName(), "step " + step + ": wrong name");
          assertEquals(
              entry.getValue()[1],
              got.getTransferStatus(),
              "step " + step + ": wrong transfer status");

          Product byName = cat.getProductByName(entry.getValue()[0]);
          assertNotNull(
              byName, "step " + step + ": '" + entry.getValue()[0] + "' not found by name");
          assertEquals(
              entry.getKey(), byName.getProductId(), "step " + step + ": name resolved to another product");
        }

        for (String gone : removedIds) {
          assertNull(
              cat.getProductById(gone),
              "step " + step + ": removed product " + gone + " is still readable");
        }

        assertEquals(
            model.size(), cat.getNumProducts(type), "step " + step + ": getNumProducts disagrees");

        List<Product> listed = cat.getProducts();
        Set<String> listedIds = new LinkedHashSet<String>();
        if (listed != null) {
          for (Product p : listed) {
            listedIds.add(p.getProductId());
          }
        }
        assertEquals(model.keySet(), listedIds, "step " + step + ": getProducts disagrees");
      }
    } finally {
      db.close();
    }
  }

  /**
   * Metadata written for a product must come back unchanged.
   *
   * <p>The values here contain characters that are meaningful inside a SQL
   * {@code LIKE} pattern or to a shell — percent, underscore, backslash,
   * double quote — plus non-ASCII letters, but never an apostrophe. Nothing in
   * that set has any special meaning inside a SQL string literal, so a
   * mismatch is the catalog corrupting data it was handed.
   */
  @HegelTest(testCases = 30)
  void metadataRoundTripsForValuesWithoutApostrophes(TestCase tc) throws Exception {
    String value = tc.draw(awkwardText(), "value");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      Product p = product("prod");
      cat.addProduct(p);

      Metadata met = new Metadata();
      met.addMetadata(ELEMENT_NAME, value);
      cat.addMetadata(met, p);

      Metadata back = cat.getMetadata(p);
      assertNotNull(back, "getMetadata returned null");
      assertEquals(
          value,
          back.getMetadata(ELEMENT_NAME),
          "metadata value changed on the way through the catalog");
    } finally {
      db.close();
    }
  }

  /**
   * The same round trip for values containing an apostrophe.
   *
   * <p>Apostrophes turn up constantly in real metadata: a file called
   * {@code O'Brien.txt}, a producer name, a free-text title. The catalog
   * accepts such a value without complaint — {@code addMetadata} catches the
   * failure from the malformed statement, logs it, and returns normally — so a
   * caller has no way to learn the value was never stored.
   */
  @HegelTest(testCases = 25)
  void metadataRoundTripsForValuesContainingAnApostrophe(TestCase tc) throws Exception {
    String value =
        tc.draw(text().minSize(1).maxSize(6).categories("Ll").includeCharacters("'"), "value");
    tc.assume(value.contains("'"));

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      Product p = product("prod");
      cat.addProduct(p);

      Metadata met = new Metadata();
      met.addMetadata(ELEMENT_NAME, value);
      cat.addMetadata(met, p);

      Metadata back = cat.getMetadata(p);
      assertNotNull(back, "getMetadata returned null");
      assertEquals(
          value,
          back.getMetadata(ELEMENT_NAME),
          "metadata value with an apostrophe was silently dropped");
    } finally {
      db.close();
    }
  }

  /**
   * A metadata key given several values must return all of them.
   *
   * <p>Multi-valued keys are the normal case for a hierarchical product:
   * {@code Filename} carries one entry per file. Losing one is losing a file.
   * Order is not asserted, because the catalog issues no {@code ORDER BY} for
   * an unordered catalog and SQL therefore promises nothing about it.
   */
  @HegelTest(testCases = 25)
  void multiValuedMetadataRoundTrips(TestCase tc) throws Exception {
    List<String> values = tc.draw(lists(plainText()).minSize(1).maxSize(5), "values");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      Product p = product("prod");
      cat.addProduct(p);

      Metadata met = new Metadata();
      met.addMetadata(ELEMENT_NAME, values);
      cat.addMetadata(met, p);

      List<String> back = cat.getMetadata(p).getAllMetadata(ELEMENT_NAME);
      assertNotNull(back, "no values came back for a key that was written");

      List<String> expected = new ArrayList<String>(values);
      List<String> actual = new ArrayList<String>(back);
      Collections.sort(expected);
      Collections.sort(actual);
      assertEquals(expected, actual, "the set of values written is not the set returned");
    } finally {
      db.close();
    }
  }

  /**
   * Removing a product must remove its metadata rows and its reference rows
   * too, leaving nothing keyed to an id that no longer exists.
   *
   * <p>The tables are queried directly rather than through the catalog, since
   * the point is whether orphan rows survive, not whether the catalog's own
   * readers happen to hide them. An orphan row is a slow leak and, once the
   * identity column wraps round to that id again, a correctness fault.
   */
  @HegelTest(testCases = 25)
  void removingAProductLeavesNoOrphanRows(TestCase tc) throws Exception {
    List<String> values = tc.draw(lists(plainText()).minSize(1).maxSize(4), "values");
    String reference = tc.draw(plainText(), "reference");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      Product p = product("doomed");
      cat.addProduct(p);
      String id = p.getProductId();

      Metadata met = new Metadata();
      met.addMetadata(ELEMENT_NAME, values);
      cat.addMetadata(met, p);

      Reference ref = new Reference();
      ref.setOrigReference("file:/orig/" + reference);
      ref.setDataStoreReference("file:/archive/" + reference);
      ref.setFileSize(42);
      ref.setMimeType("text/plain");
      p.setProductReferences(List.of(ref));
      cat.addProductReferences(p);

      assertTrue(
          db.scalarInt("SELECT COUNT(*) FROM GenericFile_metadata WHERE product_id = " + id) > 0,
          "the fixture never wrote any metadata to remove");
      assertTrue(
          db.scalarInt("SELECT COUNT(*) FROM GenericFile_reference WHERE product_id = " + id) > 0,
          "the fixture never wrote any reference to remove");

      cat.removeProduct(p);

      assertNull(cat.getProductById(id), "the product itself survived removal");
      assertEquals(
          0,
          db.scalarInt("SELECT COUNT(*) FROM GenericFile_metadata WHERE product_id = " + id),
          "metadata rows were orphaned by removeProduct");
      assertEquals(
          0,
          db.scalarInt("SELECT COUNT(*) FROM GenericFile_reference WHERE product_id = " + id),
          "reference rows were orphaned by removeProduct");
    } finally {
      db.close();
    }
  }

  /**
   * Walking the pages from the first to the last must visit every product in
   * the catalog exactly once.
   *
   * <p>Paging is how every browser and client reads a large catalog. A product
   * that falls between two pages is invisible to the user, and one that appears
   * on two pages is counted twice. The property also checks that the number of
   * pages actually walked matches the {@code totalPages} the catalog reported,
   * and that page numbers stay inside that range.
   */
  @HegelTest(testCases = 25)
  void pagingVisitsEveryProductExactlyOnce(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(0).max(11), "count");
    int pageSize = tc.draw(integers().min(1).max(4), "pageSize");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, pageSize);
      ProductType type = genericFile();

      Set<String> expected = new LinkedHashSet<String>();
      for (int i = 0; i < count; i++) {
        Product p = product("p" + i);
        cat.addProduct(p);
        // Paging reads the metadata table, so a product with no metadata is
        // not on any page. Give each one a value.
        Metadata met = new Metadata();
        met.addMetadata(ELEMENT_NAME, "f" + i);
        cat.addMetadata(met, p);
        expected.add(p.getProductId());
      }

      ProductPage page = cat.getFirstPage(type);
      assertNotNull(page, "getFirstPage returned null");

      Set<String> seen = new LinkedHashSet<String>();
      int pagesWalked = 0;
      int totalPages = page.getTotalPages();

      for (int guard = 0; guard <= count + 2; guard++) {
        if (page.getPageProducts() == null || page.getPageProducts().isEmpty()) {
          break;
        }
        pagesWalked++;
        assertTrue(
            page.getPageNum() >= 1 && page.getPageNum() <= totalPages,
            "page number " + page.getPageNum() + " is outside 1.." + totalPages);
        assertTrue(
            page.getPageProducts().size() <= pageSize,
            "page " + page.getPageNum() + " holds more than " + pageSize + " products");
        for (Product p : page.getPageProducts()) {
          assertTrue(
              seen.add(p.getProductId()),
              "product " + p.getProductId() + " appeared on more than one page");
        }
        if (page.isLastPage()) {
          break;
        }
        ProductPage next = cat.getNextPage(type, page);
        assertNotNull(next, "getNextPage returned null after page " + page.getPageNum());
        assertEquals(
            page.getPageNum() + 1, next.getPageNum(), "getNextPage did not advance by one");
        page = next;
        if (guard == count + 2) {
          fail("paging did not terminate after " + guard + " pages");
        }
      }

      assertEquals(expected, seen, "the pages do not partition the catalog");
      if (count > 0) {
        assertEquals(totalPages, pagesWalked, "walked a different number of pages than reported");
      }
    } finally {
      db.close();
    }
  }

  /**
   * The last page reported by {@code getLastProductPage} must be the page a
   * caller reaches by walking forward, and must say so about itself.
   *
   * <p>A client that jumps to the end and then pages backwards depends on this
   * agreeing with the forward walk.
   */
  @HegelTest(testCases = 25)
  void lastPageAgreesWithTheForwardWalk(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(1).max(11), "count");
    int pageSize = tc.draw(integers().min(1).max(4), "pageSize");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, pageSize);
      ProductType type = genericFile();

      for (int i = 0; i < count; i++) {
        Product p = product("p" + i);
        cat.addProduct(p);
        Metadata met = new Metadata();
        met.addMetadata(ELEMENT_NAME, "f" + i);
        cat.addMetadata(met, p);
      }

      ProductPage walked = cat.getFirstPage(type);
      for (int guard = 0; guard <= count + 2 && !walked.isLastPage(); guard++) {
        walked = cat.getNextPage(type, walked);
      }

      ProductPage last = cat.getLastProductPage(type);
      assertNotNull(last, "getLastProductPage returned null");
      assertTrue(last.isLastPage(), "getLastProductPage returned a page that denies being last");
      assertEquals(
          walked.getPageNum(), last.getPageNum(), "the last page reached is not the last page reported");

      List<String> walkedIds = new ArrayList<String>();
      for (Product p : walked.getPageProducts()) {
        walkedIds.add(p.getProductId());
      }
      List<String> lastIds = new ArrayList<String>();
      for (Product p : last.getPageProducts()) {
        lastIds.add(p.getProductId());
      }
      assertEquals(walkedIds, lastIds, "the last page holds different products depending on how it was asked for");
    } finally {
      db.close();
    }
  }

  /**
   * {@code getNumProducts} must equal the number of products added for that
   * product type, and must not count products of another type.
   */
  @HegelTest(testCases = 25)
  void numProductsCountsExactlyTheProductsOfThatType(TestCase tc) throws Exception {
    int mine = tc.draw(integers().min(0).max(8), "mine");
    int others = tc.draw(integers().min(0).max(5), "others");

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      ProductType type = genericFile();

      for (int i = 0; i < mine; i++) {
        cat.addProduct(product("mine" + i));
      }
      ProductType otherType = new ProductType();
      otherType.setName(TYPE_NAME);
      otherType.setProductTypeId("urn:oodt:OtherFile");
      for (int i = 0; i < others; i++) {
        Product p = Product.getDefaultFlatProduct("other" + i, "urn:oodt:OtherFile");
        p.getProductType().setName(TYPE_NAME);
        cat.addProduct(p);
      }

      assertEquals(mine, cat.getNumProducts(type), "getNumProducts miscounted this type");
      assertEquals(others, cat.getNumProducts(otherType), "getNumProducts miscounted the other type");
    } finally {
      db.close();
    }
  }

  /**
   * A product whose metadata carries a value must be found by a query for that
   * exact value, and must not be found by a query for a value nothing carries.
   *
   * <p>Query is the catalog's entire reason for existing. The values are drawn
   * from letters and digits, so the criteria tree is unambiguous and nothing
   * here depends on the SQL parser.
   */
  @HegelTest(testCases = 25)
  void queryFindsExactlyTheProductsCarryingTheValue(TestCase tc) throws Exception {
    List<String> values = tc.draw(lists(plainText()).minSize(1).maxSize(5), "values");
    String absent = tc.draw(plainText(), "absent");
    tc.assume(!values.contains(absent));

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      ProductType type = genericFile();

      Map<String, String> idToValue = new LinkedHashMap<String, String>();
      for (int i = 0; i < values.size(); i++) {
        Product p = product("p" + i);
        cat.addProduct(p);
        Metadata met = new Metadata();
        met.addMetadata(ELEMENT_NAME, values.get(i));
        cat.addMetadata(met, p);
        idToValue.put(p.getProductId(), values.get(i));
      }

      String wanted = values.get(0);
      Set<String> expected = new LinkedHashSet<String>();
      for (Map.Entry<String, String> entry : idToValue.entrySet()) {
        if (entry.getValue().equals(wanted)) {
          expected.add(entry.getKey());
        }
      }

      Query query = new Query();
      query.addCriterion(new TermQueryCriteria(ELEMENT_NAME, wanted));
      assertEquals(
          expected,
          new LinkedHashSet<String>(cat.query(query, type)),
          "query for '" + wanted + "' returned the wrong products");

      Query missing = new Query();
      missing.addCriterion(new TermQueryCriteria(ELEMENT_NAME, absent));
      assertEquals(
          Set.of(),
          new LinkedHashSet<String>(cat.query(missing, type)),
          "query for a value nothing carries returned products");
    } finally {
      db.close();
    }
  }

  /**
   * References written for a product must come back as they were written.
   *
   * <p>The datastore reference is what the file manager hands a client asking
   * where a product's bytes actually live, so a corrupted one is an
   * unretrievable product.
   */
  @HegelTest(testCases = 25)
  void productReferencesRoundTrip(TestCase tc) throws Exception {
    List<String> names = tc.draw(lists(plainText()).minSize(1).maxSize(4), "names");
    long size = tc.draw(integers().min(0).max(100000), "size").longValue();

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      Product p = product("refs");
      cat.addProduct(p);

      List<Reference> refs = new ArrayList<Reference>();
      for (String name : names) {
        Reference ref = new Reference();
        ref.setOrigReference("file:/orig/" + name);
        ref.setDataStoreReference("file:/archive/" + name);
        ref.setFileSize(size);
        ref.setMimeType("text/plain");
        refs.add(ref);
      }
      p.setProductReferences(refs);
      cat.addProductReferences(p);

      List<Reference> back = cat.getProductReferences(p);
      assertNotNull(back, "getProductReferences returned null");
      assertEquals(refs.size(), back.size(), "a reference was lost");

      Set<String> expected = new LinkedHashSet<String>();
      for (Reference ref : refs) {
        expected.add(ref.getOrigReference() + " -> " + ref.getDataStoreReference() + " @" + ref.getFileSize());
      }
      Set<String> actual = new LinkedHashSet<String>();
      for (Reference ref : back) {
        actual.add(ref.getOrigReference() + " -> " + ref.getDataStoreReference() + " @" + ref.getFileSize());
      }
      assertEquals(expected, actual, "references changed on the way through the catalog");
    } finally {
      db.close();
    }
  }

  /**
   * A product name containing an apostrophe must be storable and retrievable.
   *
   * <p>{@code O'Brien.txt} is a legal filename on every filesystem the file
   * manager runs on, and {@code Product} places no restriction on the name. The
   * catalog offers the caller no escaping hook, so if the name cannot survive
   * the trip the fault is the catalog's.
   */
  @HegelTest(testCases = 25)
  void productNameContainingAnApostropheRoundTrips(TestCase tc) throws Exception {
    String name =
        tc.draw(text().minSize(1).maxSize(6).categories("Ll").includeCharacters("'"), "name");
    tc.assume(name.contains("'"));

    HsqlTestDatabase db = freshCatalogDb();
    try {
      DataSourceCatalog cat = catalog(db, 5);
      Product p = product(name);
      cat.addProduct(p);

      Product back = cat.getProductByName(name);
      assertNotNull(back, "product named '" + name + "' could not be read back");
      assertEquals(name, back.getProductName());
    } finally {
      db.close();
    }
  }
}
