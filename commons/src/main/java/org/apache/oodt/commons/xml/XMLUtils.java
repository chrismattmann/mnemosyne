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

//JDK imports
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * @author mattmann
 * @author bfoster
 * @version $Revision$
 * 
 * 
 * <p>
 * A Utility class containing methods to write and transform XML objects.
 * </p>
 */

public class XMLUtils {
    /* our log stream */
    private final static Logger LOG = Logger
            .getLogger(XMLUtils.class.getName());

    /**
     * <p>
     * This method writes a DOM document to a file
     * </p>.
     * 
     * @param doc
     *            The DOM document to write.
     * @param filename
     *            The filename to write the DOM document to.
     */
    public static void writeXmlFile(Document doc, String filename) {
        // Prepare the output file
        Result result = new StreamResult(filename);
        transform(doc, result);
    }

    public static void writeXmlToStream(Document doc, OutputStream stream) {
        Result result = new StreamResult(stream);
        transform(doc, result);
    }

    private static void transform(Document doc, Result result) {
        try {
            // Prepare the DOM document for writing
            Source source = new DOMSource(doc);

            // Write the DOM document to the file
            Transformer xformer = TransformerFactory.newInstance()
                    .newTransformer();
            xformer.setOutputProperty(OutputKeys.INDENT, "yes");
            xformer.transform(source, result);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
        }

    }

    public static List readMany(Element root, String elt) {
        return readMany(root, elt, "UTF-8");
    }

    public static List readMany(Element root, String elt, String encoding) {
        NodeList valueNodes = root.getElementsByTagName(elt);
        List values = new Vector();

        for (int i = 0; i < valueNodes.getLength(); i++) {
            Element valElem = (Element) valueNodes.item(i);
            String value;

            String rawValue = DOMUtil.getSimpleElementText(valElem);
            try {
                value = URLDecoder.decode(rawValue, encoding);
            } catch (Exception e) {
                // Dropping the element made a supplied-but-unreadable value
                // indistinguishable from one that was never supplied, and
                // silently shortened the list. URLDecoder raises on a
                // malformed escape such as a bare '%', and for such a value
                // the undecoded text is the best reading available.
                LOG.log(Level.WARNING, "Could not URL-decode tag: [" + elt
                        + "]: val: [" + rawValue + "]; keeping it undecoded. "
                        + "Message: " + e.getMessage());
                value = rawValue;
            }
            values.add(value);
        }

        return values;
    }

    public static String read(Element root, String elt) {
        return read(root, elt, "UTF-8");
    }

    public static String read(Element root, String elt, String encoding) {

        String rawValue;
        try {
            rawValue = DOMUtil.getSimpleElementText(root, elt);
        } catch (Exception e) {
            // Genuinely absent or unreadable as XML: null is the right answer.
            LOG.log(Level.WARNING, "Error reading " + elt + " from metadata. "
                    + "Message: " + e.getMessage());
            return null;
        }

        try {
            return URLDecoder.decode(rawValue, encoding);
        } catch (Exception e) {
            // Returning null here reported the element as absent when it was
            // present but undecodable, so a caller could not tell the two
            // apart. See the note in readMany above.
            LOG.log(Level.WARNING, "Could not URL-decode " + elt
                    + " from metadata; keeping it undecoded. Message: "
                    + e.getMessage());
            return rawValue;
        }
    }

    public static Element getFirstElement(String name, Element root) {
        NodeList list = root.getElementsByTagName(name);
        if (list.getLength()>0) {
            return (Element) list.item(0);
        } else {
            return null;
        }
    }

    public static String getSimpleElementText(Element node, boolean trim) {
        if (node.getChildNodes().item(0) instanceof Text) {
            String elemTxt;
            if (trim) {
                elemTxt = node.getChildNodes().item(0).getNodeValue().trim();
            } else {
                elemTxt = node.getChildNodes().item(0).getNodeValue();
            }

            return elemTxt;
        } else {
            return null;
        }
    }

    public static String getSimpleElementText(Element node) {
        return getSimpleElementText(node, false);
    }

    public static String getElementText(String elemName, Element root,
            boolean trim) {
        Element elem = getFirstElement(elemName, root);
        if (elem != null) {
            return getSimpleElementText(elem, trim);
        } else {
            return null;
        }
    }

    public static String getElementText(String elemName, Element root) {
        return getElementText(elemName, root, false);
    }

    public static Document getDocumentRoot(InputStream is) {
        // open up the XML file
        DocumentBuilderFactory factory;
        DocumentBuilder parser;
        Document document;
        InputSource inputSource;

        inputSource = new InputSource(is);

        try {
            factory = DocumentBuilderFactory.newInstance();
            parser = factory.newDocumentBuilder();
            document = parser.parse(inputSource);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Unable to parse xml stream"
                    + ": Reason is [" + e + "]");
            return null;
        }

        return document;
    }

    public static Element addNode(Document doc, Node parent, String name) {
        Element child = doc.createElement(name);
        parent.appendChild(child);
        return child;
    }

    public static void addNode(Document doc, Node parent, String name,
            String text) {
        Element child = doc.createElement(name);
        child.appendChild(doc.createTextNode(text));
        parent.appendChild(child);
    }

    public static void addNode(Document doc, Node parent, String ns,
            String name, String text, Map NS_MAP) {
        Element child = doc.createElementNS((String) NS_MAP.get(ns), ns + ":"
                + name);
        child.appendChild(doc.createTextNode(text));
        parent.appendChild(child);
    }

    public static void addAttribute(Document doc, Element node, String name,
            String value) {
        Attr attribute = doc.createAttribute(name);
        attribute.setValue(value);
        node.getAttributes().setNamedItem(attribute);
    }

}
