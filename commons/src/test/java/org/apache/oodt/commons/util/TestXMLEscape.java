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

import org.junit.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;

import static org.junit.Assert.*;

/**
 * escape iterated by char. A char is a UTF-16 code unit, not a character, and
 * this treated the two as the same thing: every character outside the Basic
 * Multilingual Plane was emitted as two references, one per surrogate half.
 * Surrogate code points are not legal XML characters in their own right, so
 * the result was a document no parser would accept.
 */
public class TestXMLEscape {

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><t>" + xml + "</t>")
                        .getBytes("UTF-8")));
    }

    private static String roundTrip(String original) throws Exception {
        return parse(XML.escape(original)).getDocumentElement().getTextContent();
    }

    /** U+10000, one character, which used to escape to two surrogate refs. */
    @Test
    public void testASupplementaryCharacterSurvivesAnXmlRoundTrip() throws Exception {
        String original = new String(Character.toChars(0x10000));

        String escaped = XML.escape(original);
        assertFalse("a surrogate half was emitted as its own reference: " + escaped,
                escaped.contains("55296") || escaped.contains("56320"));
        assertEquals(original, roundTrip(original));
    }

    @Test
    public void testAnEmojiSurvivesAnXmlRoundTrip() throws Exception {
        String original = new String(Character.toChars(0x1F600));

        assertEquals(original, roundTrip(original));
    }

    /**
     * A literal carriage return is folded to \n by XML line-end
     * normalisation before the application ever sees it, so CRLF text lost
     * its CRs on a round trip.
     */
    @Test
    public void testACarriageReturnSurvivesAnXmlRoundTrip() throws Exception {
        assertEquals("a\r\nb", roundTrip("a\r\nb"));
    }

    /** Ordinary text is unchanged. */
    @Test
    public void testOrdinaryTextIsUnchanged() throws Exception {
        assertEquals("Hello, world", XML.escape("Hello, world"));
        assertEquals("Hello, world", roundTrip("Hello, world"));
    }

    /** The entity references still work. */
    @Test
    public void testMarkupIsStillEscaped() throws Exception {
        assertEquals("a < b & c > d", roundTrip("a < b & c > d"));
    }

    /** A BMP non-ASCII character still becomes one reference. */
    @Test
    public void testABmpCharacterSurvivesAnXmlRoundTrip() throws Exception {
        assertEquals("\u00e9", roundTrip("\u00e9"));
        assertEquals("&#233;", XML.escape("\u00e9"));
    }
}
