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

package org.apache.oodt.cas.filemgr.catalog.solr;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.metadata.Metadata;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Encoding properties for {@link DefaultProductSerializer}, which turns a
 * product and its metadata into the XML documents posted to Solr and reads
 * Solr's answers back.
 *
 * <p>Only two things are asked of it here, and both are the minimum a caller
 * needs in order to trust what comes out of the index. The document it produces
 * has to be XML, because Solr rejects anything else outright. And a value has
 * to be escaped exactly once, because Solr stores the text of the field element
 * — that is, the value after the XML parser has undone one layer of escaping.
 * Escaping twice does not fail anywhere; it just puts {@code &amp;} into the
 * index where the user wrote {@code &}, and nothing downstream ever notices.
 *
 * <p>The properties read the generated documents with an ordinary XML parser
 * rather than by string matching, so what they assert is precisely what Solr
 * would end up holding.
 */
class DefaultProductSerializerPropertyTest {

  private static final DefaultProductSerializer SERIALIZER = new DefaultProductSerializer();

  /** Identifiers safe in any XML context, so that id handling is not confounded in. */
  private static Generator<String> plainIds() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  private static Generator<String> keys() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll");
  }

  /**
   * Keys that are plainly ordinary: outside the {@code CAS.} namespace and not
   * caught by the serializer's substring test against
   * {@link Parameters#PRODUCT_STRUCTURE}, so that a property about encoding is
   * not answered by a key having been dropped.
   */
  private static Generator<String> safeKeys() {
    return keys()
        .filter(k -> !k.startsWith(Parameters.NS))
        .filter(k -> !Parameters.PRODUCT_STRUCTURE.contains(k));
  }

  /**
   * Text of the kind that turns up in real product metadata: instrument names
   * with an ampersand in them, comparisons written with angle brackets, quoted
   * titles, and free text carrying an entity that was already spelled out.
   */
  private static Generator<String> awkwardValues() {
    return lists(
            sampledFrom(
                List.of(
                    "Q", "A", "sea", "ice", "&", "<", ">", "\"", "'", " ", "1", "-", "&amp;")))
        .minSize(1)
        .maxSize(8)
        .map(parts -> String.join("", parts));
  }

  private static Element parseDoc(String doc) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder parser = factory.newDocumentBuilder();
    Document parsed = parser.parse(new InputSource(new StringReader(doc)));
    return parsed.getDocumentElement();
  }

  /** The text Solr would store for the named field, or null if the field is absent. */
  private static String fieldValue(Element doc, String name) {
    NodeList fields = doc.getElementsByTagName("field");
    for (int i = 0; i < fields.getLength(); i++) {
      Element field = (Element) fields.item(i);
      if (field.getAttribute("name").equals(name)) {
        return field.getTextContent();
      }
    }
    return null;
  }

  private static Product product(String id, String name) {
    Product product = new Product();
    product.setProductId(id);
    product.setProductName(name);
    product.setProductStructure(Product.STRUCTURE_FLAT);
    product.setTransferStatus(Product.STATUS_RECEIVED);
    ProductType type = new ProductType();
    type.setProductTypeId("type-1");
    type.setName("GenericFile");
    product.setProductType(type);
    return product;
  }

  /**
   * A product's name must reach the index spelled the way the user wrote it.
   *
   * <p>The product name is the field users search on; an ampersand turned into
   * {@code &amp;amp;} on the way in makes the product unfindable by its own
   * name.
   */
  @HegelTest
  void theProductNameIsEscapedExactlyOnce(TestCase tc) throws Exception {
    String id = tc.draw(plainIds(), "productId");
    String name = tc.draw(awkwardValues(), "productName");

    List<String> docs = SERIALIZER.serialize(product(id, name), true);
    assertEquals(1, docs.size(), "a create produced " + docs.size() + " documents");
    tc.note(docs.get(0));

    Element doc = parseDoc(docs.get(0));
    assertEquals(
        name,
        fieldValue(doc, Parameters.PRODUCT_NAME),
        "the product name is not what Solr would store");
  }

  /**
   * The document posted to Solr must be XML.
   *
   * <p>The Solr identifier is written into the document without being escaped,
   * while every other field is escaped. When the id generator in use is
   * {@link NameProductIdGenerator} the id is the product name, which is
   * user-supplied text; a name with an ampersand or an angle bracket in it then
   * produces a document Solr refuses to parse, and the ingest fails at the
   * server with an error about the XML rather than about the name.
   */
  @HegelTest
  void theGeneratedDocumentIsWellFormedXml(TestCase tc) {
    String id = tc.draw(awkwardValues(), "productId");
    String name = tc.draw(plainIds(), "productName");

    List<String> docs = SERIALIZER.serialize(product(id, name), true);
    tc.note(docs.get(0));

    assertDoesNotThrow(
        () -> parseDoc(docs.get(0)), "the document generated for id '" + id + "' is not XML");
  }

  /**
   * A metadata value must reach the index spelled the way the user wrote it.
   *
   * <p>This is the same contract as for the product name, over the path that
   * carries everything else a product knows about itself.
   */
  @HegelTest
  void aMetadataValueIsEscapedExactlyOnce(TestCase tc) throws Exception {
    String id = tc.draw(plainIds(), "productId");
    String key = tc.draw(safeKeys(), "key");
    String value = tc.draw(awkwardValues(), "value");

    Metadata metadata = new Metadata();
    metadata.replaceMetadata(key, value);

    List<String> docs = SERIALIZER.serialize(id, metadata, true);
    assertEquals(1, docs.size(), "expected one update document, got " + docs.size());
    tc.note(docs.get(0));

    Element doc = parseDoc(docs.get(0));
    assertEquals(value, fieldValue(doc, key), "the value is not what Solr would store");
  }

  /**
   * A reference's original path must reach the index spelled the way it was
   * given.
   *
   * <p>Paths with an ampersand in a directory name are unusual but legal, and
   * this is the path a client is handed when it asks where a product came from.
   */
  @HegelTest
  void aReferencePathIsEscapedExactlyOnce(TestCase tc) throws Exception {
    String id = tc.draw(plainIds(), "productId");
    String dir = tc.draw(awkwardValues(), "dir");
    String origPath = "file:/data/" + dir + "/f.dat";

    org.apache.oodt.cas.filemgr.structs.Reference reference =
        new org.apache.oodt.cas.filemgr.structs.Reference();
    reference.setOrigReference(origPath);
    reference.setDataStoreReference("file:/archive/f.dat");
    reference.setFileSize(10L);
    reference.setMimeType("text/plain");

    List<String> docs = SERIALIZER.serialize(id, null, List.of(reference), true);
    assertEquals(1, docs.size(), "expected one update document, got " + docs.size());
    tc.note(docs.get(0));

    Element doc = parseDoc(docs.get(0));
    assertEquals(
        origPath,
        fieldValue(doc, Parameters.REFERENCE_ORIGINAL),
        "the original path is not what Solr would store");
  }

  /**
   * A metadata key outside the reserved namespace must be indexed.
   *
   * <p>The serializer skips keys that belong to the {@code CAS.} namespace,
   * because those are written from the product's own fields. Nothing else may
   * be skipped: a key that is dropped here is a field the user set, that the
   * ingest reported as successful, and that no query will ever match.
   */
  @HegelTest(testCases = 2000)
  void aKeyOutsideTheReservedNamespaceIsIndexed(TestCase tc) throws Exception {
    String id = tc.draw(plainIds(), "productId");
    String key = tc.draw(keys(), "key");
    String value = tc.draw(plainIds(), "value");
    tc.assume(!key.startsWith(Parameters.NS));

    Metadata metadata = new Metadata();
    metadata.replaceMetadata(key, value);

    List<String> docs = SERIALIZER.serialize(id, metadata, true);
    assertEquals(1, docs.size(), "the key '" + key + "' produced no update document");

    Element doc = parseDoc(docs.get(0));
    assertNotNull(fieldValue(doc, key), "the key '" + key + "' was silently dropped");
  }

  /**
   * Every value of a multi-valued key must be indexed, once each.
   *
   * <p>Multi-valued metadata is the normal case for things like a list of
   * ancillary files; dropping one is indistinguishable from it never having
   * been set.
   */
  @HegelTest
  void everyValueOfAMultiValuedKeySurvives(TestCase tc) throws Exception {
    String id = tc.draw(plainIds(), "productId");
    String key = tc.draw(safeKeys(), "key");
    List<String> values = tc.draw(lists(plainIds()).minSize(1).maxSize(4), "values");

    Metadata metadata = new Metadata();
    metadata.replaceMetadata(key, values);

    List<String> docs = SERIALIZER.serialize(id, metadata, true);
    assertEquals(1, docs.size(), "expected one update document, got " + docs.size());

    Element doc = parseDoc(docs.get(0));
    NodeList fields = doc.getElementsByTagName("field");
    int matching = 0;
    for (int i = 0; i < fields.getLength(); i++) {
      if (((Element) fields.item(i)).getAttribute("name").equals(key)) {
        matching++;
      }
    }
    assertEquals(values.size(), matching, "wrong number of values indexed for '" + key + "'");
  }

  /**
   * Reading a Solr response must give back the values Solr holds.
   *
   * <p>The response below is built the way Solr builds one: the stored value
   * escaped once, because that is what putting text into an XML element means.
   * An XML parser undoes that escaping on its own, so anything the deserializer
   * does to the parsed text on top of that changes the value.
   */
  @HegelTest
  void deserializeGivesBackTheStoredValue(TestCase tc) throws Exception {
    String id = tc.draw(plainIds(), "productId");
    String key = tc.draw(safeKeys(), "key");
    String value = tc.draw(awkwardValues(), "value");

    String xml =
        "<response><result numFound=\"1\" start=\"0\"><doc>"
            + "<str name=\"id\">"
            + id
            + "</str>"
            + "<str name=\""
            + Parameters.PRODUCT_ID
            + "\">"
            + id
            + "</str>"
            + "<str name=\""
            + key
            + "\">"
            + StringEscapeUtils.escapeXml(value)
            + "</str>"
            + "</doc></result></response>";
    tc.note(xml);

    QueryResponse response = SERIALIZER.deserialize(xml);

    assertEquals(1, response.getCompleteProducts().size());
    CompleteProduct complete = response.getCompleteProducts().get(0);
    assertEquals(id, complete.getProduct().getProductId());
    assertTrue(
        complete.getMetadata().containsKey(key), "the key '" + key + "' did not come back");
    assertEquals(
        value, complete.getMetadata().getMetadata(key), "the value changed on the way back");
  }
}
