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

package org.apache.oodt.commons.util;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * Properties of {@link XML#escape(String)}.
 *
 * <p>{@code escape} exists so that arbitrary text can be dropped into a
 * document as element content or as an attribute value. That is a round trip:
 * whatever a caller escapes on the way out has to come back unchanged when the
 * document is parsed on the way in. Anything else is silent corruption of the
 * catalog metadata this class is used to write.
 *
 * <p>The properties parse with a plain non-validating builder rather than
 * {@link XML#parse(String)}. {@code XML.parse} turns validation on and installs
 * an error handler that prints to standard error, which would bury a real
 * failure under a wall of "no grammar found" stack traces; the escaping
 * contract has nothing to do with validation.
 */
class XMLPropertyTest {

  /** Every character the escaper is asked about, wrapped and read back. */
  private static String throughAParser(String escaped, boolean asAttribute) throws Exception {
    String document =
        asAttribute ? "<a v=\"" + escaped + "\"/>" : "<a>" + escaped + "</a>";

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setValidating(false);
    factory.setNamespaceAware(false);
    factory.setCoalescing(true);
    factory.setExpandEntityReferences(true);
    DocumentBuilder builder = factory.newDocumentBuilder();

    Element root =
        builder.parse(new InputSource(new StringReader(document))).getDocumentElement();
    return asAttribute ? root.getAttribute("v") : root.getTextContent();
  }

  /**
   * Text made of characters XML 1.0 can carry survives being escaped, written
   * into a document, and parsed back out — in element content and in an
   * attribute value alike, which are the two placements the method documents.
   *
   * <p>The domain is the Basic Multilingual Plane from the space upwards: that
   * is ordinary prose, the five characters that need entity references, and
   * accented and CJK text. Control characters are left out on purpose because
   * XML 1.0 cannot represent them at all, so no escaping could rescue them.
   */
  @HegelTest
  void escapedTextSurvivesAnXmlRoundTrip(TestCase tc) throws Exception {
    String original = tc.draw(text().codepoints(0x20, 0xD7FF).maxSize(40), "original");
    boolean asAttribute = tc.draw(booleans(), "asAttribute");

    String escaped = XML.escape(original);
    tc.note("escaped = " + escaped);

    assertEquals(original, throughAParser(escaped, asAttribute));
  }

  /**
   * The same round trip for characters outside the Basic Multilingual Plane —
   * emoji, historic scripts, the supplementary CJK blocks. Java holds these as
   * a surrogate pair, but a surrogate is a half of a character, not a character
   * a document may contain.
   */
  @HegelTest
  void escapedSupplementaryCharactersSurviveAnXmlRoundTrip(TestCase tc) throws Exception {
    String original = tc.draw(text().codepoints(0x10000, 0x10FFFF).minSize(1).maxSize(4), "original");

    String escaped = XML.escape(original);
    tc.note("escaped = " + escaped);

    assertEquals(original, throughAParser(escaped, false));
  }

  /**
   * Line endings survive the round trip. A carriage return is a legal XML
   * character, but a parser folds a literal one into a line feed before the
   * application ever sees it, so text escaped for a document has to say
   * explicitly which of the two it meant. Text written on Windows goes through
   * this method with its CRLF pairs intact.
   */
  @HegelTest
  void escapedLineEndingsSurviveAnXmlRoundTrip(TestCase tc) throws Exception {
    List<String> pieces =
        tc.draw(lists(sampledFrom("\r", "\n", "\t", " ", "x")).minSize(1).maxSize(8), "pieces");
    String original = String.join("", pieces);

    String escaped = XML.escape(original);
    tc.note("escaped = " + escaped);

    assertEquals(original, throughAParser(escaped, false));
  }
}
