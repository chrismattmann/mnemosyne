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
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Properties of {@link PGEDataHandler}, the SAX reader for PGE input files.
 *
 * <p>The handler flattens a document into three maps — scalars, vectors and
 * matrices — keyed by name. Every property here parses a document built to the
 * shape the handler documents and asks for the values back, entirely in
 * memory.
 */
class PGEDataHandlerPropertyTest {

  /** A configuration value. No markup characters, so no escaping is involved. */
  private static final Generator<String> VALUE =
      text().minSize(0).maxSize(16).categories("Lu", "Ll", "Nd");

  private static PGEDataHandler parse(String xml) throws Exception {
    SAXParserFactory factory = SAXParserFactory.newInstance();
    SAXParser parser = factory.newSAXParser();
    PGEDataHandler handler = new PGEDataHandler();
    parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), handler);
    return handler;
  }

  /** Every scalar in the document is readable afterwards under its own name. */
  @HegelTest
  void everyScalarIsReadBackUnderItsName(TestCase tc) throws Exception {
    List<String> values = tc.draw(lists(VALUE).minSize(0).maxSize(12), "values");

    StringBuilder xml = new StringBuilder("<input><group name=\"G\">");
    for (int i = 0; i < values.size(); i++) {
      xml.append("<scalar name=\"s").append(i).append("\">").append(values.get(i))
          .append("</scalar>");
    }
    xml.append("</group></input>");

    PGEDataHandler handler = parse(xml.toString());

    assertEquals(values.size(), handler.getScalars().size(), "a scalar went missing");
    for (int i = 0; i < values.size(); i++) {
      PGEScalar scalar = (PGEScalar) handler.getScalars().get("s" + i);
      assertNotNull(scalar, "scalar s" + i + " went missing");
      assertEquals(values.get(i), scalar.getValue(), "scalar s" + i + " changed");
    }
  }

  /**
   * A vector is read back with its elements in document order. The order is
   * the whole content of a vector — element three of a PGE's input list has to
   * still be element three.
   */
  @HegelTest
  void vectorElementsKeepTheirOrder(TestCase tc) throws Exception {
    List<String> elements = tc.draw(lists(VALUE).minSize(1).maxSize(10), "elements");

    StringBuilder xml = new StringBuilder("<input><group name=\"G\"><vector name=\"v\">");
    for (String element : elements) {
      xml.append("<element>").append(element).append("</element>");
    }
    xml.append("</vector></group></input>");

    PGEDataHandler handler = parse(xml.toString());

    PGEVector vector = (PGEVector) handler.getVectors().get("v");
    assertNotNull(vector, "the vector went missing");
    assertEquals(elements, vector.getElements());
  }

  /**
   * A matrix is read back cell for cell, at the row and column it occupied in
   * the document.
   */
  @HegelTest
  void matrixCellsKeepTheirPosition(TestCase tc) throws Exception {
    int rows = tc.draw(integers().min(1).max(5), "rows");
    int cols = tc.draw(integers().min(1).max(5), "cols");

    StringBuilder xml = new StringBuilder("<input><group name=\"G\"><matrix name=\"m\" rows=\"")
        .append(rows).append("\" cols=\"").append(cols).append("\">");
    for (int row = 0; row < rows; row++) {
      xml.append("<tr>");
      for (int col = 0; col < cols; col++) {
        xml.append("<td>").append(row).append('x').append(col).append("</td>");
      }
      xml.append("</tr>");
    }
    xml.append("</matrix></group></input>");

    PGEDataHandler handler = parse(xml.toString());

    PGEMatrix matrix = (PGEMatrix) handler.getMatrices().get("m");
    assertNotNull(matrix, "the matrix went missing");
    assertEquals(rows, matrix.getRows().size(), "the matrix lost rows");
    assertEquals(cols, matrix.getNumCols(), "the matrix lost columns");
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        assertEquals(row + "x" + col, matrix.getValue(row, col),
            "cell (" + row + "," + col + ") changed");
      }
    }
  }

  /**
   * The three kinds of value do not interfere: a document holding all of them
   * yields all of them, each in its own map.
   */
  @HegelTest
  void scalarsVectorsAndMatricesDoNotInterfere(TestCase tc) throws Exception {
    int scalarCount = tc.draw(integers().min(0).max(4), "scalarCount");
    int vectorCount = tc.draw(integers().min(0).max(4), "vectorCount");
    int matrixCount = tc.draw(integers().min(0).max(4), "matrixCount");
    String value = tc.draw(VALUE, "value");

    StringBuilder xml = new StringBuilder("<input><group name=\"G\">");
    for (int i = 0; i < scalarCount; i++) {
      xml.append("<scalar name=\"s").append(i).append("\">").append(value).append("</scalar>");
    }
    for (int i = 0; i < vectorCount; i++) {
      xml.append("<vector name=\"v").append(i).append("\"><element>").append(value)
          .append("</element></vector>");
    }
    for (int i = 0; i < matrixCount; i++) {
      xml.append("<matrix name=\"m").append(i).append("\" rows=\"1\" cols=\"1\"><tr><td>")
          .append(value).append("</td></tr></matrix>");
    }
    xml.append("</group></input>");

    PGEDataHandler handler = parse(xml.toString());

    assertEquals(scalarCount, handler.getScalars().size());
    assertEquals(vectorCount, handler.getVectors().size());
    assertEquals(matrixCount, handler.getMatrices().size());
  }
}
