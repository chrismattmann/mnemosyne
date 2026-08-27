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
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * Properties for {@link LenientDataSourceCatalog}, the subclass a deployment
 * gets when {@code ...catalog.datasource.lenientFields} is set, which is meant
 * to accept metadata fields that no validation layer declares.
 *
 * <p>Two kinds of property are stated here. The first are round trips over the
 * lenient catalog on its own: metadata written under a key must come back
 * under that same key, with and without a validation layer. The second are
 * <em>differential</em> properties: the same commands are applied to a lenient
 * catalog and to a plain {@link DataSourceCatalog}, each on its own database,
 * and the two are required to answer identically for the operations they both
 * inherit or both claim to support. Leniency is supposed to widen what the
 * catalog accepts, not change what it returns for input the strict catalog
 * already handles.
 */
class LenientDataSourceCatalogPropertyTest {

  private static final String TYPE_ID = "urn:oodt:GenericFile";
  private static final String TYPE_NAME = "GenericFile";
  private static final String ELEMENT_NAME = "Filename";

  private static ValidationLayer validationLayer;

  private static synchronized ValidationLayer validationLayer() {
    if (validationLayer == null) {
      URL dir =
          LenientDataSourceCatalogPropertyTest.class.getResource("/xmlrpc-struct-factory");
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

  private static Generator<String> plainText() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  private static HsqlTestDatabase freshCatalogDb(String label) throws Exception {
    HsqlTestDatabase db = HsqlTestDatabase.create(label);
    try {
      db.runScript("/testcat.sql");
    } catch (Exception e) {
      db.close();
      throw e;
    }
    return db;
  }

  private static List<String> idsOf(List<Product> products) {
    List<String> ids = new ArrayList<String>();
    if (products != null) {
      for (Product p : products) {
        ids.add(p.getProductId());
      }
    }
    return ids;
  }

  /**
   * Without a validation layer — the configuration leniency exists for —
   * metadata written under a key must come back under that same key.
   *
   * <p>This is the whole point of the lenient catalog: a field nobody declared
   * is accepted anyway. If the key it comes back under is not the key it went
   * in as, every caller that reads metadata by name gets nothing.
   */
  @HegelTest(testCases = 25)
  void metadataRoundTripsUnderItsOwnKeyWithNoValidationLayer(TestCase tc) throws Exception {
    String key = tc.draw(plainText(), "key");
    String value = tc.draw(plainText(), "value");

    HsqlTestDatabase db = freshCatalogDb("lenient");
    try {
      LenientDataSourceCatalog cat =
          new LenientDataSourceCatalog(db.dataSource(), null, true, 5, 0L, false, false);
      Product p = product("prod");
      cat.addProduct(p);

      Metadata met = new Metadata();
      met.addMetadata(key, value);
      cat.addMetadata(met, p);

      Metadata back = cat.getMetadata(p);
      assertNotNull(back, "getMetadata returned null");
      assertEquals(
          value,
          back.getMetadata(key),
          "metadata written under '" + key + "' did not come back under that key; "
              + "keys present: " + back.getAllKeys());
    } finally {
      db.close();
    }
  }

  /**
   * With a validation layer in place, a declared element must round trip.
   *
   * <p>A lenient catalog is still expected to handle the declared fields at
   * least as well as the strict one does; the leniency is only about the
   * undeclared extras.
   */
  @HegelTest(testCases = 25)
  void declaredMetadataRoundTripsWithAValidationLayer(TestCase tc) throws Exception {
    String value = tc.draw(plainText(), "value");

    HsqlTestDatabase db = freshCatalogDb("lenient-vl");
    try {
      LenientDataSourceCatalog cat =
          new LenientDataSourceCatalog(
              db.dataSource(), validationLayer(), true, 5, 0L, false, false);
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
          "a declared element did not survive the lenient catalog; keys present: "
              + back.getAllKeys());
    } finally {
      db.close();
    }
  }

  /**
   * Metadata that was removed must leave no row behind in the metadata table.
   *
   * <p>Checked against the table rather than through {@code getMetadata}, so
   * that a row the reader happens not to surface still counts as not removed:
   * it is still storage consumed, and still a value a direct SQL reader or a
   * later reindex would find.
   */
  @HegelTest(testCases = 25)
  void removeMetadataDeletesTheRowItWrote(TestCase tc) throws Exception {
    String key = tc.draw(plainText(), "key");
    String value = tc.draw(plainText(), "value");

    HsqlTestDatabase db = freshCatalogDb("lenient-rm");
    try {
      LenientDataSourceCatalog cat =
          new LenientDataSourceCatalog(db.dataSource(), null, true, 5, 0L, false, false);
      Product p = product("prod");
      cat.addProduct(p);

      Metadata met = new Metadata();
      met.addMetadata(key, value);
      cat.addMetadata(met, p);

      Metadata toRemove = new Metadata();
      toRemove.addMetadata(key, value);
      cat.removeMetadata(toRemove, p);

      assertEquals(
          0,
          db.scalarInt(
              "SELECT COUNT(*) FROM GenericFile_metadata WHERE product_id = "
                  + p.getProductId()
                  + " AND metadata_value = '"
                  + value
                  + "'"),
          "removeMetadata left the row it had written");
    } finally {
      db.close();
    }
  }

  /**
   * For the product lifecycle both catalogs inherit unchanged, the lenient
   * catalog must answer exactly as the strict one does.
   *
   * <p>The two run on separate databases fed the same commands, so their
   * generated ids line up. Any divergence here means leniency changed
   * behaviour it was never meant to touch.
   */
  @HegelTest(testCases = 20)
  void lenientAndStrictCatalogsAgreeOnTheProductLifecycle(TestCase tc) throws Exception {
    List<String> names = tc.draw(lists(plainText()).minSize(1).maxSize(8), "names");

    HsqlTestDatabase strictDb = freshCatalogDb("diff-strict");
    HsqlTestDatabase lenientDb = freshCatalogDb("diff-lenient");
    try {
      DataSourceCatalog strict =
          new DataSourceCatalog(
              strictDb.dataSource(), validationLayer(), true, 5, 0L, false, false);
      LenientDataSourceCatalog lenient =
          new LenientDataSourceCatalog(
              lenientDb.dataSource(), validationLayer(), true, 5, 0L, false, false);
      ProductType type = genericFile();

      for (int i = 0; i < names.size(); i++) {
        String name = names.get(i) + "_" + i;
        Product a = product(name);
        Product b = product(name);
        strict.addProduct(a);
        lenient.addProduct(b);
        assertEquals(a.getProductId(), b.getProductId(), "the two catalogs assigned different ids");

        assertEquals(
            strict.getProductById(a.getProductId()).getProductName(),
            lenient.getProductById(b.getProductId()).getProductName(),
            "getProductById disagrees at step " + i);
        assertEquals(
            strict.getNumProducts(type),
            lenient.getNumProducts(type),
            "getNumProducts disagrees at step " + i);
        assertEquals(
            idsOf(strict.getProducts()),
            idsOf(lenient.getProducts()),
            "getProducts disagrees at step " + i);
      }

      // Remove the first product from both and compare again.
      Product first = strict.getProducts().get(strict.getProducts().size() - 1);
      first.getProductType().setName(TYPE_NAME);
      Product mirror = lenient.getProductById(first.getProductId());
      mirror.getProductType().setName(TYPE_NAME);
      strict.removeProduct(first);
      lenient.removeProduct(mirror);

      assertEquals(
          idsOf(strict.getProducts()),
          idsOf(lenient.getProducts()),
          "getProducts disagrees after a removal");
      assertEquals(
          strict.getNumProducts(type),
          lenient.getNumProducts(type),
          "getNumProducts disagrees after a removal");
    } finally {
      lenientDb.close();
      strictDb.close();
    }
  }

  /**
   * An unfiltered page of products must be the same page from either catalog.
   *
   * <p>{@code LenientDataSourceCatalog} overrides {@code getResultListSize},
   * which is what decides how many pages there are, so this is not simply
   * inherited behaviour.
   */
  @HegelTest(testCases = 20)
  void lenientAndStrictCatalogsAgreeOnPaging(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(1).max(9), "count");
    int pageSize = tc.draw(integers().min(1).max(4), "pageSize");

    HsqlTestDatabase strictDb = freshCatalogDb("page-strict");
    HsqlTestDatabase lenientDb = freshCatalogDb("page-lenient");
    try {
      DataSourceCatalog strict =
          new DataSourceCatalog(
              strictDb.dataSource(), validationLayer(), true, pageSize, 0L, false, false);
      LenientDataSourceCatalog lenient =
          new LenientDataSourceCatalog(
              lenientDb.dataSource(), validationLayer(), true, pageSize, 0L, false, false);
      ProductType type = genericFile();

      for (int i = 0; i < count; i++) {
        Product a = product("p" + i);
        Product b = product("p" + i);
        strict.addProduct(a);
        lenient.addProduct(b);
        Metadata met = new Metadata();
        met.addMetadata(ELEMENT_NAME, "f" + i);
        strict.addMetadata(met, a);
        lenient.addMetadata(met, b);
      }

      ProductPage strictPage = strict.getFirstPage(type);
      ProductPage lenientPage = lenient.getFirstPage(type);
      assertNotNull(strictPage, "strict getFirstPage returned null");
      assertNotNull(lenientPage, "lenient getFirstPage returned null");
      assertEquals(
          strictPage.getTotalPages(), lenientPage.getTotalPages(), "total page counts disagree");
      assertEquals(
          idsOf(strictPage.getPageProducts()),
          idsOf(lenientPage.getPageProducts()),
          "the first page holds different products");
    } finally {
      lenientDb.close();
      strictDb.close();
    }
  }

