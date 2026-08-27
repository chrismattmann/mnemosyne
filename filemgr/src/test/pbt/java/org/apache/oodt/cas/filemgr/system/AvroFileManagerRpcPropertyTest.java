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
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.oodt.cas.filemgr.ingest.StdIngester;
import org.apache.oodt.cas.filemgr.metadata.CoreMetKeys;
import org.apache.oodt.cas.filemgr.structs.Element;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.metadata.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Properties for {@link FileManager} reached through {@link AvroFileManagerClient}
 * over a real Avro RPC connection.
 *
 * <p>Everything a File Manager deployment does goes over this wire. The
 * existing suite ingests one fixed file and checks a handful of fields on it;
 * these properties vary the product names, the metadata and the number of
 * products, and check what a client is entitled to assume: that a product
 * ingested is a product that comes back, that its metadata and its data-store
 * references survive the round trip intact, and that the counts the server
 * reports move by exactly as much as the client changed.
 *
 * <p>The server is started fresh for each property on an ephemeral port taken
 * from the operating system — never a fixed one, because a fixed port collides
 * with whatever else happens to be listening. Its catalog, its policy and its
 * archive all live under a temporary directory that is deleted afterwards, so
 * one property cannot see another's products.
 *
 * <p>Within a property the server is shared across cases, so the properties are
 * stated as differences — how much a count moved, which ids appeared — rather
 * than as absolutes. A property that asserted an absolute count would be
 * asserting something about the cases that ran before it.
 */
class AvroFileManagerRpcPropertyTest {

  private static final String LOCAL_TRANSFER_FACTORY =
      "org.apache.oodt.cas.filemgr.datatransfer.LocalDataTransferFactory";

  private static final String GENERIC_FILE = "GenericFile";
  private static final String GENERIC_FILE_ID = "urn:oodt:GenericFile";

  /** The multi-valued element this fixture declares for {@code GenericFile}. */
  private static final String TEST_ELEMENT = "TestElement";

  private Properties savedProperties;
  private Path root;
  private Path policyDir;
  private Path archiveDir;
  private Path stagingDir;
  private Path catalogDir;
  private int port;
  private AvroFileManagerServer server;

