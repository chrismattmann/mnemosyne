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

package org.apache.oodt.cas.filemgr.system;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypes;
import org.apache.oodt.cas.filemgr.metadata.CoreMetKeys;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductPage;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;
import org.apache.oodt.cas.filemgr.structs.exceptions.RepositoryManagerException;
import org.apache.oodt.cas.filemgr.structs.exceptions.ValidationLayerException;
import org.apache.oodt.cas.metadata.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Properties for the catalog half of {@link FileManager} — cataloguing,
 * metadata, querying, paging and transfer bookkeeping — reached through
 * {@link AvroFileManagerClient} over a real Avro RPC connection.
 *
 * <p>{@code AvroFileManagerRpcPropertyTest} next door drives the ingest path,
 * where a producer hands the File Manager a file and the File Manager archives
 * it. This one drives the path a curator or a crawler takes instead: catalogue
 * a product, hand it its references, hand it its metadata, then find it again
 * by name, by query and by page. Nothing here writes a file into the archive,
 * so a property costs a few milliseconds and can afford to say something about
 * every field.
 *
 * <p>The catalog is configured to page two products at a time, which is what
 * makes paging worth stating a property about at all: with the deployed page
 * size of twenty, a property would need twenty-one products before the second
 * page existed. Product counts are kept small for the same reason: the Lucene
 * catalog opens, commits and closes an index writer for every product it
 * indexes, so each product costs a file sync.
 *
 * <p>The server is started fresh for each property on a port the operating
 * system chose, never a fixed one. Its catalog, policy and archive live under a
 * temporary directory that is deleted afterwards.
 */
class AvroFileManagerCatalogRpcPropertyTest {

  private static final String LOCAL_TRANSFER_FACTORY =
      "org.apache.oodt.cas.filemgr.datatransfer.LocalDataTransferFactory";

  private static final String GENERIC_FILE = "GenericFile";
  private static final String GENERIC_FILE_ID = "urn:oodt:GenericFile";

  /** The multi-valued element this fixture declares for {@code GenericFile}. */
  private static final String TEST_ELEMENT = "TestElement";

  /**
   * A mime type to hang on every reference this fixture builds.
   *
   * <p>Not optional in practice: the {@code MimeTypeExtractor} the checked-in
   * policy runs server-side dereferences a reference's mime type without
   * checking it, so a reference built with the four-argument constructor and a
   * null mime type takes the server down on {@code addMetadata}. Building it
   * from Tika's registry rather than through {@code Reference(String, String,
   * long)} keeps the fixture from detecting a type per reference, which is a
   * subprocess a piece.
   */
  private static final MimeType TEXT_PLAIN = textPlain();

  private static MimeType textPlain() {
    try {
      return MimeTypes.getDefaultMimeTypes().forName("text/plain");
    } catch (Exception e) {
      throw new IllegalStateException("no text/plain in Tika's registry", e);
    }
  }

  /** Products per page, chosen small so that paging has more than one page. */
  private static final int PAGE_SIZE = 2;

  private Properties savedProperties;
  private Path root;
  private Path policyDir;
  private Path archiveDir;
  private Path catalogDir;
  private int port;
  private AvroFileManagerServer server;
  private FileManagerClient client;

  /** Names that are safe as a file name and as a path component. */
  private static Generator<String> names() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  /**
   * Metadata values including the characters a wire is most likely to lose:
   * accents, non-Latin scripts, quotes, newlines and a value long enough to
   * cross a buffer.
   */
  private static Generator<String> awkwardValues() {
    return sampledFrom(List.of(
        "plain",
        "accented-éèü",
        "日本語のテキスト",
        "quote'and\"quote",
        "line\nbreak\ttab",
        "very-long-" + "x".repeat(1200)));
  }

  // ---------------------------------------------------------------- fixtures