  /**
   * A single-term query must return the same products from either catalog.
   *
   * <p>{@code LenientDataSourceCatalog} overrides {@code getSqlQuery}, the
   * method that turns a criteria tree into the statement the catalog actually
   * runs, so a query is the place where the two implementations can genuinely
   * part company. The strict catalog's answer is used as the reference because
   * the existing suite pins it.
   */
  @HegelTest(testCases = 20)
  void lenientAndStrictCatalogsAgreeOnASingleTermQuery(TestCase tc) throws Exception {
    List<String> values = tc.draw(lists(plainText()).minSize(1).maxSize(5), "values");

    HsqlTestDatabase strictDb = freshCatalogDb("query-strict");
    HsqlTestDatabase lenientDb = freshCatalogDb("query-lenient");
    try {
      DataSourceCatalog strict =
          new DataSourceCatalog(
              strictDb.dataSource(), validationLayer(), true, 5, 0L, false, false);
      LenientDataSourceCatalog lenient =
          new LenientDataSourceCatalog(
              lenientDb.dataSource(), validationLayer(), true, 5, 0L, false, false);
      ProductType type = genericFile();

      for (int i = 0; i < values.size(); i++) {
        Product a = product("p" + i);
        Product b = product("p" + i);
        strict.addProduct(a);
        lenient.addProduct(b);
        Metadata met = new Metadata();
        met.addMetadata(ELEMENT_NAME, values.get(i));
        strict.addMetadata(met, a);
        lenient.addMetadata(met, b);
      }

      Query query = new Query();
      query.addCriterion(new TermQueryCriteria(ELEMENT_NAME, values.get(0)));

      List<String> strictIds = new ArrayList<String>(strict.query(query, type));
      List<String> lenientIds = new ArrayList<String>(lenient.query(query, type));
      Collections.sort(strictIds);
      Collections.sort(lenientIds);

      Set<String> expected = new LinkedHashSet<String>(strictIds);
      assertEquals(
          expected,
          new LinkedHashSet<String>(lenientIds),
          "the lenient catalog answered a term query differently from the strict one");
    } finally {
      lenientDb.close();
      strictDb.close();
    }
  }
}
