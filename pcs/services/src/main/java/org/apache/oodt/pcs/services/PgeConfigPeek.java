/**
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
package org.apache.oodt.pcs.services;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.oodt.cas.metadata.util.PathUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Task-page Peek of a PGE's PgeConfig.xml. Configuration already lists
 * PGETask_ConfigFilePath as a string; this reads that file so the operator
 * can see the {@code <cmd>} lines StdPGETaskInstance will put in the script.
 */
final class PgeConfigPeek {

  private static final Logger LOG = Logger.getLogger(PgeConfigPeek.class.getName());

  static final String CONFIG_FILE_PATH = "PGETask_ConfigFilePath";
  static final int MAX_BYTES = 256 * 1024;

  private PgeConfigPeek() {
  }

  static Map<String, Object> of(Map<String, String> properties) {
    if (properties == null) {
      return null;
    }
    String rawPath = properties.get(CONFIG_FILE_PATH);
    if (rawPath == null || rawPath.trim().length() == 0) {
      return null;
    }
    String path = PathUtils.replaceEnvVariables(rawPath.trim());
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("path", path);
    File file = new File(path);
    String error = unreadableReason(file);
    if (error != null) {
      row.put("error", error);
      return row;
    }
    try {
      fill(row, file);
    } catch (Exception e) {
      LOG.log(Level.FINE, "Could not peek PgeConfig.xml at " + path, e);
      row.put("error", "Could not read PgeConfig.xml: " + e.getMessage());
    }
    return row;
  }

  static String unreadableReason(File file) {
    if (file == null || !file.isFile()) {
      return "No PgeConfig.xml at this path.";
    }
    String name = file.getName();
    if (name == null || !name.toLowerCase().endsWith(".xml")) {
      return "PGETask_ConfigFilePath is not an XML file.";
    }
    long size = file.length();
    if (size <= 0) {
      return "PgeConfig.xml is empty.";
    }
    if (size > MAX_BYTES) {
      return "PgeConfig.xml is too large to peek.";
    }
    return null;
  }

  private static void fill(Map<String, Object> row, File file) throws Exception {
    byte[] bytes = Files.readAllBytes(file.toPath());
    if (bytes.length > MAX_BYTES) {
      row.put("error", "PgeConfig.xml is too large to peek.");
      return;
    }
    String xml = new String(bytes, StandardCharsets.UTF_8);
    Document document = parse(bytes);
    Element root = document.getDocumentElement();
    if (root == null || !"pgeconfig".equalsIgnoreCase(root.getTagName())) {
      row.put("error", "Not a PgeConfig.xml.");
      return;
    }
    row.put("xml", xml);
    Element exe = firstChild(root, "exe");
    if (exe == null) {
      row.put("commands", new ArrayList<String>());
      return;
    }
    String shell = attr(exe, "shell");
    if (shell.length() == 0) {
      shell = attr(exe, "shellType");
    }
    if (shell.length() > 0) {
      row.put("shell", shell);
    }
    String dir = attr(exe, "dir");
    if (dir.length() > 0) {
      row.put("dir", dir);
    }
    row.put("commands", commands(exe));
  }

  private static Document parse(byte[] bytes) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    try {
      factory.setXIncludeAware(false);
    } catch (Exception e) {
      LOG.log(Level.FINE, "Could not disable XInclude");
    }
    factory.setExpandEntityReferences(false);
    setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
    setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
    setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
    setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
  }

  private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
    try {
      factory.setFeature(name, value);
    } catch (Exception e) {
      LOG.log(Level.FINE, "XML feature not supported: " + name);
    }
  }

  private static Element firstChild(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes == null || nodes.getLength() == 0) {
      return null;
    }
    return (Element) nodes.item(0);
  }

  private static List<String> commands(Element exe) {
    List<String> out = new ArrayList<String>();
    NodeList nodes = exe.getElementsByTagName("cmd");
    if (nodes == null) {
      return out;
    }
    for (int i = 0; i < nodes.getLength(); i++) {
      String text = nodes.item(i).getTextContent();
      if (text == null) {
        continue;
      }
      String trimmed = text.trim();
      if (trimmed.length() > 0) {
        out.add(trimmed);
      }
    }
    return out;
  }

  private static String attr(Element element, String name) {
    String value = element.getAttribute(name);
    return value == null ? "" : value.trim();
  }
}
