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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.tika.mime.MimeType;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductPage;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties for {@link LuceneCatalog}, the on-disk catalog that every File
 * Manager deployment using the default configuration writes its product
 * records into.
 *
 * <p>{@code TestLuceneCatalog} exercises the class with a single fixed product
 * and a fixed page size of twenty. These properties vary the product names,
 * the metadata keys and values, the number of products and the page size, and
 * check the contracts a caller of the {@link Catalog} interface relies on:
 * what goes in comes back out, a removal removes exactly one product, and the
 * pages of a paged result together account for every product exactly once.
 *
 * <p>Every property builds its index inside a fresh temporary directory and
 * deletes it in a {@code finally} block. The index has to be built by the test
 * because the module is on Lucene 10 and an index written by an older major
 * version cannot be opened.
 *
 * <p>The catalog is constructed with a null validation layer, which is the
 * {@code lenientFields=true} configuration: every metadata key presented is
 * stored, rather than only those a policy file declares. That is the
 * configuration under which the widest range of metadata reaches the index, so
 * it is the one that exercises the encoding path hardest.
 */
class LuceneCatalogPropertyTest {

  /** Field names {@code toDoc} writes itself; a metadata key that collides
   *  with one of them is a separate question from the ones asked here. */
  private static final Set<String> RESERVED_FIELDS =
      Set.of(
          "product_id",
          "product_name",
          "product_structure",
          "product_transfer_status",
          "product_type_id",
          "product_type_name",
          "product_type_desc",
          "product_type_repoPath",
          "product_type_versioner",
          "reference_orig",
          "reference_data_store",
          "reference_fileSize",
          "reference_mimeType",
          "myfield",
          "CAS.ProductReceivedTime");

  private static final long WRITE_LOCK_TIMEOUT = 60L;
  private static final long COMMIT_LOCK_TIMEOUT = 60L;
  private static final int MERGE_FACTOR = 20;

  /**
   * Metadata keys. Kept to letters and digits: {@code Metadata} reads {@code /}
   * as a group separator and {@code toDoc} has its own reserved field names, so
   * neither is the subject of these properties.
   */
  private static Generator<String> keys() {
    return text()
        .minSize(1)
        .maxSize(8)
        .categories("Lu", "Ll", "Nd")
        .filter(k -> !RESERVED_FIELDS.contains(k));
  }

  /** Ordinary metadata values and product names. */
  private static Generator<String> values() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  /**
   * Text carrying the characters Lucene's query syntax gives a meaning to,
   * plus whitespace and non-Latin letters. A product name or a metadata value
   * is free-form text as far as any caller is concerned, so all of this is
   * input the catalog is meant to handle.
   */
  private static Generator<String> awkwardValues() {
    return text()
        .minSize(1)
        .maxSize(12)
        .categories("Lu", "Ll", "Nd", "Zs", "Po", "Sm", "Ps", "Pe")
        .includeCharacters("\"*?:\\+-&|!(){}[]^~ ");
  }

  // ---------------------------------------------------------------- fixtures

