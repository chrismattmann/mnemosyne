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

import org.apache.oodt.cas.metadata.Metadata;

import junit.framework.TestCase;

/**
 * The values rendered here are product metadata, extracted from ingested
 * files, and the person who opens the page is a curator. Anything a data
 * producer can put in a metadata value therefore reaches a curator's browser,
 * so it has to arrive as text rather than as markup.
 */
public class TestMetadataResourceHtml extends TestCase {

    private MetadataResource resource;

    protected void setUp() {
        resource = new MetadataResource();
    }

    private static Metadata metadata(String key, String value) {
        Metadata met = new Metadata();
        met.addMetadata(key, value);
        return met;
    }

    /** The serious one: a key with a quote closes the class attribute. */
    public void testQuoteInAKeyCannotCloseTheAttribute() {
        String html = resource.getMetadataAsHTML(
                metadata("bad\" onmouseover=\"alert(1)", "x"));

        assertFalse("the key escaped its attribute: " + html,
                html.contains("onmouseover=\"alert(1)\""));
        assertTrue("the quote was not escaped: " + html, html.contains("&quot;"));
    }

    public void testScriptInAValueIsNotMarkup() {
        String html = resource.getMetadataAsHTML(
                metadata("Description", "<script>alert(1)</script>"));

        assertFalse("a script tag reached the page: " + html,
                html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    public void testScriptInAKeyIsNotMarkup() {
        String html = resource.getMetadataAsHTML(
                metadata("<script>alert(1)</script>", "x"));

        assertFalse("a script tag reached the page: " + html,
                html.contains("<script>"));
    }

    /** The benign symptom from the report, and still worth being right. */
    public void testAmpersandProducesParseableMarkup() {
        String html = resource.getMetadataAsHTML(metadata("Name", "a&a"));

        assertFalse("a bare ampersand is not parseable: " + html,
                html.contains(">a&a<"));
        assertTrue(html.contains("a&amp;a"));
    }

    /** Ordinary text must still read as itself. */
    public void testOrdinaryTextIsUnchanged() {
        String html = resource.getMetadataAsHTML(metadata("Filename", "foo.txt"));

        assertTrue(html.contains("<th>Filename</th>"));
        assertTrue(html.contains("<span>foo.txt</span>"));
    }

    /** Escaping must not corrupt text outside ASCII. */
    public void testNonAsciiSurvives() {
        String html = resource.getMetadataAsHTML(metadata("Name", "Ω 😀 café"));
        assertTrue("non-ascii text was altered: " + html, html.contains("Ω 😀 café"));
    }

    public void testNullMetadataIsStillAnEmptyTable() {
        assertEquals("<table></table>", resource.getMetadataAsHTML(null));
    }

    /** The other HTML the class produces has the same exposure. */
    public void testExtractorConfigIdsAreEscaped() {
        String html = resource.getExtractorConfigIdsAsHTML(
                new String[] {"ok", "bad\" onmouseover=\"alert(1)"}, "ok");

        assertFalse("the config id escaped its attribute: " + html,
                html.contains("onmouseover=\"alert(1)\""));
        assertTrue(html.contains("&quot;"));
    }

    public void testExtractorConfigIdsKeepSelection() {
        String html = resource.getExtractorConfigIdsAsHTML(
                new String[] {"a", "b"}, "b");
        assertTrue("the current option is no longer selected: " + html,
                html.contains("selected "));
    }
}
