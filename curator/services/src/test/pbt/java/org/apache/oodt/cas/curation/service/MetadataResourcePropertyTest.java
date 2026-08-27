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

package org.apache.oodt.cas.curation.service;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.oodt.cas.metadata.Metadata;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Properties of the metadata table the curator renders.
 *
 * <p>{@link MetadataResource#getMetadataAsHTML} is the one piece of
 * {@code MetadataResource} that neither reads the static service config nor
 * opens a file manager client, so it is the only part of the class a test can
 * reach without a servlet container. It renders catalogue metadata straight
 * into a page a curator reads, which makes both of the properties below things
 * a user relies on: that the page shows the values the catalogue holds, and
 * that it is a page at all.
 */
class MetadataResourcePropertyTest {

  private static final List<String> PLAIN_CHARS =
      Arrays.asList("a", "b", "c", "D", "E", "1", "2", "_", "-", " ");

  /** Ordinary metadata text. */
  private static Generator<String> plain() {
    return lists(sampledFrom(PLAIN_CHARS)).minSize(1).maxSize(8).map(cs -> String.join("", cs));
  }

  /**
   * A metadata value containing one character that markup gives a meaning to.
   * Catalogue values really do contain ampersands and quotation marks — an
   * instrument called "Rain &amp; Snow", a title quoted verbatim from a file
   * header.
   */
  private static String drawMarkupValue(TestCase tc) {
    String before = tc.draw(plain(), "before");
    String special = tc.draw(sampledFrom(Arrays.asList("&", "<", ">", "\"", "'")), "special");
    String after = tc.draw(plain(), "after");
    return before + special + after;
  }

  private static Document parse(String html) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(html.getBytes("UTF-8")));
  }

  /**
   * Every key the catalogue holds appears in the table, with every one of its
   * values. The table is the whole of what a curator sees of a product's
   * metadata, so a value it drops is a value they cannot know about.
   */
  @HegelTest
  void everyKeyAndValueReachesTheTable(TestCase tc) {
    List<String> keys = tc.draw(lists(plain()).minSize(1).maxSize(4), "keys");
    List<String> values = tc.draw(lists(plain()).minSize(1).maxSize(3), "values");

    Metadata metadata = new Metadata();
    Set<String> distinctKeys = new HashSet<String>();
    for (String key : keys) {
      distinctKeys.add(key);
      for (String value : values) {
        metadata.addMetadata(key, value);
      }
    }

    String html = new MetadataResource().getMetadataAsHTML(metadata);

    for (String key : distinctKeys) {
      assertTrue(html.contains("<th>" + key + "</th>"), "key [" + key + "] is missing");
    }
    for (String value : values) {
      assertTrue(html.contains("<span>" + value + "</span>"), "value [" + value + "] is missing");
    }
  }

  /** With nothing to show, the curator gets an empty table rather than an error. */
  @HegelTest
  void noMetadataRendersAnEmptyTable(TestCase tc) throws Exception {
    boolean unused = tc.draw(dev.hegel.Generators.booleans(), "unused");

    String html = new MetadataResource().getMetadataAsHTML(null);

    Document doc = parse(html);
    assertEquals("table", doc.getDocumentElement().getTagName());
    assertEquals(0, doc.getElementsByTagName("tr").getLength());
  }

  /**
   * The table renders as markup and each cell holds the value the catalogue
   * holds. A value carrying a character that markup gives a meaning to must
   * still be shown as that value, and must not be able to end the cell it is
   * in — that is the difference between a rendered page and an injected one.
   */
  @HegelTest
  void aValueWithMarkupCharactersStillRendersAsThatValue(TestCase tc) throws Exception {
    String key = tc.draw(plain(), "key");
    String value = drawMarkupValue(tc);

    Metadata metadata = new Metadata();
    metadata.addMetadata(key, value);

    String html = new MetadataResource().getMetadataAsHTML(metadata);

    Document doc;
    try {
      doc = parse(html);
    } catch (Exception e) {
      throw new AssertionError("the rendered table is not markup: [" + html + "]", e);
    }

    NodeList spans = doc.getElementsByTagName("span");
    List<String> shown = new ArrayList<String>();
    for (int i = 0; i < spans.getLength(); i++) {
      shown.add(((Element) spans.item(i)).getTextContent());
    }
    assertEquals(Arrays.asList(value), shown, "the table did not show the value it was given");
  }
}
