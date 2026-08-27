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

package org.apache.oodt.pcs.input;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;

/**
 * Write-then-read properties over {@link PGEConfigFileWriter} and
 * {@link PGEConfigFileReader}.
 *
 * <p>The pair is a serialiser: PCS builds a {@link PGEConfigurationFile} in
 * memory, writes it out for a PGE to consume, and reads such files back when
 * inspecting a run. What was written therefore has to be what is read. The
 * round trip is done entirely in memory — the writer hands back a DOM
 * {@link Document}, which is serialised to bytes and fed straight to the
 * reader — so nothing here touches the filesystem.
 */
class PGEConfigFileRoundTripPropertyTest {

  /** A configuration key, as a PGE author would write it. */
  private static final Generator<String> NAME =
      text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");

  /** A configuration value. Empty is allowed: a blank setting is still a setting. */
  private static final Generator<String> VALUE =
      text().minSize(0).maxSize(16).categories("Lu", "Ll", "Nd");

  private static PGEConfigurationFile readBack(PGEConfigurationFile original) throws Exception {
    Document document = new PGEConfigFileWriter(original).getConfigFileXml();

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.transform(new DOMSource(document), new StreamResult(bytes));

    PGEConfigurationFile restored =
        new PGEConfigFileReader().read(new ByteArrayInputStream(bytes.toByteArray()));
    assertNotNull(restored, "the reader could not parse what the writer produced");
    return restored;
  }

  /**
   * A configuration file made of the settings PCS actually writes — the PGE
   * name, the product path, the monitor location and format, the input
   * product files, and any number of PGE-specific groups of scalars and
   * vectors — comes back from the file exactly as it went in.
   */
  @HegelTest
  void scalarsAndVectorsSurviveTheRoundTrip(TestCase tc) throws Exception {
    String pgeName = tc.draw(NAME, "pgeName");
    String productPath = tc.draw(VALUE, "productPath");
    String monitorPath = tc.draw(VALUE, "monitorPath");
    String monitorFormat = tc.draw(VALUE, "monitorFormat");
    List<String> inputNames = tc.draw(lists(NAME).minSize(0).maxSize(6), "inputNames");
    List<String> inputValues = tc.draw(lists(VALUE).minSize(0).maxSize(6), "inputValues");
    int groupCount = tc.draw(integers().min(0).max(3), "groupCount");
    List<String> customNames = tc.draw(lists(NAME).minSize(0).maxSize(6), "customNames");
    List<String> customValues = tc.draw(lists(VALUE).minSize(0).maxSize(6), "customValues");
    List<String> vectorElements = tc.draw(lists(VALUE).minSize(1).maxSize(4), "vectorElements");

    PGEConfigurationFile original = new PGEConfigurationFile();
    original.setPgeName(new PGEScalar("PGEName", pgeName));
    original.setProductPath(new PGEScalar("ProductPath", productPath));
    original.setMonitorPath(new PGEScalar("MonitorPath", monitorPath));
    original.setMonitorFilenameFormat(new PGEScalar("MonitorFilenameFormat", monitorFormat));

    for (int i = 0; i < inputNames.size(); i++) {
      original.getInputProductFiles().addScalar(
          new PGEScalar(inputNames.get(i), i < inputValues.size() ? inputValues.get(i) : ""));
    }

    // What the group is expected to hold once duplicate names have collapsed.
    Map<String, String> expectedInputs = new LinkedHashMap<>();
    for (String name : original.getInputProductFiles().getScalars().keySet()) {
      expectedInputs.put(name, original.getInputProductFiles().getScalar(name).getValue());
    }

    Map<String, Map<String, String>> expectedCustom = new LinkedHashMap<>();
    for (int g = 0; g < groupCount; g++) {
      PGEGroup group = new PGEGroup("Custom" + g);
      for (int i = 0; i < customNames.size(); i++) {
        group.addScalar(
            new PGEScalar(customNames.get(i), i < customValues.size() ? customValues.get(i) : ""));
      }
      group.addVector(new PGEVector("Vec" + g, new ArrayList<Object>(vectorElements)));
      original.getPgeSpecificGroups().put(group.getName(), group);

      Map<String, String> expected = new LinkedHashMap<>();
      for (String name : group.getScalars().keySet()) {
        expected.put(name, group.getScalar(name).getValue());
      }
      expectedCustom.put(group.getName(), expected);
    }

    PGEConfigurationFile restored = readBack(original);

    assertEquals(pgeName, restored.getPgeName().getValue(), "the PGE name changed");
    assertEquals(productPath, restored.getProductPath().getValue(), "the product path changed");
    assertEquals(monitorPath, restored.getMonitorPath().getValue(), "the monitor path changed");
    assertEquals(monitorFormat, restored.getMonitorFilenameFormat().getValue(),
        "the monitor filename format changed");

    assertEquals(expectedInputs.size(), restored.getInputProductFiles().getNumScalars(),
        "the input product files group changed size");
    for (Map.Entry<String, String> entry : expectedInputs.entrySet()) {
      assertEquals(entry.getValue(),
          restored.getInputProductFiles().getScalar(entry.getKey()).getValue(),
          "input product file [" + entry.getKey() + "] changed");
    }

    assertEquals(expectedCustom.size(), restored.getPgeSpecificGroups().size(),
        "a PGE-specific group went missing");
    for (Map.Entry<String, Map<String, String>> group : expectedCustom.entrySet()) {
      PGEGroup restoredGroup = restored.getPgeSpecificGroups().get(group.getKey());
      assertNotNull(restoredGroup, "group [" + group.getKey() + "] went missing");
      for (Map.Entry<String, String> entry : group.getValue().entrySet()) {
        assertEquals(entry.getValue(), restoredGroup.getScalar(entry.getKey()).getValue(),
            "scalar [" + entry.getKey() + "] in group [" + group.getKey() + "] changed");
      }
      assertEquals(vectorElements, restoredGroup.getVector("Vec" + group.getKey().substring(6))
          .getElements(), "the vector in group [" + group.getKey() + "] changed");
    }
  }