  @BeforeEach
  void startServer() throws Exception {
    savedProperties = (Properties) System.getProperties().clone();

    root = Files.createTempDirectory("filemgr-catalog-rpc-pbt");
    policyDir = Files.createDirectories(root.resolve("policy"));
    archiveDir = Files.createDirectories(root.resolve("archive"));
    /* must not exist: LuceneCatalogFactory only creates the index if it is absent */
    catalogDir = root.resolve("cat");

    copyPolicy();

    Properties properties = new Properties(System.getProperties());
    properties.setProperty("filemgr.catalog.factory",
        "org.apache.oodt.cas.filemgr.catalog.LuceneCatalogFactory");
    properties.setProperty("filemgr.repository.factory",
        "org.apache.oodt.cas.filemgr.repository.XMLRepositoryManagerFactory");
    properties.setProperty("filemgr.validationLayer.factory",
        "org.apache.oodt.cas.filemgr.validation.XMLValidationLayerFactory");
    properties.setProperty("filemgr.datatransfer.factory", LOCAL_TRANSFER_FACTORY);
    properties.setProperty("org.apache.oodt.cas.filemgr.catalog.lucene.idxPath",
        catalogDir.toString());
    properties.setProperty("org.apache.oodt.cas.filemgr.catalog.lucene.pageSize",
        String.valueOf(PAGE_SIZE));
    properties.setProperty(
        "org.apache.oodt.cas.filemgr.catalog.lucene.commitLockTimeout.seconds", "60");
    properties.setProperty(
        "org.apache.oodt.cas.filemgr.catalog.lucene.writeLockTimeout.seconds", "60");
    properties.setProperty("org.apache.oodt.cas.filemgr.catalog.lucene.mergeFactor", "20");
    properties.setProperty("org.apache.oodt.cas.filemgr.repositorymgr.dirs",
        policyDir.toUri().toString());
    properties.setProperty("org.apache.oodt.cas.filemgr.validation.dirs",
        policyDir.toUri().toString());
    properties.setProperty("org.apache.oodt.cas.filemgr.metadata.expandProduct", "false");

    URL mimeTypes = getClass().getResource("/mime-types.xml");
    properties.setProperty("org.apache.oodt.cas.filemgr.mime.type.repository",
        Path.of(mimeTypes.toURI()).toString());

    System.setProperties(properties);

    port = ephemeralPort();
    server = new AvroFileManagerServer(port);
    server.startUp();
    client = new AvroFileManagerClient(new URL("http://localhost:" + port));
  }

  @AfterEach
  void stopServer() throws Exception {
    try {
      if (client != null) {
        client.close();
      }
    } finally {
      try {
        if (server != null) {
          server.shutdown();
        }
      } finally {
        client = null;
        server = null;
        System.setProperties(savedProperties);
        deleteRecursively(root);
      }
    }
  }

