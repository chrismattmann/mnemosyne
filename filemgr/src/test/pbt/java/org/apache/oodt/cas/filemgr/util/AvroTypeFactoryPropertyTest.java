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

package org.apache.oodt.cas.filemgr.util;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.oodt.cas.filemgr.structs.BooleanQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.Element;
import org.apache.oodt.cas.filemgr.structs.ExtractorSpec;
import org.apache.oodt.cas.filemgr.structs.FileTransferStatus;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductPage;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.QueryCriteria;
import org.apache.oodt.cas.filemgr.structs.RangeQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Round-trip properties for {@link AvroTypeFactory}, the translation layer
 * between the File Manager's own structs and the Avro records that carry them
 * over the RPC pipe.
 *
 * <p>Every remote call a client makes passes through this class twice: once on
 * the way out and once on the way back. A field that does not survive the pair
 * of conversions is a field the client never sees, with no error raised
 * anywhere — the catalog simply behaves as though it were never set.
 *
 * <p>The properties below therefore all take the same shape: build a struct,
 * convert it to its Avro form and back, and require the result to carry the
 * same information. Values are drawn from a conservative alphabet so that
 * nothing here depends on encoding subtleties.
 */
class AvroTypeFactoryPropertyTest {

  private static Generator<String> words() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  private static Generator<String> paths() {
    return words().map(w -> "file:/data/" + w);
  }

  /** Mime type names that the Tika repository bundled with OODT resolves. */
  private static Generator<String> mimeTypeNames() {
    return sampledFrom(List.of("text/plain", "application/xml", "image/png", "text/html"));
  }

  private static Generator<String> structures() {
    return sampledFrom(
        List.of(
            Product.STRUCTURE_FLAT, Product.STRUCTURE_HIERARCHICAL, Product.STRUCTURE_STREAM));
  }

  private static Reference reference(TestCase tc, String label) {
    Reference r = new Reference();
    r.setOrigReference(tc.draw(paths(), label + ".orig"));
    r.setDataStoreReference(tc.draw(paths(), label + ".dataStore"));
    r.setFileSize(tc.draw(longs().min(0).max(1_000_000_000L), label + ".fileSize"));
    r.setMimeType(tc.draw(mimeTypeNames(), label + ".mimeType"));
    return r;
  }

  /**
   * A file reference must survive the trip to Avro and back.
   *
   * <p>The data store reference is where the file manager believes the archived
   * copy lives; losing it or its size means a client cannot retrieve the
   * product it was just told about.
   */
  @HegelTest
  void referenceRoundTrips(TestCase tc) {
    Reference original = reference(tc, "ref");

    Reference back = AvroTypeFactory.getReference(AvroTypeFactory.getAvroReference(original));

    assertEquals(original.getOrigReference(), back.getOrigReference());
    assertEquals(original.getDataStoreReference(), back.getDataStoreReference());
    assertEquals(original.getFileSize(), back.getFileSize());
    assertNotNull(back.getMimeType(), "mime type was dropped");
    assertEquals(original.getMimeType().getName(), back.getMimeType().getName());
  }

  /**
   * A metadata element definition must survive the trip to Avro and back.
   *
   * <p>These are what a client gets back from {@code getElementById} and the
   * validation layer; a dropped description or DC mapping is a silently
   * incomplete answer.
   */
  @HegelTest
  void elementRoundTrips(TestCase tc) {
    Element original = new Element();
    original.setElementId(tc.draw(words(), "elementId"));
    original.setElementName(tc.draw(words(), "elementName"));
    original.setDCElement(tc.draw(words(), "dcElement"));
    original.setDescription(tc.draw(words(), "description"));

    Element back = AvroTypeFactory.getElement(AvroTypeFactory.getAvroElement(original));

    assertEquals(original.getElementId(), back.getElementId());
    assertEquals(original.getElementName(), back.getElementName());
    assertEquals(original.getDCElement(), back.getDCElement());
    assertEquals(original.getDescription(), back.getDescription());
  }

