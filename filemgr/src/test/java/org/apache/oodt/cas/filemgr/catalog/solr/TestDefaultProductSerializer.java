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

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.metadata.Metadata;

import org.junit.Before;
import org.junit.Test;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Escaping tests for {@link DefaultProductSerializer}.
 *
 * The serializer hand-builds Solr update documents as XML text, so every value it writes has to be
 * escaped exactly once: not zero times (the document stops being well-formed) and not twice (the
 * indexed value quietly gains an <code>&amp;amp;</code> that compounds on every later update).
 */
public class TestDefaultProductSerializer {

  private DefaultProductSerializer serializer;

  @Before
  public void setUp() {
    serializer = new DefaultProductSerializer();
  }

  /**
   * With {@code NameProductIdGenerator} the Solr id is the user's product name, so it is as likely
   * to contain a bare ampersand as any other field.
   */
  @Test
  public void theGeneratedDocumentIsWellFormedXml() throws Exception {
    Product product = product("A&B");
    for (String doc : serializer.serialize(product, true)) {
      parse(doc);
    }
    for (String doc : serializer.serialize(product, false)) {
      parse(doc);
    }
  }

  @Test
  public void aMetadataKeyIsWellFormedInTheNameAttribute() throws Exception {
    Metadata metadata = new Metadata();
    metadata.addMetadata("a\"b&c", "value");
    for (String doc : serializer.serialize("id", metadata, true)) {
      parse(doc);
    }
  }

  @Test
  public void aMetadataValueIsEscapedExactlyOnce() throws Exception {
    Metadata metadata = new Metadata();
    metadata.addMetadata("Author", "Simon & Schuster");

    for (boolean replace : new boolean[] {true, false}) {
      String doc = single(serializer.serialize("id", metadata, replace));
      assertTrue("value should be escaped: " + doc, doc.contains("Simon &amp; Schuster"));
      assertTrue("value should not be double escaped: " + doc, !doc.contains("&amp;amp;"));
      assertEquals("Simon & Schuster", firstFieldText(doc, "Author"));
    }
  }

  /**
   * A double-escaped path no longer matches the file on disk, which is the failure that makes this
   * worse than a cosmetic bug.
   */
  @Test
  public void aReferencePathIsEscapedExactlyOnce() throws Exception {
    Reference reference = new Reference("file:/data/&/f.dat", "file:/archive/&/f.dat", 42L);
    reference.setMimeType("text/plain");

    String doc = single(serializer.serialize("id", null, Collections.singletonList(reference), true));
    assertTrue("path should not be double escaped: " + doc, !doc.contains("&amp;amp;"));
    assertEquals("file:/data/&/f.dat", firstFieldText(doc, Parameters.REFERENCE_ORIGINAL));
    assertEquals("file:/archive/&/f.dat", firstFieldText(doc, Parameters.REFERENCE_DATASTORE));
  }

  @Test
  public void deserializeGivesBackTheStoredValue() throws Exception {
    String stored = "a & b < c > d \" e ' f";
    Metadata metadata = new Metadata();
    metadata.addMetadata("Description", stored);

    QueryResponse response = serializer.deserialize(asResponse(single(serializer.serialize("id", metadata, true))));

    assertEquals(1, response.getCompleteProducts().size());
    assertEquals(stored, response.getCompleteProducts().get(0).getMetadata().getMetadata("Description"));
  }

  /**
   * The double escape on write used to be cancelled out by a double unescape on read, which hid
   * both. It stops cancelling as soon as the document was written by anything other than this
   * class -- Solr itself, another client, a reindex -- so read what a correct document stores.
   * Here the stored text really is the six characters "&amp;", which is why it appears in the XML
   * with its own ampersand escaped.
   */
  @Test
  public void readingDoesNotUnescapeWhatTheParserAlreadyUnescaped() throws Exception {
    String xml = "<response><result numFound=\"1\" start=\"0\"><doc>"
        + "<str name=\"Single\">a &amp;amp; b</str>"
        + "<arr name=\"Multi\"><str>x &amp;lt; y</str></arr>"
        + "</doc></result></response>";

    Metadata metadata = serializer.deserialize(xml).getCompleteProducts().get(0).getMetadata();

    assertEquals("a &amp; b", metadata.getMetadata("Single"));
    assertEquals("x &lt; y", metadata.getMetadata("Multi"));
  }

  @Test
  public void aProductRoundTripsThroughSolr() throws Exception {
    Product product = product("A&B");

    QueryResponse response = serializer.deserialize(asResponse(single(serializer.serialize(product, true))));

    Product returned = response.getCompleteProducts().get(0).getProduct();
    assertEquals("A&B", returned.getProductId());
    assertEquals("A&B", returned.getProductName());
    assertEquals(Product.STRUCTURE_FLAT, returned.getProductStructure());
  }

  /**
   * The reserved-namespace test used to read {@code Parameters.PRODUCT_STRUCTURE.contains(key)},
   * which asks whether the constant contains the key. Every one of these keys is a substring of
   * "CAS.ProductStructure", so every one of them was silently dropped.
   */
  @Test
  public void aKeyOutsideTheReservedNamespaceIsIndexed() throws Exception {
    for (String key : new String[] {"A", "S", "Product", "Structure", "ProductStructure", "CAS"}) {
      Metadata metadata = new Metadata();
      metadata.addMetadata(key, "kept");

      List<String> docs = serializer.serialize("id", metadata, true);
      assertTrue("key '" + key + "' was dropped", !docs.isEmpty());
      assertEquals("key '" + key + "' was dropped", "kept", firstFieldText(single(docs), key));
    }
  }

  @Test
  public void aKeyInsideTheReservedNamespaceIsStillSkipped() {
    Metadata metadata = new Metadata();
    metadata.addMetadata(Parameters.PRODUCT_STRUCTURE, Product.STRUCTURE_FLAT);

    assertTrue(serializer.serialize("id", metadata, true).isEmpty());
  }

  private Product product(String name) {
    Product product = new Product();
    product.setProductId(name);
    product.setProductName(name);
    product.setProductStructure(Product.STRUCTURE_FLAT);
    product.setTransferStatus(Product.STATUS_RECEIVED);
    ProductType type = new ProductType();
    type.setName("GenericFile");
    type.setProductTypeId("urn:oodt:GenericFile");
    product.setProductType(type);
    product.setProductReferences(new ArrayList<Reference>());
    return product;
  }

  private String single(List<String> docs) {
    assertEquals("expected exactly one document, got " + docs, 1, docs.size());
    return docs.get(0);
  }

  /** Wraps a {@code <doc>} in the envelope {@link DefaultProductSerializer#deserialize} expects. */
  private String asResponse(String doc) {
    return "<response><result numFound=\"1\" start=\"0\">" + doc + "</result></response>";
  }

  /**
   * Reads a field back with a real XML parser, so the value compared is the one Solr would store
   * rather than the escaped text we happened to write.
   */
  private String firstFieldText(String doc, String name) throws Exception {
    org.w3c.dom.NodeList fields = parse(doc).getDocumentElement().getElementsByTagName("field");
    for (int i = 0; i < fields.getLength(); i++) {
      org.w3c.dom.Element field = (org.w3c.dom.Element) fields.item(i);
      if (field.getAttribute("name").equals(name)) {
        return field.getTextContent();
      }
    }
    return null;
  }

  private org.w3c.dom.Document parse(String xml) throws Exception {
    DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    return builder.parse(new InputSource(new StringReader(xml)));
  }
}
