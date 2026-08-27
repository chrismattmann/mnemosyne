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

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import org.apache.oodt.cas.filemgr.structs.Element;
import org.apache.oodt.cas.filemgr.structs.ExtractorSpec;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.metadata.Metadata;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Round-trip properties for {@link XmlStructFactory}, the reader and writer of
 * the File Manager's policy files.
 *
 * <p>{@code elements.xml}, {@code product-types.xml} and
 * {@code product-type-element-map.xml} are what a deployment's configuration
 * actually is. The writing half of this class is what the validation layer
 * calls whenever an element or a mapping changes, and the reading half is what
 * loads the policy back at start-up. A value that does not survive one pass
 * through the pair is a piece of a deployment's configuration that quietly
 * changes meaning the next time the server restarts.
 *
 * <p>{@code TestXmlStructFactory} reads one checked-in policy directory and
 * asserts a fixed handful of values. These properties vary the values and
 * close the loop: write, read, compare.
 *
 * <p>Text is drawn without leading or trailing whitespace. Trimming is what
 * the {@code trim} attribute on a description selects and it defaults to on,
 * so a description that loses its outer whitespace is doing what it says.
 */
class XmlStructFactoryPropertyTest {

  /** Identifiers and names: letters, digits and the punctuation URNs use. */
  private static Generator<String> identifiers() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  /**
   * Free text for descriptions and metadata values. No leading or trailing
   * whitespace, because the reader trims by default and that is documented.
   */
  private static Generator<String> prose() {
    return text()
        .minSize(1)
        .maxSize(12)
        .categories("Lu", "Ll", "Nd")
        .map(String::trim)
        .filter(s -> !s.isEmpty());
  }

  /**
   * Metadata values including the two characters that mean something to a URL
   * decoder. A product type's metadata is free text: a description, a
   * spacecraft name, a percentage, a query fragment.
   */
  private static Generator<String> proseWithPercentAndPlus() {
    return text()
        .minSize(1)
        .maxSize(10)
        .categories("Lu", "Ll", "Nd")
        .includeCharacters("+%")
        .map(String::trim)
        .filter(s -> !s.isEmpty());
  }

  // ---------------------------------------------------------------- elements

  private static Element element(String id, String name, String description, String dcElement) {
    Element e = new Element();
    e.setElementId(id);
    e.setElementName(name);
    e.setDescription(description);
    e.setDCElement(dcElement);
    return e;
  }

  /**
   * A metadata element written into {@code elements.xml} and read back must be
   * the same element.
   *
   * <p>The element id is the key the product-type map refers to and the
   * element name is the metadata key the catalog stores under, so either one
   * changing silently detaches a product type from its own metadata.
   */
  @HegelTest(testCases = 50)
  void anElementSurvivesBeingWrittenAndReadBack(TestCase tc) {
    String id = tc.draw(identifiers(), "id");
    String name = tc.draw(identifiers(), "name");
    String description = tc.draw(prose(), "description");
    String dcElement = tc.draw(prose(), "dcElement");

    Element original = element(id, name, description, dcElement);

    Document doc = XmlStructFactory.getElementXmlDocument(List.of(original));
    assertNotNull(doc, "the writer produced no document");

    NodeList nodes = doc.getDocumentElement().getElementsByTagName("element");
    assertEquals(1, nodes.getLength(), "one element in, one element out");

    Element back = XmlStructFactory.getElement(nodes.item(0));
    assertEquals(id, back.getElementId(), "the element id changed");
    assertEquals(name, back.getElementName(), "the element name changed");
    assertEquals(description, back.getDescription(), "the description changed");
    assertEquals(dcElement, back.getDCElement(), "the Dublin Core element changed");
  }

