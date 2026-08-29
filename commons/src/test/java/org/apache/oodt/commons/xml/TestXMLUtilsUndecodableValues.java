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

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * {@link XMLUtils#read} and {@link XMLUtils#readMany} used to catch, log and
 * return null / drop the element, so a caller could not tell "not supplied"
 * from "supplied but unreadable". A bare '%' is the shrunk case: URLDecoder
 * raises on the incomplete escape.
 */
public class TestXMLUtilsUndecodableValues {

  @Test
  public void anUndecodableValueIsNotReportedAsAbsent() throws Exception {
    Element root = parse("<root><val>0%</val></root>");

    assertEquals("0%", XMLUtils.read(root, "val"));
  }

  @Test
  public void anUndecodableValueDoesNotShortenTheList() throws Exception {
    Element root = parse("<root><val>ok</val><val>100%</val><val>also ok</val></root>");

    List values = XMLUtils.readMany(root, "val");

    assertEquals(3, values.size());
    assertEquals("ok", values.get(0));
    assertEquals("100%", values.get(1));
    assertEquals("also ok", values.get(2));
  }

  /** A genuinely encoded value still decodes; this is not a blanket bypass. */
  @Test
  public void anEncodedValueStillDecodes() throws Exception {
    Element root = parse("<root><val>a%20b</val></root>");

    assertEquals("a b", XMLUtils.read(root, "val"));
  }

  @Test
  public void anEncodedListStillDecodes() throws Exception {
    Element root = parse("<root><val>a%20b</val><val>c%2Fd</val></root>");

    List values = XMLUtils.readMany(root, "val");

    assertEquals("a b", values.get(0));
    assertEquals("c/d", values.get(1));
  }

  @Test
  public void aMissingElementIsStillNull() throws Exception {
    Element root = parse("<root><val>x</val></root>");

    assertNotNull(XMLUtils.read(root, "val"));
    assertEquals(0, XMLUtils.readMany(root, "absent").size());
  }

  private Element parse(String xml) throws Exception {
    Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new InputSource(new StringReader(xml)));
    return document.getDocumentElement();
  }
}
