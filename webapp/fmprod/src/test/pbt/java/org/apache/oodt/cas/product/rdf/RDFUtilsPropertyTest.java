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

package org.apache.oodt.cas.product.rdf;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.maps;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Properties of the RDF elements {@link RDFUtils} builds from a
 * {@link RDFConfig}.
 *
 * <p>The config is the deployer's whole control over the RDF feed: a rewrite
 * map renames a metadata key, a namespace map decides its prefix and a resource
 * link map turns a value into a link. Every property here is stated over a
 * config a deployer could write, and checks the element an RDF consumer ends up
 * parsing rather than the DOM node in isolation.
 */
class RDFUtilsPropertyTest {

  private static final List<String> NAME_CHARS =
      Arrays.asList("a", "b", "c", "d", "e", "F", "G", "H", "i", "j", "k", "Z");

  /**
   * A word out of the alphabet metadata keys, namespace prefixes and product
   * type names actually use. XML names are drawn from it, so it is deliberately
   * plain ASCII.
   */
  private static Generator<String> word() {
    return lists(sampledFrom(NAME_CHARS))
        .minSize(1)
        .maxSize(6)
        .map(cs -> String.join("", cs));
  }

  /**
   * A metadata value: ordinary text around one character that XML gives a
   * meaning to. Catalogue values really do contain ampersands and apostrophes.
   */
  private static String drawValue(TestCase tc) {
    String before = tc.draw(word(), "before");
    String special =
        tc.draw(sampledFrom(Arrays.asList("&", "<", ">", "\"", "'", " ", "-")), "special");
    String after = tc.draw(word(), "after");
    return before + special + after;
  }

  private static Document newDocument() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().newDocument();
  }

  /** Serialise and parse back, as the RDF servlets do when they answer. */
  private static Element throughTheFeed(Document doc, Element root) throws Exception {
    // The servlets declare these on the enclosing rdf:RDF element; declare them
    // here so the fragment on its own is serialisable.
    root.setAttribute("xmlns:cas", "http://example.org/cas#");
    root.setAttribute("xmlns:rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
    doc.appendChild(root);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.transform(new DOMSource(doc), new StreamResult(bytes));

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(bytes.toByteArray()))
        .getDocumentElement();
  }

  /**
   * A metadata value published as an RDF literal reaches the consumer
   * unchanged. An RDF triple whose object has been re-encoded is a different
   * triple.
   */
  @HegelTest
  void aLiteralValueSurvivesTheFeedUnchanged(TestCase tc) throws Exception {
    String key = tc.draw(word(), "key");
    String value = drawValue(tc);

    RDFConfig conf = new RDFConfig();
    conf.setDefaultKeyNs("cas");

    Document doc = newDocument();
    Element elem = throughTheFeed(doc, RDFUtils.getRDFElement(key, value, conf, doc));

    assertEquals(value, elem.getTextContent());
  }

  /**
   * The rewrite map is honoured, and only for the keys it names. This is how a
   * deployer maps a CAS key onto the vocabulary term their consumers expect,
   * so a key they did not list must come through untouched.
   */
  @HegelTest
  void theRewriteMapRenamesExactlyTheKeysItNames(TestCase tc) throws Exception {
    Map<String, String> rewrites =
        tc.draw(maps(word(), word()).minSize(0).maxSize(5), "rewrites");
    String key = tc.draw(word(), "key");
    String value = tc.draw(word(), "value");

    RDFConfig conf = new RDFConfig();
    conf.setDefaultKeyNs("cas");
    conf.setRewriteMap(rewrites);

    Document doc = newDocument();
    Element elem = RDFUtils.getRDFElement(key, value, conf, doc);

    String expected = rewrites.containsKey(key) ? rewrites.get(key) : key;
    assertEquals("cas:" + expected, elem.getTagName());
  }

  /**
   * A key gets its own declared namespace if it has one and the default
   * otherwise. {@link RDFConfig#getKeyNs} is the single place that decision is
   * made, and the element name has to agree with it.
   */
  @HegelTest
  void everyElementCarriesTheNamespaceTheConfigChoseForItsKey(TestCase tc) {
    Map<String, String> keyNs = tc.draw(maps(word(), word()).minSize(0).maxSize(5), "keyNs");
    String defaultNs = tc.draw(word(), "defaultNs");
    String key = tc.draw(word(), "key");
    String value = tc.draw(word(), "value");

    RDFConfig conf = new RDFConfig();
    conf.setDefaultKeyNs(defaultNs);
    conf.setKeyNsMap(keyNs);

    try {
      Document doc = newDocument();
      Element elem = RDFUtils.getRDFElement(key, value, conf, doc);
      assertEquals(conf.getKeyNs(key) + ":" + key, elem.getTagName());
    } catch (Exception e) {
      throw new AssertionError("building an RDF element failed: " + e, e);
    }
  }

  /**
   * A key listed in the resource link map becomes a link rather than a
   * literal, and the link is the configured base joined to the value by exactly
   * one slash — whether or not the deployer remembered the trailing slash. A
   * doubled or missing slash is a different, usually dead, URI.
   */
  @HegelTest
  void aResourceLinkJoinsTheBaseAndValueByOneSlash(TestCase tc) throws Exception {
    String key = tc.draw(word(), "key");
    String value = tc.draw(word(), "value");
    String host = tc.draw(word(), "host");
    String path = tc.draw(word(), "path");
    boolean trailingSlash = tc.draw(booleans(), "trailingSlash");

    String base = "http://example.org/" + host + "/" + path + (trailingSlash ? "/" : "");

    RDFConfig conf = new RDFConfig();
    conf.setDefaultKeyNs("cas");
    conf.getResLinkMap().put(key, base);

    Document doc = newDocument();
    Element elem = throughTheFeed(doc, RDFUtils.getRDFElement(key, value, conf, doc));

    String link = elem.getAttribute("rdf:resource");
    assertNotNull(link);
    assertEquals(
        "http://example.org/" + host + "/" + path + "/" + value,
        link,
        "the resource link was not the base joined to the value by one slash");
    assertEquals("", elem.getTextContent(), "a resource link also carried a literal body");
  }

  /**
   * Every namespace the config declares ends up on the RDF root element, under
   * the prefix the config gave it. A consumer that meets a prefix with no
   * declaration cannot resolve the term.
   */
  @HegelTest
  void everyDeclaredNamespaceReachesTheRootElement(TestCase tc) throws Exception {
    Map<String, String> namespaces =
        tc.draw(maps(word(), word()).minSize(0).maxSize(6), "namespaces");

    RDFConfig conf = new RDFConfig();
    conf.setNsMap(namespaces);

    Document doc = newDocument();
    Element root = doc.createElement("rdf:RDF");
    RDFUtils.addNamespaces(doc, root, conf);

    for (Map.Entry<String, String> entry : namespaces.entrySet()) {
      assertTrue(
          root.hasAttribute("xmlns:" + entry.getKey()),
          "namespace prefix [" + entry.getKey() + "] was not declared");
      assertEquals(entry.getValue(), root.getAttribute("xmlns:" + entry.getKey()));
    }
  }
}