  /**
   * Names that are safe as a file name and as a path component, because the
   * ingest path turns a product name into both. Anything wider than this is a
   * property about the versioner and the file system, not about the wire.
   */
  private static Generator<String> names() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  /** Metadata values: free text, but nothing that a path would object to. */
  private static Generator<String> values() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd", "Zs").map(String::trim)
        .filter(s -> !s.isEmpty());
  }

  // ---------------------------------------------------------------- fixtures

  @BeforeEach
  void startServer() throws Exception {
    savedProperties = (Properties) System.getProperties().clone();

    root = Files.createTempDirectory("filemgr-rpc-pbt");
    policyDir = Files.createDirectories(root.resolve("policy"));
    archiveDir = Files.createDirectories(root.resolve("archive"));
    stagingDir = Files.createDirectories(root.resolve("staging"));
    /* must not exist: LuceneCatalogFactory only creates the index if it is absent */
    catalogDir = root.resolve("cat");

    copyPolicy();

    Properties properties = new Properties(System.getProperties());
    properties.setProperty("filemgr.server",
        "org.apache.oodt.cas.filemgr.system.rpc.AvroFileManagerServerFactory");
    properties.setProperty("filemgr.client",
        "org.apache.oodt.cas.filemgr.system.rpc.AvroFileManagerClientFactory");
    properties.setProperty("filemgr.catalog.factory",
        "org.apache.oodt.cas.filemgr.catalog.LuceneCatalogFactory");
    properties.setProperty("filemgr.repository.factory",
        "org.apache.oodt.cas.filemgr.repository.XMLRepositoryManagerFactory");
    properties.setProperty("filemgr.validationLayer.factory",
        "org.apache.oodt.cas.filemgr.validation.XMLValidationLayerFactory");
    properties.setProperty("filemgr.datatransfer.factory", LOCAL_TRANSFER_FACTORY);
    properties.setProperty("org.apache.oodt.cas.filemgr.catalog.lucene.idxPath",
        catalogDir.toString());
    properties.setProperty("org.apache.oodt.cas.filemgr.catalog.lucene.pageSize", "20");
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
  }

  @AfterEach
  void stopServer() throws Exception {
    try {
      if (server != null) {
        server.shutdown();
      }
    } finally {
      server = null;
      System.setProperties(savedProperties);
      deleteRecursively(root);
    }
  }

  /**
   * Asks the operating system for a port nobody is using and hands it back.
   *
   * <p>A hard-coded port is how a suite ends up failing on a machine that
   * happens to run something else — Docker publishes ports in the 50000s, and
   * that has already taken a run of this module down once.
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
   * for {@code GenericFile} so that a product type with a genuinely
   * multi-valued element is available.
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
    try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
      walk.forEach(paths::add);
    }
    paths.sort(Comparator.reverseOrder());
    for (Path p : paths) {
      Files.deleteIfExists(p);
    }
  }

  private FileManagerClient client() throws Exception {
    return new AvroFileManagerClient(new URL("http://localhost:" + port));
  }

  private ProductType genericFileType() throws Exception {
    ProductType type = new ProductType();
    type.setProductTypeId(GENERIC_FILE_ID);
    type.setName(GENERIC_FILE);
    return type;
  }

  /**
   * Stages a file and ingests it, which is the path a producer actually takes.
   *
   * @return the product id the File Manager assigned.
   */
  private String ingest(String fileName, Metadata met) throws Exception {
    Path file = stagingDir.resolve(fileName);
    Files.writeString(file, "contents of " + fileName, StandardCharsets.UTF_8);

    met.replaceMetadata(CoreMetKeys.FILE_LOCATION, stagingDir.toString());
    met.replaceMetadata(CoreMetKeys.FILENAME, fileName);
    met.replaceMetadata(CoreMetKeys.PRODUCT_TYPE, GENERIC_FILE);

    StdIngester ingester = new StdIngester(LOCAL_TRANSFER_FACTORY);
    return ingester.ingest(new URL("http://localhost:" + port), file.toFile(), met);
  }

  /** A file name that no other case in this property will have used. */
  private static String uniqueName(String base, int salt) {
    return base + "-" + salt + "-" + System.nanoTime() + ".txt";
  }

  // -------------------------------------------------------------- properties

  /**
   * A product ingested over RPC must come back over RPC: same name, same
   * product type, and marked as received.
   */
  @HegelTest(testCases = 20)
  void anIngestedProductComesBackById(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    String productName = tc.draw(names(), "productName");
    String fileName = uniqueName(base, 0);

    Metadata met = new Metadata();
    met.replaceMetadata(CoreMetKeys.PRODUCT_NAME, productName);
    String productId = ingest(fileName, met);
    assertNotNull(productId, "ingest returned no product id");

    try (FileManagerClient client = client()) {
      Product back = client.getProductById(productId);
      assertNotNull(back, "product " + productId + " was not found over RPC");
      assertEquals(productId, back.getProductId(), "the product id changed on the wire");
      assertEquals(productName, back.getProductName(), "the product name changed on the wire");
      assertNotNull(back.getProductType(), "the product came back with no product type");
      assertEquals(
          GENERIC_FILE_ID,
          back.getProductType().getProductTypeId(),
          "the product type id changed on the wire");
      assertEquals(
          Product.STATUS_RECEIVED,
          back.getTransferStatus(),
          "an ingested product is not marked as received");
    }
  }

  /**
   * The metadata a producer supplies must be readable back over RPC, including
   * an element carrying several values.
   *
   * <p>A multi-valued element that arrives as a single value, or in a different
   * order, is a producer's data quietly rewritten.
   */
  @HegelTest(testCases = 20)
  void ingestedMetadataSurvivesTheWire(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    List<String> testValues = tc.draw(lists(values()).minSize(1).maxSize(4), "testValues");
    String fileName = uniqueName(base, 1);

    Metadata met = new Metadata();
    met.replaceMetadata(CoreMetKeys.PRODUCT_NAME, base);
    met.replaceMetadata(TEST_ELEMENT, testValues);
    String productId = ingest(fileName, met);

    try (FileManagerClient client = client()) {
      Product back = client.getProductById(productId);
      Metadata backMet = client.getMetadata(back);
      assertNotNull(backMet, "no metadata came back for " + productId);

      assertEquals(
          fileName,
          backMet.getMetadata(CoreMetKeys.FILENAME),
          "the filename changed on the wire");
      assertEquals(
          testValues,
          backMet.getAllMetadata(TEST_ELEMENT),
          "the multi-valued element changed on the wire");

      Metadata reduced = client.getReducedMetadata(back, List.of(TEST_ELEMENT));
      assertEquals(
          testValues,
          reduced.getAllMetadata(TEST_ELEMENT),
          "getReducedMetadata disagrees with getMetadata");
    }
  }

  /**
   * A product's references must survive the wire, and the data store reference
   * must name a file that is actually there.
   *
   * <p>The data store reference is the only record of where the archived file
   * went. One that does not come back, or comes back pointing at nothing, is an
   * archived file nobody can find again.
   */
  @HegelTest(testCases = 20)
  void referencesSurviveTheWireAndPointAtRealFiles(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    String fileName = uniqueName(base, 2);

    Metadata met = new Metadata();
    met.replaceMetadata(CoreMetKeys.PRODUCT_NAME, base);
    String productId = ingest(fileName, met);

    try (FileManagerClient client = client()) {
      Product back = client.getProductById(productId);
      List<Reference> refs = client.getProductReferences(back);
      assertNotNull(refs, "no references came back");
      assertEquals(1, refs.size(), "one file was ingested, so there should be one reference");

      Reference ref = refs.get(0);
      assertNotNull(ref.getDataStoreReference(), "the data store reference is null");
      assertTrue(
          ref.getDataStoreReference().startsWith("file:"),
          "the data store reference is not a file URI: " + ref.getDataStoreReference());
      Path archived = Path.of(URI.create(ref.getDataStoreReference()));
      assertTrue(
          Files.exists(archived),
          "the data store reference points at nothing: " + ref.getDataStoreReference());
      assertEquals(
          Files.size(archived), ref.getFileSize(), "the recorded file size is not the file's size");
    }
  }

  /**
   * Ingesting {@code n} products must raise the reported product count by
   * exactly {@code n}, and every one of them must show up under the product
   * type.
   */
  @HegelTest(testCases = 15)
  void ingestingNproductsRaisesTheCountByN(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(1).max(3), "count");

    try (FileManagerClient client = client()) {
      ProductType type = genericFileType();
      int before = client.getNumProducts(type);
      Set<String> idsBefore = idsOf(client.getProductsByProductType(type));

      Set<String> ingested = new HashSet<>();
      for (int i = 0; i < count; i++) {
        Metadata met = new Metadata();
        met.replaceMetadata(CoreMetKeys.PRODUCT_NAME, base + i);
        ingested.add(ingest(uniqueName(base, 3 + i), met));
      }

      assertEquals(
          before + count,
          client.getNumProducts(type),
          "getNumProducts did not move by the number ingested");

      Set<String> idsAfter = idsOf(client.getProductsByProductType(type));
      idsAfter.removeAll(idsBefore);
      assertEquals(
          ingested, idsAfter, "the products listed under the type are not the ones ingested");
    }
  }

  /**
   * Removing a product over RPC must remove that product and no other.
   */
  @HegelTest(testCases = 15)
  void removingOneProductOverRpcLeavesTheRest(TestCase tc) throws Exception {
    String base = tc.draw(names(), "base");
    int count = tc.draw(integers().min(2).max(3), "count");
    int victim = tc.draw(integers().min(0).max(count - 1), "victim");

    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Metadata met = new Metadata();
      met.replaceMetadata(CoreMetKeys.PRODUCT_NAME, base + i);
      ids.add(ingest(uniqueName(base, 10 + i), met));
    }

    try (FileManagerClient client = client()) {
      ProductType type = genericFileType();
      int before = client.getNumProducts(type);

      Product doomed = client.getProductById(ids.get(victim));
      assertTrue(client.removeProduct(doomed), "removeProduct reported failure");

      assertEquals(
          before - 1,
          client.getNumProducts(type),
          "removing one product changed the count by something other than one");

      Set<String> remaining = idsOf(client.getProductsByProductType(type));
      assertTrue(
          !remaining.contains(ids.get(victim)),
          "the removed product is still listed under its type");
      for (int i = 0; i < count; i++) {
        if (i == victim) {
          continue;
        }
        assertTrue(
            remaining.contains(ids.get(i)),
            "removing product " + victim + " also removed product " + i);
      }
    }
  }

  /**
   * The policy the server was configured with must be what it serves: the
   * product type by name and by id, and the element list for that type.
   */
  @HegelTest(testCases = 10)
  void thePolicyIsServedAsItWasConfigured(TestCase tc) throws Exception {
    /* nothing to vary in the policy itself; the draw keeps the case distinct */
    int unused = tc.draw(integers().min(0).max(3), "unused");
    tc.note("case " + unused);

    try (FileManagerClient client = client()) {
      ProductType byName = client.getProductTypeByName(GENERIC_FILE);
      assertNotNull(byName, "the configured product type is not served by name");
      assertEquals(GENERIC_FILE_ID, byName.getProductTypeId());

      ProductType byId = client.getProductTypeById(GENERIC_FILE_ID);
      assertNotNull(byId, "the configured product type is not served by id");
      assertEquals(GENERIC_FILE, byId.getName());

      List<ProductType> all = client.getProductTypes();
      assertTrue(
          idsOfTypes(all).contains(GENERIC_FILE_ID),
          "the type list does not include the configured type: " + idsOfTypes(all));

      List<Element> elements = client.getElementsByProductType(byName);
      Set<String> elementNames = new HashSet<>();
      for (Element e : elements) {
        elementNames.add(e.getElementName());
      }
      assertTrue(
          elementNames.contains(TEST_ELEMENT),
          "the element declared for this type is not served: " + elementNames);

      Element byElementName = client.getElementByName(TEST_ELEMENT);
      assertNotNull(byElementName, "lookup by element name found nothing");
      assertEquals(TEST_ELEMENT, byElementName.getElementName());
      assertEquals(
          byElementName.getElementId(),
          client.getElementById(byElementName.getElementId()).getElementId(),
          "lookup by element id disagrees with lookup by name");
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

  private static Set<String> idsOfTypes(List<ProductType> types) {
    Set<String> ids = new HashSet<>();
    if (types != null) {
      for (ProductType t : types) {
        ids.add(t.getProductTypeId());
      }
    }
    return ids;
  }
}
