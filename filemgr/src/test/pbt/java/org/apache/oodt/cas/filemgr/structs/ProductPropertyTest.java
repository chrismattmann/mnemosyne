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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.oodt.commons.xml.XMLUtils;
import org.w3c.dom.Document;

/**
 * Round-trip properties for {@link Product}'s own XML form.
 *
 * <p>{@code toXML} and the {@code InputStream} constructor are a matched pair:
 * the first is what a product looks like on the wire between the file manager
 * and a client that speaks XML, the second is how that client reads it back.
 * Nothing checks the two agree, and a field lost between them is a field the
 * receiving side simply never learns about.
 *
 * <p>Product names are drawn from letters and digits with spaces allowed, since
 * a space in a product name is ordinary and is exactly what the URL encoding in
 * {@code toXML} is there to survive.
 */
class ProductPropertyTest {

  private static Generator<String> words() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  private static Generator<String> names() {
    return text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd").includeCharacters(" ");
  }

  private static Generator<String> structures() {
    return sampledFrom(
        List.of(
            Product.STRUCTURE_FLAT, Product.STRUCTURE_HIERARCHICAL, Product.STRUCTURE_STREAM));
  }

  private static Product readBack(Product product) throws Exception {
    Document doc = product.toXML();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    XMLUtils.writeXmlToStream(doc, out);
    return new Product(new ByteArrayInputStream(out.toByteArray()));
  }

  /**
   * A product's core fields must survive being written to XML and read back.
   *
   * <p>Identity, name, structure and transfer status are the whole of what a
   * client needs to know in order to ask for the product again.
   */
  @HegelTest
  void coreFieldsRoundTripThroughXml(TestCase tc) throws Exception {
    Product original = new Product();
    original.setProductId(tc.draw(words(), "productId"));
    original.setProductName(tc.draw(names(), "productName"));
    original.setProductStructure(tc.draw(structures(), "structure"));
    original.setTransferStatus(
        tc.draw(sampledFrom(List.of(Product.STATUS_TRANSFER, Product.STATUS_RECEIVED)), "status"));
    ProductType type = new ProductType();
    type.setName(tc.draw(words(), "typeName"));
    original.setProductType(type);

    Product back = readBack(original);

    assertEquals(original.getProductId(), back.getProductId());
    assertEquals(original.getProductName(), back.getProductName());
    assertEquals(original.getProductStructure(), back.getProductStructure());
    assertEquals(original.getTransferStatus(), back.getTransferStatus());
    assertNotNull(back.getProductType(), "product type was dropped");
    assertEquals(type.getName(), back.getProductType().getName());
  }

  /**
   * A product's references must survive being written to XML and read back, in
   * order and with their sizes intact.
   *
   * <p>The references are the files themselves. Losing one, or reordering them
   * for a hierarchical product where the first reference is the root of the
   * tree, points the receiving side at the wrong bytes.
   */
  @HegelTest
  void referencesRoundTripThroughXml(TestCase tc) throws Exception {
    Product original = new Product();
    original.setProductId(tc.draw(words(), "productId"));
    original.setProductName(tc.draw(names(), "productName"));
    original.setProductStructure(Product.STRUCTURE_FLAT);
    original.setTransferStatus(Product.STATUS_RECEIVED);
    original.setProductType(new ProductType());

    int numRefs = tc.draw(integers().min(1).max(4), "numRefs");
    List<Reference> refs = new ArrayList<>();
    for (int i = 0; i < numRefs; i++) {
      Reference r = new Reference();
      r.setOrigReference("file:/data/" + tc.draw(words(), "ref." + i + ".orig"));
      r.setDataStoreReference("file:/archive/" + tc.draw(words(), "ref." + i + ".dataStore"));
      r.setFileSize(tc.draw(longs().min(0).max(1_000_000_000L), "ref." + i + ".size"));
      refs.add(r);
    }
    original.setProductReferences(refs);

    Product back = readBack(original);

    assertEquals(numRefs, back.getProductReferences().size(), "references were lost");
    for (int i = 0; i < numRefs; i++) {
      Reference expected = refs.get(i);
      Reference actual = back.getProductReferences().get(i);
      assertEquals(expected.getOrigReference(), actual.getOrigReference(), "ref " + i + " orig");
      assertEquals(
          expected.getDataStoreReference(),
          actual.getDataStoreReference(),
          "ref " + i + " dataStore");
      assertEquals(expected.getFileSize(), actual.getFileSize(), "ref " + i + " size");
    }
  }