  private static Path newIndexDirectory() throws IOException {
    Path dir = Files.createTempDirectory("lucene-catalog-pbt");
    IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
    config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
    try (IndexWriter writer = new IndexWriter(FSDirectory.open(dir), config)) {
      writer.commit();
    }
    return dir;
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
      walk.forEach(paths::add);
    }
    paths.sort(Comparator.reverseOrder());
    for (Path p : paths) {
      Files.deleteIfExists(p);
    }
  }

  private static LuceneCatalog catalogAt(Path dir, int pageSize) {
    return new LuceneCatalog(
        dir.toString(), null, pageSize, COMMIT_LOCK_TIMEOUT, WRITE_LOCK_TIMEOUT, MERGE_FACTOR);
  }

  private static ProductType genericFile() {
    ProductType type = new ProductType();
    type.setProductTypeId("urn:oodt:GenericFile");
    type.setName("GenericFile");
    type.setDescription("The default product type for any kind of file.");
    type.setProductRepositoryPath("file:/repo");
    type.setVersioner("org.apache.oodt.cas.filemgr.versioning.BasicVersioner");
    return type;
  }

  /**
   * Builds a reference without asking Tika to guess a mime type.
   *
   * <p>{@code Reference(String, String, long)} constructs a {@code Tika} and
   * detects from the name, and Tika's external-parser probing forks a
   * subprocess per external tool it knows about. That is real behaviour worth
   * knowing about, but it is not what these properties are asking about, and
   * paying it once per reference makes the suite unusable.
   */
  private static Reference reference(String orig, String dataStore, long size) {
    return new Reference(orig, dataStore, size, (MimeType) null);
  }

  private static Product flatProduct(String name) {
    Product product = new Product();
    product.setProductName(name);
    product.setProductStructure(Product.STRUCTURE_FLAT);
    product.setTransferStatus(Product.STATUS_RECEIVED);
    product.setProductType(genericFile());
    Vector<Reference> refs = new Vector<>();
    refs.add(reference("file:/orig/" + name, "file:/store/" + name, 12L));
    product.setProductReferences(refs);
    return product;
  }

  private static Metadata metadataOf(Map<String, List<String>> entries) {
    Metadata met = new Metadata();
    for (Map.Entry<String, List<String>> e : entries.entrySet()) {
      met.addMetadata(e.getKey(), e.getValue());
    }
    return met;
  }

  /**
   * Adds a product and its metadata, which is the two-call sequence that
   * actually lands a document in the index: {@code addProduct} only caches, and
   * {@code addMetadata} is what flushes once references are present.
   *
   * @return the product id the catalog assigned.
   */
  private static String ingest(LuceneCatalog catalog, Product product, Metadata met)
      throws CatalogException {
    catalog.addProduct(product);
    catalog.addMetadata(met, product);
    return product.getProductId();
  }

  // -------------------------------------------------------------- properties

  /**
   * A product that has been added must come back by its id, unchanged in every
   * field the catalog stores for it.
   */
  @HegelTest(testCases = 30)
  void addedProductComesBackById(TestCase tc) throws Exception {
    String name = tc.draw(values(), "name");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      Product product = flatProduct(name);
      String id = ingest(catalog, product, new Metadata());

      Product back = catalog.getProductById(id);
      assertNotNull(back, "product " + id + " was not found after being added");
      assertEquals(name, back.getProductName());
      assertEquals(Product.STRUCTURE_FLAT, back.getProductStructure());
      assertEquals(Product.STATUS_RECEIVED, back.getTransferStatus());
      assertEquals("urn:oodt:GenericFile", back.getProductType().getProductTypeId());
      assertEquals("GenericFile", back.getProductType().getName());
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * A product must also come back under the name it was given. Lookup by name
   * is how the ingest path decides whether a product already exists, so a name
   * that does not match itself means a duplicate ingest goes unnoticed.
   */
  @HegelTest(testCases = 30)
  void addedProductComesBackByName(TestCase tc) throws Exception {
    String name = tc.draw(values(), "name");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      String id = ingest(catalog, flatProduct(name), new Metadata());

      Product back = catalog.getProductByName(name);
      assertNotNull(back, "product named '" + name + "' was not found after being added");
      assertEquals(id, back.getProductId());
      assertEquals(name, back.getProductName());
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * A product name made of the characters Lucene's query syntax reserves must
   * round trip like any other. The catalog indexes names as un-analysed string
   * fields and looks them up with a term query, so no escaping should be
   * needed; this states that.
   */
  @HegelTest(testCases = 30)
  void awkwardProductNameComesBackByName(TestCase tc) throws Exception {
    String name = tc.draw(awkwardValues(), "name");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      String id = ingest(catalog, flatProduct(name), new Metadata());

      Product back = catalog.getProductByName(name);
      assertNotNull(back, "product named " + tcQuote(name) + " was not found after being added");
      assertEquals(id, back.getProductId());
      assertEquals(name, back.getProductName(), "the name changed on the way through the index");
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * Every metadata key and value handed to {@code addMetadata} must be readable
   * again through {@code getMetadata}, including keys carrying several values.
   *
   * <p>Containment rather than equality: with no validation layer the catalog
   * also hands back the fields it writes for the product itself, which is its
   * documented behaviour.
   */
  @HegelTest(testCases = 30)
  void metadataSurvivesTheRoundTrip(TestCase tc) throws Exception {
    Map<String, List<String>> entries = drawMetadata(tc, values());

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      Product product = flatProduct("p");
      ingest(catalog, product, metadataOf(entries));

      Metadata back = catalog.getMetadata(product);
      assertNotNull(back);
      assertMetadataContains(entries, back);
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * The same contract over metadata values built from Lucene's reserved
   * characters, whitespace and non-Latin letters. Metadata values are free-form
   * text from a producer's point of view, so a value that cannot survive the
   * index is a silent loss of a caller's data.
   */
  @HegelTest(testCases = 30)
  void awkwardMetadataSurvivesTheRoundTrip(TestCase tc) throws Exception {
    Map<String, List<String>> entries = drawMetadata(tc, awkwardValues());

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      Product product = flatProduct("p");
      ingest(catalog, product, metadataOf(entries));

      Metadata back = catalog.getMetadata(product);
      assertNotNull(back);
      assertMetadataContains(entries, back);
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * A product's references must survive the index: the data store reference is
   * where the archived file actually lives, so losing or mangling one strands
   * the file.
   */
  @HegelTest(testCases = 20)
  void referencesSurviveTheRoundTrip(TestCase tc) throws Exception {
    String name = tc.draw(values(), "name");
    List<Long> sizes = tc.draw(lists(integers().min(0).max(100000)).minSize(1).maxSize(4), "sizes")
        .stream()
        .map(Integer::longValue)
        .collect(java.util.stream.Collectors.toList());

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      Product product = flatProduct(name);
      Vector<Reference> refs = new Vector<>();
      for (int i = 0; i < sizes.size(); i++) {
        refs.add(reference("file:/orig/" + name + "/" + i, "file:/store/" + name + "/" + i,
            sizes.get(i)));
      }
      product.setProductReferences(refs);
      ingest(catalog, product, new Metadata());

      List<Reference> back = catalog.getProductReferences(product);
      assertEquals(refs.size(), back.size(), "reference count changed");
      for (int i = 0; i < refs.size(); i++) {
        assertEquals(refs.get(i).getOrigReference(), back.get(i).getOrigReference());
        assertEquals(refs.get(i).getDataStoreReference(), back.get(i).getDataStoreReference());
        assertEquals(refs.get(i).getFileSize(), back.get(i).getFileSize());
      }
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * {@code getNumProducts} must agree with the number of products actually in
   * the catalog, both after adding and after removing.
   */
  @HegelTest(testCases = 20)
  void numProductsAgreesWithWhatIsInTheCatalog(TestCase tc) throws Exception {
    int added = tc.draw(integers().min(1).max(5), "added");
    int removed = tc.draw(integers().min(0).max(added), "removed");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      ProductType type = genericFile();
      List<Product> products = new ArrayList<>();
      for (int i = 0; i < added; i++) {
        Product p = flatProduct("prod-" + i);
        ingest(catalog, p, new Metadata());
        products.add(p);
      }

      assertEquals(added, catalog.getNumProducts(type), "count wrong after adding " + added);

      for (int i = 0; i < removed; i++) {
        catalog.removeProduct(products.get(i));
      }

      assertEquals(
          added - removed,
          catalog.getNumProducts(type),
          "count wrong after removing " + removed + " of " + added);
      assertEquals(added - removed, catalog.getProducts().size(), "getProducts disagrees");
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * Removing a product must leave nothing of it behind: not the product, and
   * not the metadata that was stored alongside it. A metadata value unique to
   * the removed product must no longer match any query.
   */
  @HegelTest(testCases = 20)
  void removingAProductLeavesNoOrphanMetadata(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(2).max(4), "count");
    int victim = tc.draw(integers().min(0).max(count - 1), "victim");
    String key = tc.draw(keys(), "key");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      ProductType type = genericFile();
      List<Product> products = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        Product p = flatProduct("prod-" + i);
        Metadata met = new Metadata();
        met.addMetadata(key, "value-" + i);
        ingest(catalog, p, met);
        products.add(p);
      }

      Product removedProduct = products.get(victim);
      catalog.removeProduct(removedProduct);

      Query query = new Query();
      query.addCriterion(new TermQueryCriteria(key, "value-" + victim));
      List<String> hits = catalog.query(query, type);
      assertTrue(
          hits.isEmpty(),
          "metadata of the removed product is still indexed: " + hits);

      for (int i = 0; i < count; i++) {
        if (i == victim) {
          continue;
        }
        Query survivor = new Query();
        survivor.addCriterion(new TermQueryCriteria(key, "value-" + i));
        assertEquals(
            List.of(products.get(i).getProductId()),
            catalog.query(survivor, type),
            "removing product " + victim + " disturbed product " + i);
      }
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * {@code removeMetadata} names the metadata to remove. Keys that were not
   * named must still be there afterwards.
   *
   * <p>A caller correcting one bad element should not silently lose every other
   * element the product carries.
   */
  @HegelTest(testCases = 20)
  void removeMetadataRemovesOnlyWhatItIsGiven(TestCase tc) throws Exception {
    String doomedKey = tc.draw(keys(), "doomedKey");
    String keptKey = tc.draw(keys(), "keptKey");
    tc.assume(!doomedKey.equals(keptKey));
    String keptValue = tc.draw(values(), "keptValue");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      Product product = flatProduct("p");
      Metadata met = new Metadata();
      met.addMetadata(doomedKey, "doomed");
      met.addMetadata(keptKey, keptValue);
      ingest(catalog, product, met);

      Metadata toRemove = new Metadata();
      toRemove.addMetadata(doomedKey, "doomed");
      catalog.removeMetadata(toRemove, product);

      Metadata back = catalog.getMetadata(product);
      assertNotNull(back);
      assertEquals(
          List.of(keptValue),
          back.getAllMetadata(keptKey) == null ? List.of() : back.getAllMetadata(keptKey),
          "removing '" + doomedKey + "' also removed '" + keptKey + "'");
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * Modifying a product must not lose the metadata already stored for it. The
   * File Manager calls {@code modifyProduct} through
   * {@code setProductTransferStatus} on every single ingest, so metadata lost
   * here is metadata lost on the normal path.
   */
  @HegelTest(testCases = 20)
  void modifyingAProductKeepsItsMetadata(TestCase tc) throws Exception {
    String key = tc.draw(keys(), "key");
    List<String> vals = tc.draw(lists(values()).minSize(1).maxSize(3), "vals");
    String newName = tc.draw(values(), "newName");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      Product product = flatProduct("original");
      Metadata met = new Metadata();
      met.addMetadata(key, vals);
      String id = ingest(catalog, product, met);

      product.setProductName(newName);
      catalog.modifyProduct(product);

      Product back = catalog.getProductById(id);
      assertNotNull(back);
      assertEquals(newName, back.getProductName(), "the modification did not take");

      Metadata backMet = catalog.getMetadata(product);
      assertNotNull(backMet);
      assertEquals(vals, backMet.getAllMetadata(key), "metadata was lost by modifyProduct");
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * Walking the pages from the first to the last must account for every product
   * exactly once, with page numbers that stay inside the range the pages
   * themselves report.
   *
   * <p>This is the property a user interface paging through a catalog depends
   * on: a product that appears on two pages is counted twice, and one that
   * appears on none is invisible.
   */
  @HegelTest(testCases = 20)
  void pagesPartitionTheProductSet(TestCase tc) throws Exception {
    int pageSize = tc.draw(integers().min(1).max(3), "pageSize");
    int count = tc.draw(integers().min(1).max(8), "count");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, pageSize);
      ProductType type = genericFile();
      Set<String> expected = new HashSet<>();
      for (int i = 0; i < count; i++) {
        Product p = flatProduct("prod-" + i);
        ingest(catalog, p, new Metadata());
        expected.add(p.getProductId());
      }

      int expectedPages = (count + pageSize - 1) / pageSize;

      ProductPage page = catalog.getFirstPage(type);
      assertNotNull(page);
      assertEquals(1, page.getPageNum(), "the first page is not numbered 1");
      assertEquals(expectedPages, page.getTotalPages(), "wrong total page count");

      List<String> seen = new ArrayList<>();
      int guard = 0;
      while (true) {
        assertTrue(
            page.getPageNum() >= 1 && page.getPageNum() <= page.getTotalPages(),
            "page number " + page.getPageNum() + " is outside 1.." + page.getTotalPages());
        assertTrue(
            page.getPageProducts().size() <= pageSize,
            "page " + page.getPageNum() + " holds more than " + pageSize + " products");
        for (Product p : page.getPageProducts()) {
          seen.add(p.getProductId());
        }
        if (page.isLastPage()) {
          break;
        }
        page = catalog.getNextPage(type, page);
        if (++guard > count + 5) {
          fail("paging did not terminate after " + guard + " pages");
        }
      }

      assertEquals(count, seen.size(), "products seen across all pages: " + seen);
      assertEquals(expected, new HashSet<>(seen), "the pages are not a partition of the catalog");
      assertEquals(
          expectedPages,
          page.getPageNum(),
          "walking with getNextPage did not end on the last page");

      ProductPage last = catalog.getLastProductPage(type);
      assertEquals(page.getPageNum(), last.getPageNum(), "getLastProductPage disagrees");
      assertEquals(
          page.getPageProducts().size(),
          last.getPageProducts().size(),
          "getLastProductPage returned a different last page");
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * Asking for a page beyond the last must not hand back products.
   *
   * <p>A caller that walks pages by number — which is what {@code pagedQuery}
   * is for, since it takes the number as an argument — has no way to tell a
   * genuine page from one it has already seen. Returning the first page's
   * products under a page number past the end makes a bounded loop unbounded
   * and duplicates every product on it.
   */
  @HegelTest(testCases = 20)
  void aPageBeyondTheLastIsEmpty(TestCase tc) throws Exception {
    int pageSize = tc.draw(integers().min(1).max(3), "pageSize");
    int count = tc.draw(integers().min(1).max(8), "count");
    int overshoot = tc.draw(integers().min(1).max(3), "overshoot");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, pageSize);
      ProductType type = genericFile();
      for (int i = 0; i < count; i++) {
        ingest(catalog, flatProduct("prod-" + i), new Metadata());
      }

      int totalPages = (count + pageSize - 1) / pageSize;
      int beyond = totalPages + overshoot;
      ProductPage page = catalog.pagedQuery(new Query(), type, beyond);

      assertNotNull(page);
      assertTrue(
          page.getPageProducts().isEmpty(),
          "page "
              + beyond
              + " of a "
              + totalPages
              + "-page catalog returned "
              + page.getPageProducts().size()
              + " products");
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * A term query must return exactly the products carrying that value for that
   * key, and nothing else.
   */
  @HegelTest(testCases = 20)
  void aTermQueryReturnsExactlyTheMatchingProducts(TestCase tc) throws Exception {
    String key = tc.draw(keys(), "key");
    List<String> assigned = tc.draw(
        lists(sampledFrom(List.of("alpha", "beta", "gamma"))).minSize(1).maxSize(5), "assigned");
    String wanted = tc.draw(sampledFrom(List.of("alpha", "beta", "gamma")), "wanted");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      ProductType type = genericFile();
      Set<String> expected = new HashSet<>();
      for (int i = 0; i < assigned.size(); i++) {
        Product p = flatProduct("prod-" + i);
        Metadata met = new Metadata();
        met.addMetadata(key, assigned.get(i));
        ingest(catalog, p, met);
        if (assigned.get(i).equals(wanted)) {
          expected.add(p.getProductId());
        }
      }

      Query query = new Query();
      query.addCriterion(new TermQueryCriteria(key, wanted));
      assertEquals(
          expected,
          new HashSet<>(catalog.query(query, type)),
          "query for " + key + "=" + wanted + " over " + assigned);
    } finally {
      deleteRecursively(dir);
    }
  }

  // ------------------------------------------------------------ model-based

  private enum Op {
    ADD,
    REMOVE,
    MODIFY,
    REMOVE_METADATA
  }

  /**
   * A sequence of catalog commands applied to the real catalog and to an
   * in-memory model of what a catalog is supposed to hold. After every single
   * command the two must still agree on the set of products, on each product's
   * name, and on the count the catalog reports.
   *
   * <p>Single operations in isolation are the easy case. This is what catches
   * state that one command leaves behind for the next: a cache entry that
   * outlives the document it stood for, a removal that a later add trips over,
   * a modification that resurrects something.
   */
  @HegelTest(testCases = 20)
  void catalogAgreesWithAnInMemoryModelAfterEveryCommand(TestCase tc) throws Exception {
    List<Op> ops =
        tc.draw(lists(sampledFrom(List.of(Op.values()))).minSize(1).maxSize(6), "ops");
    List<String> names = tc.draw(lists(values()).minSize(1).maxSize(6), "names");
    List<Integer> picks =
        tc.draw(lists(integers().min(0).max(99)).minSize(1).maxSize(6), "picks");

    Path dir = newIndexDirectory();
    try {
      LuceneCatalog catalog = catalogAt(dir, 20);
      ProductType type = genericFile();

      /* productId -> name, in insertion order so a pick is reproducible */
      Map<String, String> model = new LinkedHashMap<>();
      /* productId -> the live Product object, which is what the catalog wants */
      Map<String, Product> live = new LinkedHashMap<>();

      for (int step = 0; step < ops.size(); step++) {
        Op op = ops.get(step);
        String name = names.get(step % names.size());
        int pick = picks.get(step % picks.size());

        String where = "at step " + step + " (" + op + ") of " + ops;

        try {
          switch (op) {
            case ADD: {
              Product p = flatProduct(name);
              Metadata met = new Metadata();
              met.addMetadata("Marker", "kept");
              ingest(catalog, p, met);
              model.put(p.getProductId(), name);
              live.put(p.getProductId(), p);
              break;
            }
            case REMOVE: {
              String id = nth(model.keySet(), pick);
              if (id == null) {
                break;
              }
              catalog.removeProduct(live.get(id));
              model.remove(id);
              live.remove(id);
              break;
            }
            case MODIFY: {
              String id = nth(model.keySet(), pick);
              if (id == null) {
                break;
              }
              Product p = live.get(id);
              p.setProductName(name);
              catalog.modifyProduct(p);
              model.put(id, name);
              break;
            }
            case REMOVE_METADATA: {
              String id = nth(model.keySet(), pick);
              if (id == null) {
                break;
              }
              Metadata toRemove = new Metadata();
              toRemove.addMetadata("Marker", "kept");
              catalog.removeMetadata(toRemove, live.get(id));
              break;
            }
            default:
              throw new IllegalStateException("unhandled op " + op);
          }
        } catch (CatalogException e) {
          fail(
              "the catalog refused a command it is supposed to accept, "
                  + where
                  + " with "
                  + model.size()
                  + " products already in the index: "
                  + rootCauseOf(e));
        }

        assertEquals(model.size(), catalog.getNumProducts(type), "getNumProducts " + where);
        assertEquals(model.size(), catalog.getProducts().size(), "getProducts " + where);

        Set<String> actualIds = new HashSet<>();
        for (Product p : catalog.getProducts()) {
          actualIds.add(p.getProductId());
        }
        assertEquals(model.keySet(), actualIds, "the set of products " + where);

        for (Map.Entry<String, String> e : model.entrySet()) {
          Product back = catalog.getProductById(e.getKey());
          assertNotNull(back, "product " + e.getKey() + " missing " + where);
          assertEquals(e.getValue(), back.getProductName(), "name of " + e.getKey() + " " + where);
        }
      }
    } finally {
      deleteRecursively(dir);
    }
  }

  // ------------------------------------------------------------------ helpers

  /** The message at the bottom of an exception chain, which is where Lucene's is. */
  private static String rootCauseOf(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause.getClass().getName() + ": " + cause.getMessage();
  }

  private static String nth(Set<String> ids, int pick) {
    if (ids.isEmpty()) {
      return null;
    }
    List<String> asList = new ArrayList<>(ids);
    return asList.get(pick % asList.size());
  }

  private static Map<String, List<String>> drawMetadata(TestCase tc, Generator<String> valueGen) {
    List<String> keys = tc.draw(lists(keys()).minSize(1).maxSize(4), "keys");
    Map<String, List<String>> entries = new LinkedHashMap<>();
    for (int i = 0; i < keys.size(); i++) {
      List<String> vals = tc.draw(lists(valueGen).minSize(1).maxSize(3), "values" + i);
      entries.put(keys.get(i), vals);
    }
    return entries;
  }

  private static void assertMetadataContains(
      Map<String, List<String>> expected, Metadata actual) {
    for (Map.Entry<String, List<String>> e : expected.entrySet()) {
      List<String> back = actual.getAllMetadata(e.getKey());
      assertNotNull(back, "key " + tcQuote(e.getKey()) + " is missing from the catalog");
      assertFalse(back.isEmpty(), "key " + tcQuote(e.getKey()) + " came back with no values");
      assertEquals(
          e.getValue(),
          back,
          "values for key " + tcQuote(e.getKey()) + " changed on the way through the index");
    }
  }

  /** Renders a string with its awkward characters visible in a failure message. */
  private static String tcQuote(String s) {
    StringBuilder sb = new StringBuilder("'");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x20 || c > 0x7e) {
        sb.append(String.format("\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.append('\'').toString();
  }
}
