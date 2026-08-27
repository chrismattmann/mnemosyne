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

package org.apache.oodt.cas.filemgr.validation;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.oodt.cas.filemgr.structs.Element;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.util.XmlStructFactory;

/**
 * Properties for {@link XMLValidationLayer}, the policy-file validation layer
 * every default File Manager deployment runs with.
 *
 * <p>The validation layer is what decides which metadata keys a product type is
 * allowed to carry. {@code LuceneCatalog} asks it for the element list on every
 * write and on every read, so an element the layer does not report for a type
 * is metadata that is never stored and never returned. Getting the answer wrong
 * for one product type is therefore a silent, permanent data loss for that
 * type.
 *
 * <p>{@code TestXMLValidationLayer} reads one checked-in policy directory and
 * asserts against its fixed contents. These properties generate the policy —
 * through {@link XmlStructFactory}, which is the same writer the layer itself
 * uses when a policy changes — write it into a fresh temporary directory, and
 * read it back.
 */
class XMLValidationLayerPropertyTest {

  private static Generator<String> identifiers() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  // ---------------------------------------------------------------- fixtures

  private static Path newPolicyDirectory() throws IOException {
    return Files.createTempDirectory("xml-vallayer-pbt");
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

  private static Element element(String id) {
    Element e = new Element();
    e.setElementId(id);
    e.setElementName(id.substring(id.lastIndexOf(':') + 1));
    e.setDescription("an element");
    e.setDCElement("Identifier");
    return e;
  }

  private static ProductType type(String id) {
    ProductType t = new ProductType();
    t.setProductTypeId(id);
    t.setName(id.substring(id.lastIndexOf(':') + 1));
    return t;
  }

  /**
   * Writes a policy directory using the same writer {@code XMLValidationLayer}
   * calls when it saves, so what is on disk is what the layer would have put
   * there.
   */
  private static void writePolicy(
      Path dir,
      List<Element> elements,
      ConcurrentHashMap<String, List<Element>> typeMap,
      ConcurrentHashMap<String, String> subToSuper) {
    XmlStructFactory.writeElementXmlDocument(elements, dir.resolve("elements.xml").toString());
    XmlStructFactory.writeProductTypeMapXmLDocument(
        typeMap, subToSuper, dir.resolve("product-type-element-map.xml").toString());
  }

  private static XMLValidationLayer layerOver(Path dir) {
    return new XMLValidationLayer(List.of(dir.toUri().toString()));
  }

  private static Set<String> idsOf(List<Element> elements) {
    Set<String> ids = new LinkedHashSet<>();
    for (Element e : elements) {
      ids.add(e.getElementId());
    }
    return ids;
  }

  // -------------------------------------------------------------- properties

  /**
   * An element declared for one product type must be returned for that type,
   * and must not be returned for a type it was not declared for.
   *
   * <p>This is the whole job of the product-type-element map. Leaking an
   * element into another type widens what that type stores; dropping one
   * narrows it, and the metadata simply disappears on the next ingest.
   */
  @HegelTest(testCases = 40)
  void anElementIsReturnedOnlyForTheTypesItIsDeclaredFor(TestCase tc) throws Exception {
    List<String> forA = tc.draw(lists(identifiers()).minSize(1).maxSize(4), "forA");
    List<String> forB = tc.draw(lists(identifiers()).minSize(1).maxSize(4), "forB");

    Path dir = newPolicyDirectory();
    try {
      List<Element> aElements = new ArrayList<>();
      for (int i = 0; i < forA.size(); i++) {
        aElements.add(element("urn:oodt:a" + i + forA.get(i)));
      }
      List<Element> bElements = new ArrayList<>();
      for (int i = 0; i < forB.size(); i++) {
        bElements.add(element("urn:oodt:b" + i + forB.get(i)));
      }

      List<Element> all = new ArrayList<>(aElements);
      all.addAll(bElements);

      ConcurrentHashMap<String, List<Element>> typeMap = new ConcurrentHashMap<>();
      typeMap.put("urn:oodt:TypeA", aElements);
      typeMap.put("urn:oodt:TypeB", bElements);
      writePolicy(dir, all, typeMap, new ConcurrentHashMap<>());

      XMLValidationLayer layer = layerOver(dir);

      assertEquals(
          idsOf(aElements),
          idsOf(layer.getElements(type("urn:oodt:TypeA"))),
          "TypeA got the wrong element list");
      assertEquals(
          idsOf(bElements),
          idsOf(layer.getElements(type("urn:oodt:TypeB"))),
          "TypeB got the wrong element list");
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * A policy directory written by the layer and read back by the layer must
   * describe the same policy.
   *
   * <p>The layer rewrites both files every time an element or a mapping
   * changes, and the server reads them again on the next start-up. A policy
   * that is not a fixed point of that pair changes underneath a deployment
   * without anyone editing it.
   */
  @HegelTest(testCases = 40)
  void thePolicySurvivesBeingSavedAndReloaded(TestCase tc) throws Exception {
    List<String> ids = tc.draw(lists(identifiers()).minSize(1).maxSize(5), "ids");

    Path dir = newPolicyDirectory();
    try {
      List<Element> elements = new ArrayList<>();
      for (int i = 0; i < ids.size(); i++) {
        elements.add(element("urn:oodt:" + i + ids.get(i)));
      }

      ConcurrentHashMap<String, List<Element>> typeMap = new ConcurrentHashMap<>();
      typeMap.put("urn:oodt:Generated", new Vector<>(elements));
      writePolicy(dir, elements, typeMap, new ConcurrentHashMap<>());

      XMLValidationLayer first = layerOver(dir);
      Set<String> before = idsOf(first.getElements(type("urn:oodt:Generated")));

      /* addElement triggers a full save of both files */
      Element extra = element("urn:oodt:Extra");
      first.addElement(extra);
      first.addElementToProductType(type("urn:oodt:Generated"), extra);

      XMLValidationLayer reloaded = layerOver(dir);
      Set<String> after = idsOf(reloaded.getElements(type("urn:oodt:Generated")));

      Set<String> expected = new LinkedHashSet<>(before);
      expected.add("urn:oodt:Extra");
      assertEquals(expected, after, "the saved policy did not reload as it was written");

      assertNotNull(
          reloaded.getElementById("urn:oodt:Extra"), "the added element is not in the element map");
      assertEquals(
          "Extra",
          reloaded.getElementByName("Extra").getElementName(),
          "lookup by name did not find the added element");
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * A product type that declares no parent must not be reported as having one.
   *
   * <p>The sub-to-super map is served straight out to the curator's metadata
   * REST resource, and it is what {@code getElements} walks to collect
   * inherited elements. A phantom parent on every type is a hierarchy nobody
   * wrote.
   */
  @HegelTest(testCases = 30)
  void aTypeWithoutAParentIsNotReportedAsHavingOne(TestCase tc) throws Exception {
    int typeCount = tc.draw(integers().min(1).max(4), "typeCount");

    Path dir = newPolicyDirectory();
    try {
      List<Element> elements = List.of(element("urn:oodt:Only"));
      ConcurrentHashMap<String, List<Element>> typeMap = new ConcurrentHashMap<>();
      Set<String> typeIds = new LinkedHashSet<>();
      for (int i = 0; i < typeCount; i++) {
        String id = "urn:oodt:Type" + i;
        typeIds.add(id);
        typeMap.put(id, new Vector<>(elements));
      }

      /* no parents at all */
      writePolicy(dir, elements, typeMap, new ConcurrentHashMap<>());

      XMLValidationLayer layer = layerOver(dir);
      Map<String, String> parents = layer.getSubToSuperMap();

      for (String id : typeIds) {
        assertTrue(
            !parents.containsKey(id),
            "type " + id + " declares no parent but is reported as a child of '"
                + parents.get(id) + "'");
      }
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * A child product type must see its parent's elements as well as its own,
   * and asking for its direct elements must give back only its own.
   */
  @HegelTest(testCases = 30)
  void aChildTypeInheritsItsParentsElements(TestCase tc) throws Exception {
    List<String> parentIds = tc.draw(lists(identifiers()).minSize(1).maxSize(3), "parentIds");
    List<String> childIds = tc.draw(lists(identifiers()).minSize(1).maxSize(3), "childIds");

    Path dir = newPolicyDirectory();
    try {
      List<Element> parentElements = new ArrayList<>();
      for (int i = 0; i < parentIds.size(); i++) {
        parentElements.add(element("urn:oodt:p" + i + parentIds.get(i)));
      }
      List<Element> childElements = new ArrayList<>();
      for (int i = 0; i < childIds.size(); i++) {
        childElements.add(element("urn:oodt:c" + i + childIds.get(i)));
      }

      List<Element> all = new ArrayList<>(parentElements);
      all.addAll(childElements);

      ConcurrentHashMap<String, List<Element>> typeMap = new ConcurrentHashMap<>();
      typeMap.put("urn:oodt:Parent", parentElements);
      typeMap.put("urn:oodt:Child", childElements);
      ConcurrentHashMap<String, String> subToSuper = new ConcurrentHashMap<>();
      subToSuper.put("urn:oodt:Child", "urn:oodt:Parent");

      writePolicy(dir, all, typeMap, subToSuper);

      XMLValidationLayer layer = layerOver(dir);

      Set<String> expectedInherited = new HashSet<>(idsOf(childElements));
      expectedInherited.addAll(idsOf(parentElements));
      assertEquals(
          expectedInherited,
          new HashSet<>(idsOf(layer.getElements(type("urn:oodt:Child")))),
          "the child did not inherit its parent's elements");

      assertEquals(
          new HashSet<>(idsOf(childElements)),
          new HashSet<>(idsOf(layer.getElements(type("urn:oodt:Child"), true))),
          "asking for direct elements returned inherited ones too");

      assertEquals(
          new HashSet<>(idsOf(parentElements)),
          new HashSet<>(idsOf(layer.getElements(type("urn:oodt:Parent")))),
          "the parent picked up the child's elements");
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * Removing one element from a product type must leave the type's other
   * elements alone.
   */
  @HegelTest(testCases = 30)
  void removingOneElementFromATypeLeavesTheRest(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(2).max(5), "count");
    int victim = tc.draw(integers().min(0).max(count - 1), "victim");

    Path dir = newPolicyDirectory();
    try {
      List<Element> elements = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        elements.add(element("urn:oodt:E" + i));
      }
      ConcurrentHashMap<String, List<Element>> typeMap = new ConcurrentHashMap<>();
      typeMap.put("urn:oodt:Generated", new Vector<>(elements));
      writePolicy(dir, elements, typeMap, new ConcurrentHashMap<>());

      XMLValidationLayer layer = layerOver(dir);
      ProductType generated = type("urn:oodt:Generated");
      layer.removeElementFromProductType(generated, elements.get(victim));

      Set<String> expected = new LinkedHashSet<>(idsOf(elements));
      expected.remove(elements.get(victim).getElementId());

      assertEquals(
          expected,
          idsOf(layer.getElements(generated)),
          "removing element " + victim + " of " + count + " disturbed the others");

      XMLValidationLayer reloaded = layerOver(dir);
      assertEquals(
          expected,
          idsOf(reloaded.getElements(generated)),
          "the removal did not survive being saved and reloaded");
    } finally {
      deleteRecursively(dir);
    }
  }
}
