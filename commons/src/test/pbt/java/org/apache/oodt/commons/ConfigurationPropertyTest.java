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

package org.apache.oodt.commons;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.xml.sax.InputSource;

/**
 * Write-then-read properties over {@link Configuration}.
 *
 * <p>{@link Configuration#toXML()} exists to write a configuration back out, so
 * the pair {@code parse} / {@code toXML} is a serialiser and the round trip is
 * the contract: an installation that saves its configuration and reloads it
 * must come back to the same configuration. Every property here builds a
 * configuration document, parses it, serialises the result, parses that, and
 * compares.
 *
 * <p>{@code Configuration} keeps some of its state in system properties — the
 * entity-reference directories in particular — so every property saves and
 * restores the ones it touches in a {@code finally} block.
 */
class ConfigurationPropertyTest {

  /** A hostname. Kept to letters and digits because it ends up inside a URL. */
  private static final Generator<String> HOST =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  /** A property key or value. */
  private static final Generator<String> WORD =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  /** A character that means something to an XML parser. */
  private static final Generator<String> AWKWARD =
      sampledFrom(List.of("<", ">", "&", "\"", "'", "%", "é", "中"));

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String doctype() {
    return "<!DOCTYPE configuration PUBLIC \"" + Configuration.DTD_FPI + "\" \""
        + Configuration.DTD_URL + "\">";
  }

  private static Configuration parse(String xml) throws Exception {
    return new Configuration(new InputSource(new StringReader(xml)));
  }