  /**
   * A product type must survive the trip to Avro and back.
   *
   * <p>The repository path and versioner class name are what the ingest side
   * uses to decide where a file is archived, so a type that comes back
   * incomplete archives products in the wrong place.
   */
  @HegelTest
  void productTypeRoundTrips(TestCase tc) {
    ProductType original = new ProductType();
    original.setProductTypeId(tc.draw(words(), "typeId"));
    original.setName(tc.draw(words(), "name"));
    original.setDescription(tc.draw(words(), "description"));
    original.setProductRepositoryPath(tc.draw(paths(), "repoPath"));
    original.setVersioner(tc.draw(words(), "versioner"));

    ProductType back = AvroTypeFactory.getProductType(AvroTypeFactory.getAvroProductType(original));

    assertEquals(original.getProductTypeId(), back.getProductTypeId());
    assertEquals(original.getName(), back.getName());
    assertEquals(original.getDescription(), back.getDescription());
    assertEquals(original.getProductRepositoryPath(), back.getProductRepositoryPath());
    assertEquals(original.getVersioner(), back.getVersioner());
  }

  /**
   * A fully populated product must survive the trip to Avro and back.
   *
   * <p>This is the common case: a product that has been catalogued, so it has
   * an id, a type, and at least one reference.
   */
  @HegelTest
  void productWithATypeRoundTrips(TestCase tc) {
    Product original = new Product();
    original.setProductId(tc.draw(words(), "productId"));
    original.setProductName(tc.draw(words(), "productName"));
    original.setProductStructure(tc.draw(structures(), "structure"));
    original.setTransferStatus(
        tc.draw(sampledFrom(List.of(Product.STATUS_TRANSFER, Product.STATUS_RECEIVED)), "status"));
    ProductType type = new ProductType();
    type.setProductTypeId(tc.draw(words(), "typeId"));
    type.setName(tc.draw(words(), "typeName"));
    original.setProductType(type);
    List<Reference> refs = new ArrayList<>();
    refs.add(reference(tc, "ref0"));
    original.setProductReferences(refs);

    Product back = AvroTypeFactory.getProduct(AvroTypeFactory.getAvroProduct(original));

    assertEquals(original.getProductId(), back.getProductId());
    assertEquals(original.getProductName(), back.getProductName());
    assertEquals(original.getProductStructure(), back.getProductStructure());
    assertEquals(original.getTransferStatus(), back.getTransferStatus());
    assertEquals(1, back.getProductReferences().size());
    assertEquals(
        refs.get(0).getDataStoreReference(),
        back.getProductReferences().get(0).getDataStoreReference());
    assertNotNull(back.getProductType(), "product type was dropped");
    assertEquals(type.getName(), back.getProductType().getName());
  }

  /**
   * A product's structure must survive the trip to Avro and back whether or not
   * a product type has been attached to it.
   *
   * <p>A product with no type is not a hypothetical: it is precisely the state
   * of a product between {@code Product.getDefaultFlatProduct} being asked for
   * a type it does not know and the catalog filling one in, and it is what a
   * client sends when it wants the server to resolve the type by name. The
   * structure decides whether the product is archived as one file or as a
   * directory tree, so losing it changes what the file manager does with the
   * bytes.
   */
  @HegelTest
  void productStructureSurvivesWithoutAProductType(TestCase tc) {
    Product original = new Product();
    original.setProductName(tc.draw(words(), "productName"));
    original.setProductStructure(tc.draw(structures(), "structure"));
    original.setProductType(null);

    Product back = AvroTypeFactory.getProduct(AvroTypeFactory.getAvroProduct(original));

    assertEquals(
        original.getProductStructure(),
        back.getProductStructure(),
        "structure was dropped because the product had no type");
  }

  /** A single equality term must survive the trip to Avro and back. */
  @HegelTest
  void termQueryCriteriaRoundTrips(TestCase tc) {
    TermQueryCriteria original =
        new TermQueryCriteria(tc.draw(words(), "name"), tc.draw(words(), "value"));

    QueryCriteria back =
        AvroTypeFactory.getQueryCriteria(AvroTypeFactory.getAvroQueryCriteria(original));

    assertTrue(back instanceof TermQueryCriteria, "came back as " + back.getClass());
    assertEquals(original.getElementName(), back.getElementName());
    assertEquals(original.getValue(), ((TermQueryCriteria) back).getValue());
  }

