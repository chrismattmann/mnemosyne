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

package org.apache.oodt.cas.product.rss;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.commons.xml.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Properties of the RSS feed fragments built by {@link RSSUtils}.
 *
 * <p>{@link RSSProductServlet} builds a DOM and hands it to a {@link Transformer}
 * for serialisation, so every property here is stated over the feed a reader
 * actually receives: the DOM is serialised and parsed back exactly the way the
 * servlet serialises it. The values are ordinary metadata values — a product
 * name, an instrument, a title — over the characters a catalogue really holds.
 */
class RSSUtilsPropertyTest {

  /** The metadata key the generated tags read their value from. */
  private static final String KEY = "AValue";

  /** Ordinary text, with nothing in it that {@link org.apache.oodt.cas.metadata.util.PathUtils} would treat as a variable. */
  private static Generator<String> word() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  private static final List<String> ASCII_LETTERS =
      Arrays.asList("a", "b", "c", "d", "e", "F", "G", "H", "i", "j", "k", "Z");

  /** A word usable as an XML name: plain ASCII letters, as a config file uses. */
  private static Generator<String> nameWord() {
    return lists(sampledFrom(ASCII_LETTERS)).minSize(1).maxSize(6).map(cs -> String.join("", cs));
  }

  /** A word out of the alphabet real product type and host names use. */
  private static Generator<String> asciiWord() {
    return lists(sampledFrom(ASCII_LETTERS))
        .minSize(1)
        .maxSize(8)
        .map(letters -> String.join("", letters));
  }

  /**
   * A metadata value: ordinary text around one character that XML gives a
   * meaning to. Catalogue values really do contain ampersands ("Rain &amp;
   * Snow"), apostrophes and angle brackets, and it is exactly those that a
   * feed has to carry through unharmed.
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

  /**
   * Serialise exactly as {@link RSSProductServlet} does, then parse the result
   * back. This is the feed as a reader sees it.
   */
  private static Document throughTheFeed(Document doc) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.transform(new DOMSource(doc), new StreamResult(bytes));

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes.toByteArray()));
  }

  private static RSSTag tagReading(String name, String source) {
    RSSTag tag = new RSSTag();
    tag.setName(name);
    tag.setSource(source);
    return tag;
  }

  /**
   * A metadata value published as the body of an RSS tag reaches the reader
   * unchanged. This is the whole job of a feed: a consumer parses the XML and
   * must get back the value the catalogue holds, not a re-encoded version of
   * it.
   */
  @HegelTest
  void aValueSurvivesTheFeedUnchanged(TestCase tc) throws Exception {
    String value = drawValue(tc);

    Metadata met = new Metadata();
    met.addMetadata(KEY, value);

    Document doc = newDocument();
    Element item = XMLUtils.addNode(doc, doc, "item");
    RSSUtils.emitRSSTag(tagReading("value", "[" + KEY + "]"), met, doc, item);

    Element emitted =
        (Element) throughTheFeed(doc).getElementsByTagName("value").item(0);
    assertNotNull(emitted, "the tag was not emitted at all");
    assertEquals(value, emitted.getTextContent());
  }

  /**
   * The same value published as an attribute reaches the reader unchanged.
   * Attributes and element bodies are two ways of saying the same thing in the
   * same feed, so they cannot disagree about what a value is.
   */
  @HegelTest
  void aValueSurvivesTheFeedUnchangedAsAnAttribute(TestCase tc) throws Exception {
    String value = drawValue(tc);

    Metadata met = new Metadata();
    met.addMetadata(KEY, value);

    RSSTag tag = tagReading("value", null);
    RSSTagAttribute attr = new RSSTagAttribute();
    attr.setName("held");
    attr.setValue("[" + KEY + "]");
    List<RSSTagAttribute> attrs = new ArrayList<RSSTagAttribute>();
    attrs.add(attr);
    tag.setAttrs(attrs);

    Document doc = newDocument();
    Element item = XMLUtils.addNode(doc, doc, "item");
    RSSUtils.emitRSSTag(tag, met, doc, item);

    Element emitted =
        (Element) throughTheFeed(doc).getElementsByTagName("value").item(0);
    assertNotNull(emitted, "the tag was not emitted at all");
    assertEquals(value, emitted.getAttribute("held"));
  }

  /**
   * A tag name written with spaces in the config file still produces a feed a
   * reader can parse. {@link RSSUtils} joins such a name into one word for
   * exactly this reason: an element name containing a space is not well-formed
   * XML, and would make the whole feed unreadable rather than just that tag.
   */
  @HegelTest
  void aSpacedTagNameStillYieldsAWellFormedFeed(TestCase tc) throws Exception {
    String first = tc.draw(nameWord(), "first");
    String second = tc.draw(nameWord(), "second");
    String value = tc.draw(word(), "value");

    Metadata met = new Metadata();
    met.addMetadata(KEY, value);

    Document doc = newDocument();
    Element item = XMLUtils.addNode(doc, doc, "item");
    Element emitted =
        RSSUtils.emitRSSTag(
            tagReading(first + " " + second, "[" + KEY + "]"), met, doc, item);

    String name = emitted.getTagName();
    assertEquals(-1, name.indexOf(' '), "the emitted element name [" + name + "] contains a space");

    Element reread = (Element) throughTheFeed(doc).getElementsByTagName(name).item(0);
    assertNotNull(reread, "the tag did not survive a parse of the feed");
  }

  /**
   * A configured channel link is used as given, once its metadata variables are
   * filled in. A feed author who has spelled out the link must get that link.
   */
  @HegelTest
  void aConfiguredChannelLinkIsUsedAsWritten(TestCase tc) {
    String host = tc.draw(word(), "host");
    String type = tc.draw(word(), "type");

    Metadata met = new Metadata();
    met.addMetadata("ProductType", type);

    String configured = "http://" + host + "/rss?type=[ProductType]";
    assertEquals(
        "http://" + host + "/rss?type=" + type, RSSUtils.getChannelLink(configured, met));
  }

  /**
   * With no channel link configured, the one {@link RSSUtils} builds is a
   * usable URI naming the product type it was asked about. A feed reader
   * dereferences this link, so it has to parse and it has to carry the
   * identifiers through.
   *
   * <p>The type name is drawn from the characters real product type names use
   * — the policy files in this repository hold names like "GenericFile" and
   * "BookPage". Nothing here URL-encodes the values, so a name outside that
   * alphabet would produce a broken link; that is noted rather than asserted,
   * because it is not a case the configuration can currently reach.
   */
  @HegelTest
  void theDefaultChannelLinkIsAUsableUri(TestCase tc) {
    String host = tc.draw(asciiWord(), "host");
    String first = tc.draw(asciiWord(), "first");
    String second = tc.draw(asciiWord(), "second");
    String separator = tc.draw(sampledFrom(Arrays.asList("", "_", "-")), "separator");
    String id = tc.draw(asciiWord(), "id");
    String type = first + separator + second;

    Metadata met = new Metadata();
    met.addMetadata("BaseUrl", "http://example.org/" + host);
    met.addMetadata("ProductType", type);
    met.addMetadata("ProductTypeId", id);

    String link = RSSUtils.getChannelLink(null, met);

    java.net.URI uri;
    try {
      uri = new java.net.URI(link);
    } catch (java.net.URISyntaxException e) {
      throw new AssertionError("the channel link is not a usable URI: [" + link + "]", e);
    }
    assertEquals("type=" + type + "&typeID=" + id, uri.getQuery());
  }
}