  /**
   * A matrix survives the round trip too. {@link PGEMatrix} is one of the
   * three value shapes a PGE configuration file is defined to carry, the
   * reader knows how to parse one, and a PGE handed a configuration file is
   * entitled to find in it whatever PCS put there.
   */
  @HegelTest
  void matricesSurviveTheRoundTrip(TestCase tc) throws Exception {
    int rows = tc.draw(integers().min(1).max(4), "rows");
    int cols = tc.draw(integers().min(1).max(4), "cols");

    PGEMatrix matrix = new PGEMatrix("Coefficients", rows, cols);
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        matrix.addValue(row + "x" + col, row, col);
      }
    }

    PGEConfigurationFile original = new PGEConfigurationFile();
    PGEGroup group = new PGEGroup("CustomMatrixGroup");
    group.addMatrix(matrix);
    original.getPgeSpecificGroups().put(group.getName(), group);

    PGEConfigurationFile restored = readBack(original);

    PGEGroup restoredGroup = restored.getPgeSpecificGroups().get("CustomMatrixGroup");
    assertNotNull(restoredGroup, "the group holding the matrix went missing");
    PGEMatrix restoredMatrix = restoredGroup.getMatrix("Coefficients");
    assertNotNull(restoredMatrix, "the matrix went missing");

    assertEquals(rows, restoredMatrix.getRows().size(), "the matrix lost rows");
    assertEquals(cols, restoredMatrix.getNumCols(), "the matrix lost columns");
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        assertEquals(row + "x" + col, restoredMatrix.getValue(row, col),
            "cell (" + row + "," + col + ") changed");
      }
    }
  }

  /**
   * The monitor level settings — the per-level verbosity a PGE is run at —
   * come back as they were written.
   */
  @HegelTest
  void monitorLevelsSurviveTheRoundTrip(TestCase tc) throws Exception {
    List<String> levelNames = tc.draw(lists(NAME).minSize(0).maxSize(6), "levelNames");
    List<String> levelValues = tc.draw(lists(VALUE).minSize(0).maxSize(6), "levelValues");

    PGEConfigurationFile original = new PGEConfigurationFile();
    for (int i = 0; i < levelNames.size(); i++) {
      original.getMonitorLevelGroup().addScalar(
          new PGEScalar(levelNames.get(i), i < levelValues.size() ? levelValues.get(i) : ""));
    }

    Map<String, String> expected = new LinkedHashMap<>();
    for (String name : original.getMonitorLevelGroup().getScalars().keySet()) {
      expected.put(name, original.getMonitorLevelGroup().getScalar(name).getValue());
    }

    PGEConfigurationFile restored = readBack(original);

    assertEquals(expected.size(), restored.getMonitorLevelGroup().getNumScalars());
    for (Map.Entry<String, String> entry : expected.entrySet()) {
      assertEquals(entry.getValue(),
          restored.getMonitorLevelGroup().getScalar(entry.getKey()).getValue(),
          "monitor level [" + entry.getKey() + "] changed");
    }
  }
}