  /**
   * Writing a product out twice must produce the same XML.
   *
   * <p>If a single round trip is not a fixed point then a product forwarded
   * through an intermediate service arrives different from the one that was
   * sent, and the difference compounds with each hop.
   */
  @HegelTest
  void theXmlFormIsAFixedPoint(TestCase tc) throws Exception {
    Product original = new Product();
    original.setProductId(tc.draw(words(), "productId"));
    original.setProductName(tc.draw(names(), "productName"));
    original.setProductStructure(Product.STRUCTURE_FLAT);
    original.setTransferStatus(Product.STATUS_RECEIVED);
    original.setProductType(new ProductType());
    List<Reference> refs = new ArrayList<>();
    Reference r = new Reference();
    r.setOrigReference("file:/data/" + tc.draw(words(), "orig"));
    r.setDataStoreReference("file:/archive/" + tc.draw(words(), "dataStore"));
    r.setFileSize(tc.draw(longs().min(0).max(1_000L), "size"));
    refs.add(r);
    original.setProductReferences(refs);

    ByteArrayOutputStream once = new ByteArrayOutputStream();
    XMLUtils.writeXmlToStream(original.toXML(), once);
    ByteArrayOutputStream twice = new ByteArrayOutputStream();
    XMLUtils.writeXmlToStream(readBack(original).toXML(), twice);

    assertEquals(once.toString("UTF-8"), twice.toString("UTF-8"), "the XML changed on the second pass");
  }

  /**
   * A product structure the class does not define must be refused.
   *
   * <p>The three named structures decide how the archive lays a product out.
   * The setter guards against anything else, and it has to: a structure the
   * versioners do not recognise reaches them as an unhandled branch.
   */
  @HegelTest
  void anUnknownStructureIsRefused(TestCase tc) {
    String structure = tc.draw(words(), "structure");
    tc.assume(!structure.equals(Product.STRUCTURE_FLAT));
    tc.assume(!structure.equals(Product.STRUCTURE_HIERARCHICAL));
    tc.assume(!structure.equals(Product.STRUCTURE_STREAM));

    Product product = new Product();

    assertThrows(
        IllegalArgumentException.class,
        () -> product.setProductStructure(structure),
        "'" + structure + "' was accepted as a product structure");
  }

  /**
   * A default flat product must be internally consistent.
   *
   * <p>This factory is what the ingest path builds from a file name alone, so
   * every field it sets is one the caller is entitled not to set itself.
   */
  @HegelTest
  void aDefaultFlatProductIsConsistent(TestCase tc) {
    String name = tc.draw(names(), "name");
    String typeId = tc.draw(words(), "typeId");

    Product product = Product.getDefaultFlatProduct(name, typeId);

    assertEquals(name, product.getProductName());
    assertEquals(Product.STRUCTURE_FLAT, product.getProductStructure());
    assertEquals(Product.STATUS_TRANSFER, product.getTransferStatus());
    assertNotNull(product.getProductReferences(), "references were left null");
    assertEquals(typeId, product.getProductType().getProductTypeId());
  }

  /**
   * A product with no references must still survive the XML round trip.
   *
   * <p>That is the state of a product between being catalogued and being
   * versioned, and it is what a client sees when it asks for a product by id
   * before the transfer has completed.
   */
  @HegelTest
  void aProductWithNoReferencesRoundTrips(TestCase tc) throws Exception {
    Product original = new Product();
    original.setProductId(tc.draw(words(), "productId"));
    original.setProductName(tc.draw(names(), "productName"));
    original.setProductStructure(Product.STRUCTURE_FLAT);
    original.setTransferStatus(Product.STATUS_TRANSFER);
    original.setProductType(new ProductType());
    original.setProductReferences(new ArrayList<>());

    Product back = readBack(original);

    assertEquals(original.getProductName(), back.getProductName());
    assertNotNull(back.getProductReferences(), "references came back null");
    assertEquals(0, back.getProductReferences().size());
  }

  /**
   * Names that need escaping in a URL must survive the XML round trip.
   *
   * <p>{@code toXML} URL-encodes the name and the reader decodes it, so the
   * pair has to agree about characters the encoding treats specially — the plus
   * sign, the percent sign and the space are all legal in a file name.
   */
  @HegelTest(testCases = 2000)
  void namesNeedingUrlEscapingRoundTrip(TestCase tc) throws Exception {
    String name =
        tc.draw(
            lists(sampledFrom(List.of("a", "b", "1", " ", "+", "%", "&", "=", "/")))
                .minSize(1)
                .maxSize(8)
                .map(parts -> String.join("", parts)),
            "productName");

    Product original = new Product();
    original.setProductId(tc.draw(words(), "productId"));
    original.setProductName(name);
    original.setProductStructure(Product.STRUCTURE_FLAT);
    original.setTransferStatus(Product.STATUS_RECEIVED);
    original.setProductType(new ProductType());

    assertEquals(name, readBack(original).getProductName(), "the product name changed");
  }
}