  /**
   * A whole list of elements must survive, in order and without any of them
   * being merged into another.
   */
  @HegelTest(testCases = 40)
  void aListOfElementsSurvivesInOrder(TestCase tc) {
    List<String> ids = tc.draw(lists(identifiers()).minSize(1).maxSize(5), "ids");
    List<String> names = tc.draw(lists(identifiers()).minSize(1).maxSize(5), "names");

    List<Element> originals = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      originals.add(
          element(
              ids.get(i) + "-" + i,
              names.get(i % names.size()) + "-" + i,
              "description " + i,
              "dc" + i));
    }

    Document doc = XmlStructFactory.getElementXmlDocument(originals);
    assertNotNull(doc);
    NodeList nodes = doc.getDocumentElement().getElementsByTagName("element");
    assertEquals(originals.size(), nodes.getLength(), "the element count changed");

    for (int i = 0; i < originals.size(); i++) {
      Element back = XmlStructFactory.getElement(nodes.item(i));
      assertEquals(originals.get(i).getElementId(), back.getElementId(), "id at position " + i);
      assertEquals(
          originals.get(i).getElementName(), back.getElementName(), "name at position " + i);
      assertEquals(
          originals.get(i).getDescription(), back.getDescription(), "description at position " + i);
    }
  }

  // ------------------------------------------------------------ product types

  private static ProductType productType(
      String id, String name, String description, String repoPath, String versioner,
      Metadata typeMetadata) {
    ProductType type = new ProductType();
    type.setProductTypeId(id);
    type.setName(name);
    type.setDescription(description);
    type.setProductRepositoryPath(repoPath);
    type.setVersioner(versioner);
    type.setTypeMetadata(typeMetadata);
    type.setExtractors(new Vector<ExtractorSpec>());
    return type;
  }

  /**
   * A product type written into {@code product-types.xml} and read back must
   * keep its id, name, description, repository path and versioner.
   *
   * <p>The repository path is where every product of that type is archived and
   * the versioner decides the layout underneath it, so either one changing
   * puts files somewhere nobody is looking for them.
   */
  @HegelTest(testCases = 40)
  void aProductTypeSurvivesBeingWrittenAndReadBack(TestCase tc) {
    String id = tc.draw(identifiers(), "id");
    String name = tc.draw(identifiers(), "name");
    String description = tc.draw(prose(), "description");
    String repoPath = tc.draw(identifiers(), "repoPath");
    String versioner = tc.draw(identifiers(), "versioner");

    ProductType original =
        productType(
            id, name, description, "file:/archive/" + repoPath,
            "org.apache.oodt.cas.filemgr.versioning." + versioner, new Metadata());

    Document doc = XmlStructFactory.getProductTypeXmlDocument(List.of(original));
    assertNotNull(doc, "the writer produced no document");

    NodeList nodes = doc.getDocumentElement().getElementsByTagName("type");
    assertEquals(1, nodes.getLength(), "one type in, one type out");

    ProductType back = XmlStructFactory.getProductType(nodes.item(0));
    assertEquals(original.getProductTypeId(), back.getProductTypeId(), "the type id changed");
    assertEquals(original.getName(), back.getName(), "the type name changed");
    assertEquals(original.getDescription(), back.getDescription(), "the description changed");
    assertEquals(
        original.getProductRepositoryPath(),
        back.getProductRepositoryPath(),
        "the repository path changed");
    assertEquals(original.getVersioner(), back.getVersioner(), "the versioner changed");
  }

  /**
   * A product type's own metadata must survive, keys and values, including
   * keys carrying more than one value.
   */
  @HegelTest(testCases = 40)
  void productTypeMetadataSurvivesBeingWrittenAndReadBack(TestCase tc) {
    Map<String, List<String>> entries = drawTypeMetadata(tc, prose());

    Metadata met = new Metadata();
    entries.forEach(met::addMetadata);

    ProductType original =
        productType(
            "urn:oodt:Generated", "Generated", "a generated type", "file:/archive",
            "org.apache.oodt.cas.filemgr.versioning.BasicVersioner", met);

    Document doc = XmlStructFactory.getProductTypeXmlDocument(List.of(original));
    assertNotNull(doc);
    NodeList nodes = doc.getDocumentElement().getElementsByTagName("type");
    ProductType back = XmlStructFactory.getProductType(nodes.item(0));

    Metadata backMet = back.getTypeMetadata();
    assertNotNull(backMet, "the type came back with no metadata at all");
    for (Map.Entry<String, List<String>> e : entries.entrySet()) {
      assertEquals(
          e.getValue(),
          backMet.getAllMetadata(e.getKey()),
          "values for type metadata key '" + e.getKey() + "' changed");
    }
  }

  /**
   * The same contract for metadata values containing {@code +} or {@code %}.
   *
   * <p>Neither character has any special meaning in XML, and nothing in the
   * policy format says a product type's metadata is URL-encoded: the writer
   * puts the value straight into a text node. A value that comes back
   * different — or does not come back at all — is a value a deployment cannot
   * express in its own configuration file.
   */
  @HegelTest(testCases = 40)
  void productTypeMetadataWithPercentOrPlusSurvives(TestCase tc) {
    Map<String, List<String>> entries = drawTypeMetadata(tc, proseWithPercentAndPlus());

    Metadata met = new Metadata();
    entries.forEach(met::addMetadata);

    ProductType original =
        productType(
            "urn:oodt:Generated", "Generated", "a generated type", "file:/archive",
            "org.apache.oodt.cas.filemgr.versioning.BasicVersioner", met);

    Document doc = XmlStructFactory.getProductTypeXmlDocument(List.of(original));
    assertNotNull(doc);
    NodeList nodes = doc.getDocumentElement().getElementsByTagName("type");
    ProductType back = XmlStructFactory.getProductType(nodes.item(0));

    Metadata backMet = back.getTypeMetadata();
    assertNotNull(backMet, "the type came back with no metadata at all");
    for (Map.Entry<String, List<String>> e : entries.entrySet()) {
      assertEquals(
          e.getValue(),
          backMet.getAllMetadata(e.getKey()),
          "values for type metadata key '" + e.getKey() + "' changed");
    }
  }

  /**
   * Writing a type out and reading it back must reach a fixed point: a second
   * pass through the pair must not change anything a first pass left alone.
   */
  @HegelTest(testCases = 30)
  void theProductTypeRoundTripIsStable(TestCase tc) {
    String id = tc.draw(identifiers(), "id");
    String name = tc.draw(identifiers(), "name");
    String description = tc.draw(prose(), "description");

    ProductType original =
        productType(
            id, name, description, "file:/archive",
            "org.apache.oodt.cas.filemgr.versioning.BasicVersioner", new Metadata());

    ProductType once = readBack(original);
    ProductType twice = readBack(once);

    assertEquals(once.getProductTypeId(), twice.getProductTypeId());
    assertEquals(once.getName(), twice.getName());
    assertEquals(once.getDescription(), twice.getDescription());
    assertEquals(once.getProductRepositoryPath(), twice.getProductRepositoryPath());
    assertEquals(once.getVersioner(), twice.getVersioner());
  }

  // ------------------------------------------------------------------ helpers

  private static ProductType readBack(ProductType type) {
    Document doc = XmlStructFactory.getProductTypeXmlDocument(List.of(type));
    NodeList nodes = doc.getDocumentElement().getElementsByTagName("type");
    return XmlStructFactory.getProductType(nodes.item(0));
  }

  private static Map<String, List<String>> drawTypeMetadata(
      TestCase tc, Generator<String> valueGen) {
    List<String> keys = tc.draw(lists(identifiers()).minSize(1).maxSize(3), "keys");
    Map<String, List<String>> entries = new LinkedHashMap<>();
    for (int i = 0; i < keys.size(); i++) {
      List<String> vals = tc.draw(lists(valueGen).minSize(1).maxSize(3), "values" + i);
      entries.put(keys.get(i) + i, vals);
    }
    return entries;
  }
}