  /**
   * Asks the operating system for a port nobody is using and hands it back.
   *
   * <p>A hard-coded port is how a suite ends up failing on a machine that
   * happens to run something else.
   */
  private static int ephemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  /**
   * Copies the checked-in ingest policy into the temporary directory, pointing
   * the repository at the temporary archive and declaring {@code TestElement}
   * for {@code GenericFile}.
   */
  private void copyPolicy() throws IOException {
    for (String file : List.of("elements.xml", "product-type-element-map.xml",
        "product-types.xml")) {
      try (var in = getClass().getResourceAsStream("/ingest/fmpolicy/" + file)) {
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (file.equals("product-types.xml")) {
          content = content.replace("file:///tmp", "file://" + archiveDir);
        }
        if (file.equals("product-type-element-map.xml")) {
          content =
              content.replace(
                  "<element id=\"urn:oodt:MimeType\"/>",
                  "<element id=\"urn:oodt:MimeType\"/>\n"
                      + "       <element id=\"urn:oodt:TestElement\"/>");
        }
        Files.writeString(policyDir.resolve(file), content, StandardCharsets.UTF_8);
      }
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    try (var walk = Files.walk(dir)) {
      walk.forEach(paths::add);
    }
    paths.sort(Comparator.reverseOrder());
    for (Path p : paths) {
      Files.deleteIfExists(p);
    }
  }

  private ProductType genericFileType() {
    ProductType type = new ProductType();
    type.setProductTypeId(GENERIC_FILE_ID);
    type.setName(GENERIC_FILE);
    return type;
  }

  /** A product name no other case in this property will have used. */
  private static String uniqueName(String base, int salt) {
    return base + "-" + salt + "-" + System.nanoTime();
  }

  /**
   * The catalogue-only path: register the product, give it a reference, give
   * it its metadata. The catalog holds a product in memory until it has both a
   * reference with a data-store location and a metadata block, so all three
   * calls are needed before the product is findable.
   *
   * @return the product id the File Manager assigned.
   */
  private String catalogue(Product product, Metadata met) throws Exception {
    String productId = client.catalogProduct(product);
    product.setProductId(productId);

    Reference ref = new Reference(
        "file:/staging/" + product.getProductName(),
        archiveDir.toUri() + product.getProductName(),
        42L,
        TEXT_PLAIN);
    product.setProductReferences(List.of(ref));
    client.addProductReferences(product);

    met.replaceMetadata(CoreMetKeys.PRODUCT_NAME, product.getProductName());
    client.addMetadata(product, met);
    return productId;
  }

  /** A product of the fixture's product type, not yet catalogued. */
  private Product product(String name) {
    Product product = new Product();
    product.setProductName(name);
    product.setProductType(genericFileType());
    product.setProductStructure(Product.STRUCTURE_FLAT);
    product.setTransferStatus(Product.STATUS_RECEIVED);
    return product;
  }

  // -------------------------------------------------------------- properties

  /**
   * Every field of a catalogued {@link Product} must come back over the wire:
   * its identifier, its name, its type, its structure and its transfer status.
   *
   * <p>Structure is what tells a consumer whether the reference it is holding
   * names a file or a directory; transfer status is what tells it whether the
   * bytes are there yet. Neither can be recovered from anywhere else.
   */
  @HegelTest(testCases = 10)
  void everyFieldOfACataloguedProductSurvivesTheWire(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    String structure = tc.draw(
        sampledFrom(List.of(Product.STRUCTURE_FLAT, Product.STRUCTURE_HIERARCHICAL)),
        "structure");
    String status = tc.draw(
        sampledFrom(List.of(Product.STATUS_RECEIVED, Product.STATUS_TRANSFER)),
        "status");
    String name = uniqueName(base, 0);

    Product sent = product(name);
    sent.setProductStructure(structure);
    sent.setTransferStatus(status);
    String productId = catalogue(sent, new Metadata());

    Product back = client.getProductById(productId);
    assertNotNull(back, "the product just catalogued is not readable back");
    assertEquals(productId, back.getProductId(), "the product id changed on the wire");
    assertEquals(name, back.getProductName(), "the product name changed on the wire");
    assertEquals(structure, back.getProductStructure(),
        "the product structure changed on the wire");
    assertEquals(status, back.getTransferStatus(),
        "the product transfer status changed on the wire");
    assertNotNull(back.getProductType(), "the product came back with no product type");
    assertEquals(GENERIC_FILE_ID, back.getProductType().getProductTypeId(),
        "the product type id changed on the wire");
    assertEquals(GENERIC_FILE, back.getProductType().getName(),
        "the product type name changed on the wire");

    Product byName = client.getProductByName(name);
    assertNotNull(byName, "the product cannot be found by the name it was given");
    assertEquals(productId, byName.getProductId(),
        "lookup by name and lookup by id disagree");
  }

  /**
   * A reference given to a product over RPC must come back with the same
   * original location, data-store location and size.
   *
   * <p>The data-store reference is the only record of where a product's bytes
   * live; a size that arrives wrong misreports the archive's contents.
   */
  @HegelTest(testCases = 10)
  void referencesSurviveTheWire(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    long fileSize = tc.draw(longs().min(0L).max(1_000_000_000_000L), "fileSize");
    String name = uniqueName(base, 1);

    Product sent = product(name);
    String productId = client.catalogProduct(sent);
    sent.setProductId(productId);

    Reference ref = new Reference(
        "file:/staging/" + name, archiveDir.toUri() + name, fileSize, TEXT_PLAIN);
    sent.setProductReferences(List.of(ref));
    client.addProductReferences(sent);
    client.addMetadata(sent, new Metadata());

    Product back = client.getProductById(productId);
    List<Reference> refs = client.getProductReferences(back);
    assertNotNull(refs, "no references came back");
    assertEquals(1, refs.size(), "one reference was added, so one should come back");
    assertEquals(ref.getOrigReference(), refs.get(0).getOrigReference(),
        "the original reference changed on the wire");
    assertEquals(ref.getDataStoreReference(), refs.get(0).getDataStoreReference(),
        "the data store reference changed on the wire");
    assertEquals(fileSize, refs.get(0).getFileSize(), "the file size changed on the wire");
  }

  /**
   * Metadata carrying accents, non-Latin scripts, quotes, newlines or a value
   * long enough to cross a buffer must come back spelled the way it was sent,
   * in the order it was sent, whether read whole or reduced to one element.
   */
  @HegelTest(testCases = 10)
  void awkwardMetadataSurvivesTheWire(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    List<String> values = tc.draw(lists(awkwardValues()).minSize(1).maxSize(3), "values");
    String name = uniqueName(base, 2);

    Metadata met = new Metadata();
    met.replaceMetadata(TEST_ELEMENT, values);

    Product sent = product(name);
    String productId = catalogue(sent, met);

    Product back = client.getProductById(productId);
    Metadata backMet = client.getMetadata(back);
    assertNotNull(backMet, "no metadata came back for " + productId);
    assertEquals(values, backMet.getAllMetadata(TEST_ELEMENT),
        "the multi-valued element changed on the wire");
    /* the policy runs its core extractor namespaced, so the catalogued
       element is CAS.ProductName; a plain ProductName is not a declared
       element for this type and the catalog is right not to return it */
    assertEquals(name, backMet.getMetadata("CAS.ProductName"),
        "the product name in the metadata changed on the wire");

    Metadata reduced = client.getReducedMetadata(back, List.of(TEST_ELEMENT));
    assertEquals(values, reduced.getAllMetadata(TEST_ELEMENT),
        "getReducedMetadata disagrees with getMetadata");
  }

  /**
   * Updating a product's metadata over RPC must replace what was there, and a
   * later read must report only the new values.
   */
  @HegelTest(testCases = 10)
  void updatedMetadataReplacesWhatWasThere(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    List<String> before = tc.draw(lists(names()).minSize(1).maxSize(3), "before");
    List<String> after = tc.draw(lists(names()).minSize(1).maxSize(3), "after");
    String name = uniqueName(base, 3);

    Metadata met = new Metadata();
    met.replaceMetadata(TEST_ELEMENT, before);
    Product sent = product(name);
    String productId = catalogue(sent, met);

    Product back = client.getProductById(productId);
    Metadata replacement = new Metadata();
    replacement.replaceMetadata(CoreMetKeys.PRODUCT_NAME, name);
    replacement.replaceMetadata(TEST_ELEMENT, after);
    assertTrue(client.updateMetadata(back, replacement), "updateMetadata reported failure");

    Metadata now = client.getMetadata(client.getProductById(productId));
    assertEquals(after, now.getAllMetadata(TEST_ELEMENT),
        "the metadata read back is not the metadata that replaced it");
  }

  /**
   * A term query over RPC must return exactly the products carrying that term,
   * and nothing else.
   *
   * <p>A query is the only way a consumer finds a product it did not catalogue
   * itself. One extra product in the answer is a consumer processing data it
   * was not asked for; one missing is data nobody ever sees.
   */
  @HegelTest(testCases = 10)
  void aTermQueryReturnsExactlyTheProductsCarryingTheTerm(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int matching = tc.draw(integers().min(1).max(2), "matching");
    int other = tc.draw(integers().min(0).max(1), "other");
    String wanted = uniqueName(base, 4);
    String unwanted = uniqueName(base, 5);

    Set<String> expected = new HashSet<>();
    for (int i = 0; i < matching; i++) {
      Metadata met = new Metadata();
      met.replaceMetadata(TEST_ELEMENT, wanted);
      expected.add(catalogue(product(uniqueName(base, 10 + i)), met));
    }
    for (int i = 0; i < other; i++) {
      Metadata met = new Metadata();
      met.replaceMetadata(TEST_ELEMENT, unwanted);
      catalogue(product(uniqueName(base, 20 + i)), met);
    }

    Query query = new Query();
    query.addCriterion(new TermQueryCriteria(TEST_ELEMENT, wanted));
    Set<String> found = idsOf(client.query(query, genericFileType()));
    assertEquals(expected, found, "the query answer is not the set of matching products");
  }

  /**
   * Paging forward from the first page must visit every catalogued product
   * exactly once, and each page must report the size the catalog paged with.
   *
   * <p>This is how every listing in the system is built. A page that repeats a
   * product double-counts it; a page that skips one hides it from every
   * consumer that pages rather than querying.
   *
   * <p>Stated against the whole product-type listing rather than against this
   * case's products alone: the server is shared across a property's cases, so
   * the catalog also holds whatever the cases before this one put there, and a
   * property asserting an absolute set would be asserting something about them.
   */
  @HegelTest(testCases = 6)
  void pagingForwardVisitsEveryProductOnce(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(1).max(3), "count");

    Set<String> catalogued = new HashSet<>();
    for (int i = 0; i < count; i++) {
      catalogued.add(catalogue(product(uniqueName(base, 30 + i)), new Metadata()));
    }

    ProductType type = genericFileType();
    ProductPage page = client.getFirstPage(type);
    assertNotNull(page, "getFirstPage returned nothing");
    assertEquals(PAGE_SIZE, page.getPageSize(),
        "a page does not report the size the catalog paged with");
    assertEquals(1, page.getPageNum(), "the first page is not numbered one");

    List<String> visited = new ArrayList<>();
    int guard = 0;
    while (page != null && !page.isLastPage() && guard++ < 50) {
      assertEquals(PAGE_SIZE, page.getPageSize(), "a later page reports a different size");
      visited.addAll(idsOf(page.getPageProducts()));
      page = client.getNextPage(type, page);
    }
    if (page != null) {
      visited.addAll(idsOf(page.getPageProducts()));
    }

    assertEquals(visited.size(), new HashSet<>(visited).size(),
        "paging visited a product more than once: " + visited);
    assertTrue(new HashSet<>(visited).containsAll(catalogued),
        "paging skipped a product that was catalogued");
    assertEquals(idsOf(client.getProductsByProductType(type)), new HashSet<>(visited),
        "paging and the product type listing disagree about what is catalogued");
  }