  /**
   * A range must survive the trip to Avro and back, including its inclusivity.
   *
   * <p>Flipping inclusive to exclusive quietly drops the products sitting
   * exactly on a bound, which is the boundary a caller most often cares about.
   */
  @HegelTest
  void rangeQueryCriteriaRoundTrips(TestCase tc) {
    RangeQueryCriteria original =
        new RangeQueryCriteria(
            tc.draw(words(), "name"),
            tc.draw(words(), "start"),
            tc.draw(words(), "end"),
            tc.draw(booleans(), "inclusive"));

    QueryCriteria back =
        AvroTypeFactory.getQueryCriteria(AvroTypeFactory.getAvroQueryCriteria(original));

    assertTrue(back instanceof RangeQueryCriteria, "came back as " + back.getClass());
    RangeQueryCriteria range = (RangeQueryCriteria) back;
    assertEquals(original.getElementName(), range.getElementName());
    assertEquals(original.getStartValue(), range.getStartValue());
    assertEquals(original.getEndValue(), range.getEndValue());
    assertEquals(original.getInclusive(), range.getInclusive(), "inclusivity flipped");
  }

  /**
   * A whole query must survive the trip to Avro and back with its operator and
   * its operands in order.
   *
   * <p>Reordering AND operands is harmless; reordering OR against NOT is not,
   * and neither is silently changing the operator, so both are pinned here.
   */
  @HegelTest
  void booleanQueryRoundTripsWithOperandOrder(TestCase tc) throws Exception {
    int op =
        tc.draw(
            sampledFrom(List.of(BooleanQueryCriteria.AND, BooleanQueryCriteria.OR)), "operator");
    List<String> names = tc.draw(lists(words()).minSize(2).maxSize(4), "names");
    List<QueryCriteria> terms = new ArrayList<>();
    for (String name : names) {
      terms.add(new TermQueryCriteria(name, tc.draw(words(), "value." + terms.size())));
    }
    Query original = new Query(new ArrayList<>(List.of(new BooleanQueryCriteria(terms, op))));

    Query back = AvroTypeFactory.getQuery(AvroTypeFactory.getAvroQuery(original));

    assertEquals(1, back.getCriteria().size());
    QueryCriteria criterion = back.getCriteria().get(0);
    assertTrue(criterion instanceof BooleanQueryCriteria, "came back as " + criterion.getClass());
    BooleanQueryCriteria bqc = (BooleanQueryCriteria) criterion;
    assertEquals(op, bqc.getOperator(), "operator changed");
    assertEquals(names.size(), bqc.getTerms().size(), "operands were dropped");
    for (int i = 0; i < names.size(); i++) {
      assertEquals(
          names.get(i), bqc.getTerms().get(i).getElementName(), "operand " + i + " moved");
    }
  }

  /**
   * A page of results must survive the trip to Avro and back.
   *
   * <p>The page counters are what a paging client loops on. If the total page
   * count or the page number changes in transit the client either stops early
   * or never stops.
   */
  @HegelTest
  void productPageRoundTrips(TestCase tc) {
    int totalPages = tc.draw(integers().min(1).max(50), "totalPages");
    int pageNum = tc.draw(integers().min(1).max(totalPages), "pageNum");
    int pageSize = tc.draw(integers().min(1).max(20), "pageSize");
    long numOfHits = tc.draw(longs().min(0).max(100_000L), "numOfHits");
    int numProducts = tc.draw(integers().min(0).max(3), "numProducts");

    List<Product> products = new ArrayList<>();
    for (int i = 0; i < numProducts; i++) {
      Product p = new Product();
      p.setProductId(tc.draw(words(), "product." + i + ".id"));
      p.setProductName(tc.draw(words(), "product." + i + ".name"));
      p.setProductStructure(Product.STRUCTURE_FLAT);
      p.setProductType(ProductType.blankProductType());
      products.add(p);
    }
    ProductPage original = new ProductPage(pageNum, totalPages, pageSize, products);
    original.setNumOfHits(numOfHits);

    ProductPage back = AvroTypeFactory.getProductPage(AvroTypeFactory.getAvroProductPage(original));

    assertEquals(pageNum, back.getPageNum());
    assertEquals(totalPages, back.getTotalPages());
    assertEquals(pageSize, back.getPageSize());
    assertEquals(numOfHits, back.getNumOfHits());
    assertEquals(numProducts, back.getPageProducts().size());
    assertEquals(original.isLastPage(), back.isLastPage());
    assertEquals(original.isFirstPage(), back.isFirstPage());
  }

