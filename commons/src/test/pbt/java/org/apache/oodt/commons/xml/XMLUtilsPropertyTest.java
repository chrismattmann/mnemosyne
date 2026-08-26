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

package org.apache.oodt.commons.xml;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.oodt.commons.exceptions.CommonsException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Properties of {@link XMLUtils} and {@link DOMUtil}, the DOM helpers that
 * {@code SerializableMetadata} and the XML-RPC clients build their documents
 * with.
 *
 * <p>The pairing that matters is {@code addNode} against {@code read}: a value
 * is written into an element with {@code addNode}, the document is serialised,
 * parsed back, and read out with {@code read}. That is the whole life of a
 * metadata value moving between two OODT components, and it must be the
 * identity.
 *
 * <p>{@code read} and {@code readMany} URL-decode what they find, so a caller
 * writing a raw value and reading it back only gets the identity if it encoded
 * on the way in — every property here does. The interesting question is what
 * happens when the decoder rejects the text: both methods catch
 * {@code Exception}, log, and return {@code null} (or silently drop the
 * element), turning a malformed value into a missing one.
 *
 * <p>Neither class had unit tests.
 */
class XMLUtilsPropertyTest {

  private static final String ELEMENT = "val";

  /** Values a metadata field plausibly holds, including the ones that need encoding. */
  private static Generator<String> values() {
    return text().maxSize(24);
  }

  /** Legal XML element and attribute names. */
  private static Generator<String> names() {
    return fromRegex("[a-zA-Z][a-zA-Z0-9_.-]{0,7}");
  }

  private static Document emptyDocument() throws Exception {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
  }