  /**
   * The last page must be reachable both by asking for it and by paging
   * forward, and paging back from it must return to the first page.
   */
  @HegelTest(testCases = 6)
  void theLastPageIsWhereForwardPagingEnds(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(PAGE_SIZE + 1).max(PAGE_SIZE + 2), "count");

    for (int i = 0; i < count; i++) {
      catalogue(product(uniqueName(base, 40 + i)), new Metadata());
    }

    ProductType type = genericFileType();
    ProductPage last = client.getLastPage(type);
    assertNotNull(last, "getLastPage returned nothing");
    assertTrue(last.getPageNum() > 1,
        "more products than a page holds were catalogued, yet the last page is the first");

    ProductPage prev = client.getPrevPage(type, last);
    assertNotNull(prev, "getPrevPage returned nothing");
    assertEquals(last.getPageNum() - 1, prev.getPageNum(),
        "the previous page is not the page before the last");

    ProductPage forward = client.getNextPage(type, prev);
    assertEquals(last.getPageNum(), forward.getPageNum(),
        "paging forward from the page before the last does not reach the last");
    assertEquals(idsOf(last.getPageProducts()), idsOf(forward.getPageProducts()),
        "the last page reached two ways holds different products");
  }

  /**
   * A paged query must agree with the unpaged query it pages: the union of its
   * pages is the query's whole answer.
   */
  @HegelTest(testCases = 6)
  void aPagedQueryAgreesWithTheQueryItPages(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(1).max(3), "count");
    String term = uniqueName(base, 50);

    for (int i = 0; i < count; i++) {
      Metadata met = new Metadata();
      met.replaceMetadata(TEST_ELEMENT, term);
      catalogue(product(uniqueName(base, 60 + i)), met);
    }

    Query query = new Query();
    query.addCriterion(new TermQueryCriteria(TEST_ELEMENT, term));
    ProductType type = genericFileType();

    Set<String> unpaged = idsOf(client.query(query, type));
    assertEquals(count, unpaged.size(), "the unpaged query did not find what was catalogued");

    Set<String> paged = new HashSet<>();
    int pages = (count + PAGE_SIZE - 1) / PAGE_SIZE;
    for (int pageNum = 1; pageNum <= pages; pageNum++) {
      ProductPage page = client.pagedQuery(query, type, pageNum);
      assertNotNull(page, "pagedQuery returned nothing for page " + pageNum);
      paged.addAll(idsOf(page.getPageProducts()));
    }
    assertEquals(unpaged, paged, "the paged query and the unpaged query disagree");
  }

  /**
   * Cataloguing {@code n} products must raise the reported product count by
   * exactly {@code n}, and {@code getTopNProducts} must never return more than
   * it was asked for.
   */
  @HegelTest(testCases = 8)
  void cataloguingNproductsRaisesTheCountByN(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(1).max(2), "count");
    int topN = tc.draw(integers().min(1).max(6), "topN");

    ProductType type = genericFileType();
    int before = client.getNumProducts(type);

    Set<String> catalogued = new HashSet<>();
    for (int i = 0; i < count; i++) {
      catalogued.add(catalogue(product(uniqueName(base, 70 + i)), new Metadata()));
    }

    assertEquals(before + count, client.getNumProducts(type),
        "getNumProducts did not move by the number catalogued");
    assertTrue(idsOf(client.getProductsByProductType(type)).containsAll(catalogued),
        "a catalogued product is missing from the product type listing");

    List<Product> top = client.getTopNProducts(topN, type);
    assertNotNull(top, "getTopNProducts returned nothing");
    assertTrue(top.size() <= topN,
        "getTopNProducts returned " + top.size() + " products when asked for " + topN);
  }

  /**
   * A product type added over RPC must be served back by name and by id with
   * every field it was given.
   *
   * <p>A product type is what tells the File Manager where to archive and how
   * to version. A repository path that arrives wrong sends the next ingest
   * somewhere nobody is looking.
   */
  @HegelTest(testCases = 10)
  void anAddedProductTypeIsServedBackWhole(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    String description = tc.draw(awkwardValues(), "description");
    String typeName = uniqueName(base, 80);
    String typeId = "urn:oodt:" + typeName;

    ProductType type = new ProductType();
    type.setProductTypeId(typeId);
    type.setName(typeName);
    type.setDescription(description);
    type.setProductRepositoryPath(archiveDir.toUri().toString());
    type.setVersioner("org.apache.oodt.cas.filemgr.versioning.BasicVersioner");

    assertEquals(typeId, client.addProductType(type),
        "addProductType did not report the id it was given");

    ProductType byName = client.getProductTypeByName(typeName);
    assertNotNull(byName, "the type just added is not served by name");
    assertEquals(typeId, byName.getProductTypeId(), "the type id changed on the wire");
    assertEquals(description, byName.getDescription(),
        "the type description changed on the wire");
    assertEquals(archiveDir.toUri().toString(), byName.getProductRepositoryPath(),
        "the type repository path changed on the wire");
    assertEquals("org.apache.oodt.cas.filemgr.versioning.BasicVersioner",
        byName.getVersioner(), "the type versioner changed on the wire");

    ProductType byId = client.getProductTypeById(typeId);
    assertNotNull(byId, "the type just added is not served by id");
    assertEquals(typeName, byId.getName(), "lookup by id disagrees with lookup by name");
  }

  /**
   * Transfer bookkeeping must be visible to a client while it is happening and
   * gone once it is cleared.
   *
   * <p>This is what an operator watches during an ingest, and the only way to
   * tell a stalled transfer from a finished one.
   *
   * <p>The fixture writes a partly-arrived file into the archive and declares a
   * larger size for it. That is what "in progress" means to the status tracker:
   * a reference whose file exists and is shorter than the size the catalog
   * recorded. A product merely marked as transferring, with nothing on disk,
   * is not a transfer the tracker has anything to report about.
   */
  @HegelTest(testCases = 10)
  void aTransferInProgressIsVisibleUntilItIsCleared(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int arrived = tc.draw(integers().min(1).max(50), "arrived");
    int total = tc.draw(integers().min(60).max(200), "total");
    String name = uniqueName(base, 90);

    Path partial = archiveDir.resolve(name);
    Files.writeString(partial, "x".repeat(arrived), StandardCharsets.UTF_8);

    Product sent = product(name);
    sent.setTransferStatus(Product.STATUS_TRANSFER);
    String productId = client.catalogProduct(sent);
    sent.setProductId(productId);
    sent.setProductReferences(List.of(new Reference(
        "file:/staging/" + name, partial.toUri().toString(), total, TEXT_PLAIN)));
    client.addProductReferences(sent);
    client.addMetadata(sent, new Metadata());

    Product tracked = client.getProductById(productId);
    tracked.setProductReferences(client.getProductReferences(tracked));

    assertTrue(client.transferringProduct(tracked), "transferringProduct reported failure");
    assertTrue(idsOfTransfers(client.getCurrentFileTransfers()).contains(productId),
        "a product being transferred is not in the current transfer listing");
    assertNotNull(client.getCurrentFileTransfer(),
        "a transfer is in progress but no current file transfer is reported");

    double pct = client.getProductPctTransferred(tracked);
    assertTrue(pct > 0.0 && pct < 1.0,
        "a partly arrived product does not report a partial percentage: " + pct);
    assertEquals((double) arrived / total,
        client.getRefPctTransferred(tracked.getProductReferences().get(0)), 1e-9,
        "the reference percentage is not the fraction of the file that arrived");

    assertTrue(client.removeProductTransferStatus(tracked),
        "removeProductTransferStatus reported failure");
    assertFalse(idsOfTransfers(client.getCurrentFileTransfers()).contains(productId),
        "a product whose transfer status was cleared is still in the transfer listing");
  }

  /**
   * Removing a product over RPC must remove that product and no other.
   */
  @HegelTest(testCases = 8)
  void removingOneProductLeavesTheRest(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(2).max(3), "count");
    int victim = tc.draw(integers().min(0).max(count - 1), "victim");

    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(catalogue(product(uniqueName(base, 100 + i)), new Metadata()));
    }

    ProductType type = genericFileType();
    int before = client.getNumProducts(type);

    Product doomed = client.getProductById(ids.get(victim));
    assertTrue(client.removeProduct(doomed), "removeProduct reported failure");

    assertEquals(before - 1, client.getNumProducts(type),
        "removing one product changed the count by something other than one");
    Set<String> remaining = idsOf(client.getProductsByProductType(type));
    assertFalse(remaining.contains(ids.get(victim)),
        "the removed product is still listed under its type");
    for (int i = 0; i < count; i++) {
      if (i != victim) {
        assertTrue(remaining.contains(ids.get(i)),
            "removing product " + victim + " also removed product " + i);
      }
    }
  }

  /**
   * A lookup for something the catalog does not hold must arrive at the client
   * as the exception the interface declares, or as a defined empty answer —
   * never as a transport-level error and never as a call that does not return.
   *
   * <p>{@code AvroFileManagerServer} raises {@code AvroRemoteException} with a
   * message string almost everywhere, which is the form the protocol can
   * actually carry, so these ought to arrive named. What they cannot survive is
   * the server failing before it reaches its own {@code catch}: a null from the
   * catalog that the File Manager immediately dereferences never becomes a
   * {@code CatalogException} at all.
   */
  @HegelTest(testCases = 12)
  void aLookupMissIsNamedAtTheClient(TestCase tc) {
    String missing = uniqueName(tc.draw(names(), "missing"), 110);

    assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
      List<String> violations = new ArrayList<>();
      check(violations, "getProductById", CatalogException.class,
          () -> client.getProductById(missing));
      check(violations, "getProductByName", CatalogException.class,
          () -> client.getProductByName(missing));
      check(violations, "getProductTypeByName", RepositoryManagerException.class,
          () -> client.getProductTypeByName(missing));
      check(violations, "getProductTypeById", RepositoryManagerException.class,
          () -> client.getProductTypeById(missing));
      check(violations, "getElementByName", ValidationLayerException.class,
          () -> client.getElementByName(missing));
      check(violations, "hasProduct", CatalogException.class,
          () -> client.hasProduct(missing));
      assertTrue(violations.isEmpty(),
          "a lookup miss did not reach the client as the declared exception: "
              + String.join("; ", violations));
    });
  }

  /**
   * Refreshing the server's configuration must succeed and must leave the
   * policy it was serving in place.
   */
  @HegelTest(testCases = 8)
  void refreshingPolicyLeavesThePolicyServed(TestCase tc) throws Exception {
    int refreshes = tc.draw(integers().min(1).max(3), "refreshes");

    for (int i = 0; i < refreshes; i++) {
      assertTrue(client.refreshConfigAndPolicy(), "refreshConfigAndPolicy reported failure");
      ProductType type = client.getProductTypeByName(GENERIC_FILE);
      assertNotNull(type, "the configured product type is gone after a refresh");
      assertEquals(GENERIC_FILE_ID, type.getProductTypeId(),
          "the configured product type changed across a refresh");
    }
  }

  // ------------------------------------------------------------------ helpers

  private static Set<String> idsOf(List<Product> products) {
    Set<String> ids = new HashSet<>();
    if (products != null) {
      for (Product p : products) {
        ids.add(p.getProductId());
      }
    }
    return ids;
  }

  private static Set<String> idsOfTransfers(
      List<org.apache.oodt.cas.filemgr.structs.FileTransferStatus> transfers) {
    Set<String> ids = new HashSet<>();
    if (transfers != null) {
      for (org.apache.oodt.cas.filemgr.structs.FileTransferStatus s : transfers) {
        if (s.getParentProduct() != null) {
          ids.add(s.getParentProduct().getProductId());
        }
      }
    }
    return ids;
  }

  /**
   * Records how one lookup-on-a-missing-thing behaved rather than stopping at
   * the first that misbehaves, so a failure names every operation that is
   * wrong instead of only the first one tried. A {@code null} answer counts as
   * a defined empty answer and is allowed.
   */
  private static void check(List<String> violations, String name,
      Class<? extends Exception> declared, ThrowingCall call) {
    try {
      call.call();
    } catch (Throwable t) {
      if (!declared.isInstance(t)) {
        violations.add(name + " raised " + t.getClass().getName() + " (" + t.getMessage()
            + ") instead of " + declared.getSimpleName());
      }
    }
  }

  /** A client call that may fail. */
  private interface ThrowingCall {
    Object call() throws Exception;
  }
}
