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
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

//Junit imports
import junit.framework.TestCase;

/**
 * <p>
 * End-to-end behaviour of {@link PGEConfigFileWriter}'s urlEncoding option.
 * </p>
 *
 * <p>
 * The writer has always been able to URL-encode what it writes, but nothing in
 * this package ever decoded, so setUrlEncoding(true) produced a file that no
 * reader here could read back. The writer now records the choice on the root
 * element and both readers honour it.
 * </p>
 */
public class PGEConfigFileUrlEncodingTest extends TestCase {

  private static final String GROUP = "MyPGEInfo";

  /** A space survives without encoding, so it does not discriminate. */
  private static final String AWKWARD = "a b&c/d?e=f+g";

  public void testAnEncodedScalarRoundTrips() throws Exception {
    PGEConfigurationFile read = roundTrip(true);

    assertEquals(AWKWARD, read.getPgeSpecificGroups().get(GROUP)
        .getScalar("Awkward").getValue());
  }

  public void testAnEncodedVectorRoundTrips() throws Exception {
    PGEConfigurationFile read = roundTrip(true);

    List elements = read.getPgeSpecificGroups().get(GROUP)
        .getVector("Vec").getElements();
    assertEquals(2, elements.size());
    assertEquals(AWKWARD, elements.get(0));
    assertEquals("plain", elements.get(1));
  }

  public void testAnEncodedMatrixRoundTrips() throws Exception {
    PGEConfigurationFile read = roundTrip(true);

    PGEMatrix matrix = read.getPgeSpecificGroups().get(GROUP).getMatrix("Mat");
    assertEquals(Arrays.asList(AWKWARD, "plain"), matrix.getRows().get(0));
  }

  /** The unencoded path is the default and must be untouched. */
  public void testAnUnencodedFileStillRoundTrips() throws Exception {
    PGEConfigurationFile read = roundTrip(false);

    assertEquals(AWKWARD, read.getPgeSpecificGroups().get(GROUP)
        .getScalar("Awkward").getValue());
  }

  public void testTheEncodingIsRecordedOnlyWhenUsed() throws Exception {
    assertEquals("true", write(true).getDocumentElement()
        .getAttribute("urlEncoding"));
    assertEquals("", write(false).getDocumentElement()
        .getAttribute("urlEncoding"));
  }

  /** The value really is encoded on disk, not merely round-tripping by luck. */
  public void testTheValueIsActuallyEncodedInTheFile() throws Exception {
    String xml = asString(write(true));

    assertTrue("expected an encoded value in: " + xml,
        xml.contains("a+b%26c%2Fd%3Fe%3Df%2Bg"));
    assertFalse(xml.contains(">" + AWKWARD + "<"));
  }

  /** The SAX reader reads the same file, and honours the same marker. */
  public void testTheSaxHandlerDecodesAnEncodedFile() throws Exception {
    PGEDataHandler handler = parseWithSax(asString(write(true)));

    assertEquals(AWKWARD,
        ((PGEScalar) handler.getScalars().get("Awkward")).getValue());
    assertEquals(AWKWARD,
        ((PGEVector) handler.getVectors().get("Vec")).getElements().get(0));
    assertEquals(AWKWARD,
        ((PGEMatrix) handler.getMatrices().get("Mat")).getRows().get(0).get(0));
  }

  public void testTheSaxHandlerLeavesAnUnencodedFileAlone() throws Exception {
    PGEDataHandler handler = parseWithSax(asString(write(false)));

    assertEquals(AWKWARD,
        ((PGEScalar) handler.getScalars().get("Awkward")).getValue());
  }

  /**
   * A document with no marker holds literal values, so a percent sign in one of
   * them must not be treated as an escape. This is what makes the attribute
   * worth carrying rather than always decoding.
   */
  public void testALiteralPercentInAnUnmarkedFileIsLeftAlone() throws Exception {
    String xml = "<input><group name=\"" + GROUP + "\">"
        + "<scalar name=\"Pct\">100% done</scalar></group></input>";

    PGEConfigurationFile read = new PGEConfigFileReader()
        .read(new ByteArrayInputStream(xml.getBytes("UTF-8")));

    assertEquals("100% done", read.getPgeSpecificGroups().get(GROUP)
        .getScalar("Pct").getValue());
  }

  /** A malformed escape in a marked file keeps the value rather than losing it. */
  public void testAMalformedEscapeIsKeptUndecoded() throws Exception {
    String xml = "<input urlEncoding=\"true\"><group name=\"" + GROUP + "\">"
        + "<scalar name=\"Bad\">100%</scalar></group></input>";

    PGEConfigurationFile read = new PGEConfigFileReader()
        .read(new ByteArrayInputStream(xml.getBytes("UTF-8")));

    assertEquals("100%", read.getPgeSpecificGroups().get(GROUP)
        .getScalar("Bad").getValue());
  }

  private Document write(boolean urlEncoding) throws Exception {
    PGEGroup group = new PGEGroup(GROUP);
    group.addScalar(new PGEScalar("Awkward", AWKWARD));

    PGEVector vector = new PGEVector();
    vector.setName("Vec");
    vector.getElements().add(AWKWARD);
    vector.getElements().add("plain");
    group.addVector(vector);

    PGEMatrix matrix = new PGEMatrix();
    matrix.setName("Mat");
    matrix.getRows().add(new Vector<Object>(Arrays.asList(AWKWARD, "plain")));
    matrix.setNumCols(2);
    group.addMatrix(matrix);

    PGEConfigurationFile configFile = new PGEConfigurationFile();
    configFile.getPgeSpecificGroups().put(GROUP, group);

    PGEConfigFileWriter writer = new PGEConfigFileWriter(configFile);
    writer.setUrlEncoding(urlEncoding);
    return writer.getConfigFileXml();
  }

  private PGEConfigurationFile roundTrip(boolean urlEncoding) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    XMLUtils.writeXmlToStream(write(urlEncoding), out);
    return new PGEConfigFileReader()
        .read(new ByteArrayInputStream(out.toByteArray()));
  }

  private String asString(Document document) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    XMLUtils.writeXmlToStream(document, out);
    return out.toString("UTF-8");
  }

  private PGEDataHandler parseWithSax(String xml) throws Exception {
    PGEDataHandler handler = new PGEDataHandler();
    SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
    parser.parse(new InputSource(new StringReader(xml)), handler);
    return handler;
  }
}