  /** Serialise a document and parse it back, the way two components exchange one. */
  private static Document roundTrip(Document doc) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    XMLUtils.writeXmlToStream(doc, out);
    return XMLUtils.getDocumentRoot(new ByteArrayInputStream(out.toByteArray()));
  }

  private static String encode(String value) throws UnsupportedEncodingException {
    return URLEncoder.encode(value, "UTF-8");
  }

  /**
   * A value written into a child element and read back out is the value that
   * was written. This is the contract every caller of these two methods
   * depends on, and the only reason the class exists.
   */
  @HegelTest
  void aValueWrittenIntoAnElementReadsBackUnchanged(TestCase tc) throws Exception {
    String value = tc.draw(values(), "value");

    Document doc = emptyDocument();
    Element root = doc.createElement("root");
    doc.appendChild(root);
    XMLUtils.addNode(doc, root, ELEMENT, encode(value));

    assertEquals(value, XMLUtils.read(root, ELEMENT), "the value changed in transit");
  }

  /**
   * The same round trip, but across an actual serialise-and-reparse. A DOM the
   * caller built in memory and a DOM that came off the wire must read the same,
   * or values survive locally and vanish between processes.
   */
  @HegelTest
  void aValueSurvivesSerialisationAndReparsing(TestCase tc) throws Exception {
    String value = tc.draw(values(), "value");

    Document doc = emptyDocument();
    Element root = doc.createElement("root");
    doc.appendChild(root);
    XMLUtils.addNode(doc, root, ELEMENT, encode(value));

    Document reparsed = roundTrip(doc);
    assertNotNull(reparsed, "the document did not survive serialisation");

    assertEquals(
        value,
        XMLUtils.read(reparsed.getDocumentElement(), ELEMENT),
        "the value did not survive serialisation");
  }

  /**
   * A list of values written under repeated elements comes back as the same
   * list, in the same order. Multi-valued metadata keys are stored exactly this
   * way, so a dropped element is a dropped value.
   */
  @HegelTest
  void everyRepeatedElementIsReadBackInOrder(TestCase tc) throws Exception {
    List<String> values = tc.draw(lists(values()).maxSize(8), "values");

    Document doc = emptyDocument();
    Element root = doc.createElement("root");
    doc.appendChild(root);
    for (String value : values) {
      XMLUtils.addNode(doc, root, ELEMENT, encode(value));
    }

    assertEquals(values, new ArrayList<>(XMLUtils.readMany(root, ELEMENT)));
  }

  /**
   * Reading an element that is not there answers {@code null} rather than
   * throwing. Callers test the result against {@code null} to decide whether an
   * optional field was supplied.
   */
  @HegelTest
  void readingAMissingElementIsNull(TestCase tc) throws Exception {
    String present = tc.draw(names(), "present");
    String absent = tc.draw(names(), "absent");
    tc.assume(!present.equals(absent));

    Document doc = emptyDocument();
    Element root = doc.createElement("root");
    doc.appendChild(root);
    XMLUtils.addNode(doc, root, present, "x");

    assertEquals(null, XMLUtils.read(root, absent));
    assertTrue(XMLUtils.readMany(root, absent).isEmpty());
  }

  /**
   * A caller must be able to tell "the element was not supplied" from "the
   * element was supplied but could not be read". {@code read} returns
   * {@code null} for both: it catches every exception the URL decoder raises,
   * logs it, and hands back the same {@code null} that a missing element
   * produces. Data written by a producer that did not URL-encode — a plain
   * percentage, say — therefore vanishes with no signal the caller can act on.
   *
   * <p>{@code addNode} does no encoding of its own, so nothing in this class
   * stops a caller producing such a value.
   */
  @HegelTest
  void anUndecodableValueIsNotConfusedWithAMissingOne(TestCase tc) throws Exception {
    int percentage = tc.draw(integers().min(0).max(100), "percentage");
    String value = percentage + "%";

    Document doc = emptyDocument();
    Element root = doc.createElement("root");
    doc.appendChild(root);
    XMLUtils.addNode(doc, root, ELEMENT, value);

    assertNotNull(
        XMLUtils.read(root, ELEMENT),
        "the value [" + value + "] was written but reads back as absent");
  }

  /** The same silent drop through the multi-valued path: an element goes missing. */
  @HegelTest
  void anUndecodableValueIsNotDroppedFromAList(TestCase tc) throws Exception {
    List<String> good = tc.draw(lists(values()).minSize(1).maxSize(4), "good");
    int badAt = tc.draw(integers().min(0).max(good.size()), "badAt");

    Document doc = emptyDocument();
    Element root = doc.createElement("root");
    doc.appendChild(root);
    for (int i = 0; i <= good.size(); i++) {
      if (i == badAt) {
        XMLUtils.addNode(doc, root, ELEMENT, "50%");
      }
      if (i < good.size()) {
        XMLUtils.addNode(doc, root, ELEMENT, encode(good.get(i)));
      }
    }

    assertEquals(
        good.size() + 1,
        XMLUtils.readMany(root, ELEMENT).size(),
        "an element written to the document was not read back");
  }

  /** An attribute written onto an element reads back with the value it was given. */
  @HegelTest
  void anAttributeReadsBackAsWritten(TestCase tc) throws Exception {
    String name = tc.draw(names(), "name");
    String value = tc.draw(values(), "value");

    Document doc = emptyDocument();
    Element root = doc.createElement("root");
    doc.appendChild(root);
    XMLUtils.addAttribute(doc, root, name, value);

    assertEquals(value, root.getAttribute(name));
  }

  /**
   * The text of an element is the concatenation of its text children. Values
   * long enough to be split across several text nodes by a parser must still
   * read as one string.
   */
  @HegelTest
  void elementTextIsTheWholeText(TestCase tc) throws Exception {
    List<String> fragments =
        tc.draw(lists(text().minSize(1).maxSize(8).categories("Ll")).maxSize(5), "fragments");

    Document doc = emptyDocument();
    Element root = doc.createElement("root");
    doc.appendChild(root);
    Element child = XMLUtils.addNode(doc, root, ELEMENT);
    for (String fragment : fragments) {
      child.appendChild(doc.createTextNode(fragment));
    }

    assertEquals(String.join("", fragments), DOMUtil.getSimpleElementText(child));
  }

  /**
   * Asking {@link DOMUtil} for an element that is not there is an error a
   * caller can catch, not a null it will dereference later.
   */
  @HegelTest
  void askingForAMissingElementIsAnError(TestCase tc) throws Exception {
    String absent = tc.draw(names(), "absent");

    Document doc = emptyDocument();
    Element root = doc.createElement("root");
    doc.appendChild(root);

    assertThrows(CommonsException.class, () -> DOMUtil.getFirstElement(root, absent));
    assertEquals(null, XMLUtils.getFirstElement(absent, root));
  }
}