  private static void restore(String key, String original) {
    if (original == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, original);
    }
  }

  private static List<String> distinct(List<String> values) {
    Set<String> set = new LinkedHashSet<>(values);
    return new ArrayList<>(set);
  }

  /**
   * The web server address survives the round trip. This is the address every
   * other component is handed when it asks the configuration where the web
   * server is, so a host or port that changes on save-and-reload silently
   * repoints the installation.
   */
  @HegelTest(testCases = 30)
  void theWebServerAddressSurvivesTheRoundTrip(TestCase tc) throws Exception {
    String host = tc.draw(HOST, "host");
    int port = tc.draw(integers().min(1).max(65535), "port");
    String dir = tc.draw(WORD, "dir");

    String original = System.getProperty(Configuration.WEB_PROTOCOL_PROPERTY);
    try {
      String xml = doctype()
          + "<configuration>"
          + "<webServer><host>" + host + "</host><port>" + port + "</port>"
          + "<dir>/" + dir + "</dir></webServer>"
          + "<nameServer stateFrequency=\"0\"><iiop><host>" + host + "</host></iiop></nameServer>"
          + "</configuration>";

      Configuration first = parse(xml);
      Configuration second = parse(first.toXML());

      assertEquals(first.getWebServerBaseURL(), second.getWebServerBaseURL(),
          "the web server base URL changed across the round trip");
      assertEquals(first.getWebServerDocumentDirectory(), second.getWebServerDocumentDirectory(),
          "the web server document directory changed across the round trip");
      assertTrue(first.getWebServerBaseURL().endsWith(":" + port),
          "the configured port is not in the base URL: " + first.getWebServerBaseURL());
    } finally {
      restore(Configuration.WEB_PROTOCOL_PROPERTY, original);
    }
  }

  /**
   * The IIOP name server settings survive the round trip: the port a client
   * would dial, and the state-save frequency the name server runs on.
   */
  @HegelTest(testCases = 30)
  void theNameServerSettingsSurviveTheRoundTrip(TestCase tc) throws Exception {
    String host = tc.draw(HOST, "host");
    int nsPort = tc.draw(integers().min(1).max(65535), "nsPort");
    int stateFrequency = tc.draw(integers().min(0).max(1_000_000), "stateFrequency");

    String original = System.getProperty(Configuration.WEB_PROTOCOL_PROPERTY);
    try {
      String xml = doctype()
          + "<configuration>"
          + "<webServer><host>" + host + "</host><port>8080</port></webServer>"
          + "<nameServer stateFrequency=\"" + stateFrequency + "\">"
          + "<iiop><host>" + host + "</host><port>" + nsPort + "</port></iiop>"
          + "</nameServer>"
          + "</configuration>";

      Configuration first = parse(xml);
      Configuration second = parse(first.toXML());

      assertEquals(String.valueOf(nsPort), first.getNameServerPort(),
          "the configured name server port was not read");
      assertEquals(first.getNameServerPort(), second.getNameServerPort(),
          "the name server port changed across the round trip");
      assertEquals(stateFrequency, first.getNameServerStateFrequency(),
          "the configured state frequency was not read");
      assertEquals(first.getNameServerStateFrequency(), second.getNameServerStateFrequency(),
          "the state frequency changed across the round trip");
    } finally {
      restore(Configuration.WEB_PROTOCOL_PROPERTY, original);
    }
  }

  /**
   * The server manager port survives the round trip. Zero means "no server
   * manager", which {@code toXML} treats specially, so the generated range
   * starts at one.
   */
  @HegelTest(testCases = 30)
  void theServerManagerPortSurvivesTheRoundTrip(TestCase tc) throws Exception {
    String host = tc.draw(HOST, "host");
    int port = tc.draw(integers().min(1).max(65535), "port");

    String original = System.getProperty(Configuration.WEB_PROTOCOL_PROPERTY);
    try {
      String xml = doctype()
          + "<configuration>"
          + "<webServer><host>" + host + "</host><port>8080</port></webServer>"
          + "<nameServer stateFrequency=\"0\"><iiop><host>" + host + "</host></iiop></nameServer>"
          + "<serverMgr><port>" + port + "</port></serverMgr>"
          + "</configuration>";

      Configuration first = parse(xml);
      Configuration second = parse(first.toXML());

      assertEquals(port, first.getServerMgrPort(), "the configured server manager port was lost");
      assertEquals(first.getServerMgrPort(), second.getServerMgrPort(),
          "the server manager port changed across the round trip");
    } finally {
      restore(Configuration.WEB_PROTOCOL_PROPERTY, original);
    }
  }

  /**
   * Global properties survive the round trip. These are the installation's own
   * settings — every one of them is merged into the JVM's system properties at
   * start-up — so a key or value that changes shape on save-and-reload changes
   * how the installation behaves.
   */
  @HegelTest(testCases = 30)
  void globalPropertiesSurviveTheRoundTrip(TestCase tc) throws Exception {
    String host = tc.draw(HOST, "host");
    List<String> keys = distinct(tc.draw(lists(WORD).minSize(1).maxSize(5), "keys"));
    List<String> values = tc.draw(lists(WORD).minSize(1).maxSize(5), "values");

    Map<String, String> expected = new LinkedHashMap<>();
    StringBuilder propertiesXml = new StringBuilder("<properties>");
    for (int i = 0; i < keys.size(); i++) {
      String value = values.get(i % values.size());
      expected.put(keys.get(i), value);
      propertiesXml.append("<key>").append(escape(keys.get(i))).append("</key>");
      propertiesXml.append("<value>").append(escape(value)).append("</value>");
    }
    propertiesXml.append("</properties>");

    String original = System.getProperty(Configuration.WEB_PROTOCOL_PROPERTY);
    try {
      String xml = doctype()
          + "<configuration>"
          + "<webServer><host>" + host + "</host><port>8080</port></webServer>"
          + "<nameServer stateFrequency=\"0\"><iiop><host>" + host + "</host></iiop></nameServer>"
          + propertiesXml
          + "</configuration>";

      Configuration second = parse(parse(xml).toXML());

      Properties merged = new Properties();
      second.mergeProperties(merged);
      for (Map.Entry<String, String> entry : expected.entrySet()) {
        assertEquals(entry.getValue(), merged.getProperty(entry.getKey()),
            "property [" + entry.getKey() + "] changed across the round trip");
      }
    } finally {
      restore(Configuration.WEB_PROTOCOL_PROPERTY, original);
    }
  }

  /**
   * A property value containing characters that are significant to XML survives
   * the round trip. Passwords, regular expressions and file globs all live in
   * this file, and all of them contain such characters.
   */
  @HegelTest(testCases = 30)
  void significantCharactersInAPropertyValueSurvive(TestCase tc) throws Exception {
    String host = tc.draw(HOST, "host");
    String key = tc.draw(WORD, "key");
    String prefix = tc.draw(WORD, "prefix");
    String marker = tc.draw(AWKWARD, "marker");
    String suffix = tc.draw(WORD, "suffix");
    String value = prefix + marker + suffix;

    String original = System.getProperty(Configuration.WEB_PROTOCOL_PROPERTY);
    try {
      String xml = doctype()
          + "<configuration>"
          + "<webServer><host>" + host + "</host><port>8080</port></webServer>"
          + "<nameServer stateFrequency=\"0\"><iiop><host>" + host + "</host></iiop></nameServer>"
          + "<properties><key>" + escape(key) + "</key>"
          + "<value>" + escape(value) + "</value></properties>"
          + "</configuration>";

      Configuration second = parse(parse(xml).toXML());

      Properties merged = new Properties();
      second.mergeProperties(merged);
      assertEquals(value, merged.getProperty(key),
          "the property value was mangled by the round trip");
    } finally {
      restore(Configuration.WEB_PROTOCOL_PROPERTY, original);
    }
  }

  /**
   * The entity-reference directories survive the round trip.
   *
   * <p>These directories are where XML entity references are resolved, so
   * losing them turns every document that uses an entity into a parse failure
   * on the next start-up. The system property that backs them is cleared before
   * each parse so that the second read starts from the same blank state as the
   * first, and restored afterwards.
   */
  @HegelTest(testCases = 30)
  void entityReferenceDirectoriesSurviveTheRoundTrip(TestCase tc) throws Exception {
    String host = tc.draw(HOST, "host");
    List<String> dirs = distinct(tc.draw(lists(WORD).minSize(1).maxSize(4), "dirs"));

    StringBuilder dirsXml = new StringBuilder();
    List<String> expected = new ArrayList<>();
    for (String dir : dirs) {
      dirsXml.append("<dir>/").append(dir).append("</dir>");
      expected.add("/" + dir);
    }

    String originalDirs = System.getProperty(Configuration.ENTITY_DIRS_PROP);
    String originalProtocol = System.getProperty(Configuration.WEB_PROTOCOL_PROPERTY);
    try {
      String xml = doctype()
          + "<configuration>"
          + "<webServer><host>" + host + "</host><port>8080</port></webServer>"
          + "<nameServer stateFrequency=\"0\"><iiop><host>" + host + "</host></iiop></nameServer>"
          + "<xml><entityRef>" + dirsXml + "</entityRef></xml>"
          + "</configuration>";

      System.clearProperty(Configuration.ENTITY_DIRS_PROP);
      Configuration first = parse(xml);
      assertEquals(expected, first.getEntityRefDirs(),
          "the configured entity reference directories were not read");
      String written = first.toXML();

      System.clearProperty(Configuration.ENTITY_DIRS_PROP);
      Configuration second = parse(written);

      assertEquals(expected, second.getEntityRefDirs(),
          "the entity reference directories changed across the round trip");
    } finally {
      restore(Configuration.ENTITY_DIRS_PROP, originalDirs);
      restore(Configuration.WEB_PROTOCOL_PROPERTY, originalProtocol);
    }
  }

  /**
   * Merging never overwrites a setting the target already holds, and supplies
   * every setting it does not. This is the contract the method documents, and
   * the reason command-line overrides beat the configuration file.
   */
  @HegelTest(testCases = 30)
  void mergingFillsGapsWithoutOverwriting(TestCase tc) throws Exception {
    String host = tc.draw(HOST, "host");
    List<String> keys = distinct(tc.draw(lists(WORD).minSize(1).maxSize(5), "keys"));
    String preset = tc.draw(WORD, "preset");
    int presetIndex = tc.draw(integers().min(0).max(4), "presetIndex");

    StringBuilder propertiesXml = new StringBuilder("<properties>");
    for (String key : keys) {
      propertiesXml.append("<key>").append(escape(key)).append("</key>");
      propertiesXml.append("<value>fromFile</value>");
    }
    propertiesXml.append("</properties>");

    String original = System.getProperty(Configuration.WEB_PROTOCOL_PROPERTY);
    try {
      String xml = doctype()
          + "<configuration>"
          + "<webServer><host>" + host + "</host><port>8080</port></webServer>"
          + "<nameServer stateFrequency=\"0\"><iiop><host>" + host + "</host></iiop></nameServer>"
          + propertiesXml
          + "</configuration>";

      Configuration configuration = parse(xml);

      String alreadySet = keys.get(presetIndex % keys.size());
      Properties target = new Properties();
      target.setProperty(alreadySet, preset);

      configuration.mergeProperties(target);

      assertEquals(preset, target.getProperty(alreadySet),
          "merging overwrote a setting the caller had already made");
      for (String key : keys) {
        if (!key.equals(alreadySet)) {
          assertEquals("fromFile", target.getProperty(key),
              "merging did not supply [" + key + "]");
        }
      }
    } finally {
      restore(Configuration.WEB_PROTOCOL_PROPERTY, original);
    }
  }
}