  /**
   * Metadata must survive the trip to Avro and back, keys, values and
   * multiplicity alike.
   *
   * <p>This is the payload of every catalog read; a dropped value for a
   * multi-valued key is indistinguishable from the product not having it.
   */
  @HegelTest
  void metadataRoundTrips(TestCase tc) {
    List<String> keys = tc.draw(lists(words()).minSize(1).maxSize(4), "keys");
    Metadata original = new Metadata();
    for (String key : keys) {
      List<String> values = tc.draw(lists(words()).minSize(1).maxSize(3), "values." + key);
      original.replaceMetadata(key, values);
    }

    Metadata back = AvroTypeFactory.getMetadata(AvroTypeFactory.getAvroMetadata(original));

    for (String key : original.getAllKeys()) {
      assertEquals(
          original.getAllMetadata(key), back.getAllMetadata(key), "values changed for key " + key);
    }
    assertEquals(original.getAllKeys().size(), back.getAllKeys().size(), "key set changed");
  }

  /**
   * An extractor specification must survive the trip to Avro and back.
   *
   * <p>The configuration properties are the extractor's whole behaviour; a
   * dropped property means an extractor that silently runs with defaults.
   */
  @HegelTest
  void extractorSpecRoundTrips(TestCase tc) {
    String className = tc.draw(words(), "className");
    List<String> propNames = tc.draw(lists(words()).minSize(1).maxSize(4), "propNames");
    Properties config = new Properties();
    for (String propName : propNames) {
      config.setProperty(propName, tc.draw(words(), "propValue." + propName));
    }
    ExtractorSpec original = new ExtractorSpec(className, config);

    ExtractorSpec back =
        AvroTypeFactory.getExtractorSpec(AvroTypeFactory.getAvroExtractorSpec(original));

    assertEquals(className, back.getClassName());
    assertEquals(config, back.getConfiguration(), "configuration changed");
  }

  /**
   * A file transfer status must survive the trip to Avro and back.
   *
   * <p>A client polling a transfer divides the byte count it is handed by the
   * reference's file size; both halves have to arrive intact for the progress
   * it reports to mean anything.
   */
  @HegelTest
  void fileTransferStatusRoundTrips(TestCase tc) {
    Reference fileRef = reference(tc, "fileRef");
    long bytesTransferred = tc.draw(longs().min(0).max(fileRef.getFileSize()), "bytesTransferred");
    Product parent = new Product();
    parent.setProductId(tc.draw(words(), "parentId"));
    parent.setProductName(tc.draw(words(), "parentName"));
    parent.setProductStructure(Product.STRUCTURE_FLAT);
    parent.setProductType(ProductType.blankProductType());
    FileTransferStatus original =
        new FileTransferStatus(fileRef, fileRef.getFileSize(), bytesTransferred, parent);

    FileTransferStatus back =
        AvroTypeFactory.getFileTransferStatus(
            AvroTypeFactory.getAvroFileTransferStatus(original));

    assertEquals(bytesTransferred, back.getBytesTransferred());
    assertEquals(fileRef.getFileSize(), back.getFileRef().getFileSize(), "file size was dropped");
    assertEquals(parent.getProductId(), back.getParentProduct().getProductId());
  }
}
