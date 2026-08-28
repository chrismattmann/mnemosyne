// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements.  See the NOTICE.txt file distributed with this work for
// additional information regarding copyright ownership.  The ASF licenses this
// file to you under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License.  You may obtain a copy of
// the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
// License for the specific language governing permissions and limitations under
// the License.

package org.apache.oodt.pcs.input;

//OODT imports
import org.apache.oodt.commons.xml.XMLUtils;

//JDK imports
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

//Junit imports
import junit.framework.TestCase;

/**
 * <p>
 * Matrix handling in {@link PGEConfigFileWriter}, and its agreement with the two
 * readers in this package.
 * </p>
 *
 * <p>
 * The writer used to assemble each tr and td and then never attach them, so every
 * matrix reached the document as a bare <code>&lt;matrix name="..."/&gt;</code>.
 * The scalar and vector paths round trip correctly, which is why this stayed
 * hidden.
 * </p>
 */
public class PGEConfigFileMatrixTest extends TestCase {

  private static final String GROUP = "MyPGEInfo";

  /** The smallest case the property shrinks to. */
  public void testOneByOneMatrixSurvivesTheRoundTrip() throws Exception {
    PGEMatrix matrix = matrix("Tiny", Arrays.asList(Arrays.asList("55")));

    PGEMatrix read = roundTrip(matrix);

    assertEquals(1, read.getRows().size());
    assertEquals(1, read.getRows().get(0).size());
    assertEquals("55", read.getRows().get(0).get(0));
  }

  public void testMatrixValuesAndOrderSurviveTheRoundTrip() throws Exception {
    PGEMatrix matrix = matrix("TestMatrix1", Arrays.asList(
        Arrays.asList("55", "12", "1"),
        Arrays.asList("44", "33", "100")));

    PGEMatrix read = roundTrip(matrix);

    assertEquals("TestMatrix1", read.getName());
    assertEquals(2, read.getRows().size());
    assertEquals(Arrays.asList("55", "12", "1"), read.getRows().get(0));
    assertEquals(Arrays.asList("44", "33", "100"), read.getRows().get(1));
  }

  /**
   * The failure as it appeared in the file: a well-formed document, reported as
   * written successfully, containing nothing.
   */
  public void testTheWrittenMatrixIsNotEmpty() throws Exception {
    Element matrixElem = firstMatrixElement(write(matrix("M",
        Arrays.asList(Arrays.asList("a", "b")))));

    NodeList rows = matrixElem.getElementsByTagName("tr");
    assertEquals(1, rows.getLength());
    assertEquals(2, ((Element) rows.item(0)).getElementsByTagName("td").getLength());
  }

  public void testTheWrittenMatrixCarriesItsDimensions() throws Exception {
    Element matrixElem = firstMatrixElement(write(matrix("M", Arrays.asList(
        Arrays.asList("a", "b", "c"),
        Arrays.asList("d", "e", "f")))));

    assertEquals("2", matrixElem.getAttribute("rows"));
    assertEquals("3", matrixElem.getAttribute("cols"));
  }

  /**
   * PGEDataHandler required rows and cols with an unguarded parseInt, so a config
   * this project wrote failed this project's own SAX reader with a
   * NumberFormatException. The tr/td structure is what carries the dimensions.
   */
  public void testTheSaxHandlerReadsAMatrixWrittenByTheWriter() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    XMLUtils.writeXmlToStream(write(matrix("TestMatrix1", Arrays.asList(
        Arrays.asList("55", "12", "1"),
        Arrays.asList("44", "33", "100")))), out);
    String xml = out.toString("UTF-8");

    PGEMatrix read = (PGEMatrix) parseWithSax(xml).getMatrices().get("TestMatrix1");

    assertNotNull(read);
    assertEquals(2, read.getRows().size());
    assertEquals(3, read.getNumCols());
    assertEquals(Arrays.asList("55", "12", "1"), read.getRows().get(0));
    assertEquals(Arrays.asList("44", "33", "100"), read.getRows().get(1));
  }

  public void testTheSaxHandlerAcceptsAMatrixWithNoDimensionAttributes()
      throws Exception {
    String xml = "<input><group name=\"" + GROUP + "\">"
        + "<matrix name=\"NoAttrs\"><tr><td>1</td><td>2</td></tr></matrix>"
        + "</group></input>";

    PGEMatrix read = (PGEMatrix) parseWithSax(xml).getMatrices().get("NoAttrs");

    assertNotNull(read);
    assertEquals(Arrays.asList("1", "2"), read.getRows().get(0));
    assertEquals(2, read.getNumCols());
  }

  /** The dimension attributes stay supported for files that carry them. */
  public void testTheSaxHandlerStillReadsAMatrixWithDimensionAttributes()
      throws Exception {
    String xml = "<input><group name=\"" + GROUP + "\">"
        + "<matrix name=\"WithAttrs\" rows=\"2\" cols=\"2\">"
        + "<tr><td>194</td><td>192</td></tr><tr><td>1</td><td>2.2</td></tr>"
        + "</matrix></group></input>";

    PGEMatrix read = (PGEMatrix) parseWithSax(xml).getMatrices().get("WithAttrs");

    assertEquals(2, read.getRows().size());
    assertEquals(Arrays.asList("194", "192"), read.getRows().get(0));
    assertEquals(Arrays.asList("1", "2.2"), read.getRows().get(1));
  }

  private PGEMatrix matrix(String name, List<List<String>> values) {
    PGEMatrix matrix = new PGEMatrix();
    matrix.setName(name);
    for (List<String> row : values) {
      matrix.getRows().add(new Vector<Object>(row));
    }
    matrix.setNumCols(values.get(0).size());
    return matrix;
  }

  private Document write(PGEMatrix matrix) throws Exception {
    PGEGroup group = new PGEGroup(GROUP);
    group.addMatrix(matrix);

    PGEConfigurationFile configFile = new PGEConfigurationFile();
    configFile.getPgeSpecificGroups().put(GROUP, group);

    return new PGEConfigFileWriter(configFile).getConfigFileXml();
  }

  /** Writes the matrix, then reads it back with PGEConfigFileReader. */
  private PGEMatrix roundTrip(PGEMatrix matrix) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    XMLUtils.writeXmlToStream(write(matrix), out);

    PGEConfigurationFile read = new PGEConfigFileReader()
        .read(new ByteArrayInputStream(out.toByteArray()));

    PGEGroup group = read.getPgeSpecificGroups().get(GROUP);
    assertNotNull("group '" + GROUP + "' missing from the re-read file", group);
    return group.getMatrix(matrix.getName());
  }

  private PGEDataHandler parseWithSax(String xml) throws Exception {
    PGEDataHandler handler = new PGEDataHandler();
    SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
    parser.parse(new InputSource(new java.io.StringReader(xml)), handler);
    return handler;
  }

  private Element firstMatrixElement(Document document) {
    NodeList matrices = document.getElementsByTagName("matrix");
    assertEquals(1, matrices.getLength());
    return (Element) matrices.item(0);
  }
}
