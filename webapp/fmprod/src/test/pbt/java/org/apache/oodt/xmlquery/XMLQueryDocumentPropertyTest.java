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

package org.apache.oodt.xmlquery;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Round-trip properties for the XML document form of an {@link XMLQuery}.
 *
 * <p>A query travels between a client and a product server as this document and
 * nothing else: {@link XMLQuery#getXMLDocString} writes it, the
 * {@link XMLQuery#XMLQuery(String) string constructor} reads it back. Whatever
 * does not survive that trip is a part of the query the server never sees, and
 * the loss is silent — the document parses, the query is well formed, it just
 * asks for something other than what was asked.
 *
 * <p>Two strengths of property are stated, and the difference between them is
 * deliberate. Ordinary values must survive unchanged. Awkward ones — leading
 * space, an embedded newline — must at least reach a fixed point after one
 * trip, because a query is relayed more than once in a federated search and a
 * value that shifted a little on each hop would drift without bound. The
 * document format normalises whitespace on the way in (see
 * {@code XML.unwrappedText}), so a value carrying its own is changed once and
 * then held; that is the format's own rule rather than a fault in this class,
 * and the two properties say so by dividing along that line.
 *
 * <p>Characters that XML 1.0 cannot represent at all — the C0 controls other
 * than tab, carriage return and newline — are excluded from the generators.
 * They do not round-trip; they make {@link XMLQuery#getXMLDocString} throw an
 * {@code IllegalStateException}, which is noted here rather than asserted,
 * because a format that cannot hold a character is entitled to say so.
 */
class XMLQueryDocumentPropertyTest {

  /** Ordinary text: no leading or trailing space, no line breaks, no doubled blanks. */
  private static Generator<String> ordinaryText() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  private static final List<String> ASCII_LETTERS =
      Arrays.asList("a", "b", "c", "d", "e", "F", "G", "H", "i", "j", "k", "Z");

  /**
   * A word out of the alphabet the keyword tokeniser reads as one token, so
   * that a property about the document is not answered by the parser having
   * dropped a term it could not lex.
   */
  private static Generator<String> asciiWord() {
    return lists(sampledFrom(ASCII_LETTERS))
        .minSize(1)
        .maxSize(8)
        .map(letters -> String.join("", letters));
  }

  /**
   * Text with one character in it that XML gives a meaning to. A query title or
   * description really does contain ampersands and apostrophes, and those are
   * exactly the characters an escaping mistake loses.
   */
  private static String drawMarkupText(TestCase tc, String label) {
    String before = tc.draw(ordinaryText(), label + ".before");
    String special =
        tc.draw(
            sampledFrom(Arrays.asList("&", "<", ">", "\"", "'", "]]>", "é", "中", "-")),
            label + ".special");
    String after = tc.draw(ordinaryText(), label + ".after");
    return before + special + after;
  }

  /** Text that also carries whitespace the document format is entitled to normalise. */
  private static String drawAwkwardText(TestCase tc, String label) {
    String before =
        tc.draw(sampledFrom(Arrays.asList("", " ", "  ", "\n", "\t")), label + ".lead");
    String body = drawMarkupText(tc, label + ".body");
    String middle =
        tc.draw(sampledFrom(Arrays.asList("", " ", "\n", "\n  ", "\t", "  ")), label + ".middle");
    String tail = tc.draw(ordinaryText(), label + ".tail");
    String after =
        tc.draw(sampledFrom(Arrays.asList("", " ", "\n", "\t")), label + ".trail");
    return before + body + middle + tail + after;
  }

  /** A MIME type as a client writes one in an Accept list. */
  private static Generator<String> mimeTypes() {
    return sampledFrom(
        Arrays.asList(
            "*/*",
            "text/plain",
            "text/xml",
            "image/gif",
            "image/jpeg",
            "application/octet-stream",
            "application/vnd.jpl.large-product"));
  }

  private static XMLQuery queryWith(
      String keywordQuery, String id, String title, String desc, String ddId, int maxResults,
      List<String> mimeAccept) {
    return new XMLQuery(
        keywordQuery, id, title, desc, ddId, "ATTRIBUTE", "BROADCAST", "N/A", maxResults,
        new ArrayList<String>(mimeAccept), true);
  }

  private static XMLQuery throughTheDocument(XMLQuery query) throws Exception {
    return new XMLQuery(query.getXMLDocString());
  }

  /**
   * A query built from ordinary values is the same query after being written
   * out and read back. This is the contract every product server depends on:
   * the object the server handles is the object the client constructed.
   */
  @HegelTest
  void aQueryIsUnchangedByItsDocument(TestCase tc) throws Exception {
    XMLQuery original =
        queryWith(
            tc.draw(ordinaryText(), "keywordQuery") + " EQ " + tc.draw(ordinaryText(), "literal"),
            tc.draw(ordinaryText(), "id"),
            drawMarkupText(tc, "title"),
            drawMarkupText(tc, "description"),
            tc.draw(ordinaryText(), "ddId"),
            tc.draw(integers().min(0).max(100000), "maxResults"),
            Arrays.asList("*/*"));

    XMLQuery reread = throughTheDocument(original);

    assertEquals(original.getKwdQueryString(), reread.getKwdQueryString(), "the query text changed");
    assertEquals(
        original.getQueryHeader().getTitle(), reread.getQueryHeader().getTitle(),
        "the title changed");
    assertEquals(
        original.getQueryHeader().getDescription(), reread.getQueryHeader().getDescription(),
        "the description changed");
    assertEquals(original.getMaxResults(), reread.getMaxResults(), "the result limit changed");
    assertEquals(original, reread, "the query is not equal to itself after a round trip");
    assertEquals(original.hashCode(), reread.hashCode(), "equal queries hash differently");
  }

  /**
   * However a value is written, one trip through the document settles it: the
   * second document is byte-for-byte the first. A query relayed on through a
   * federation must not drift a little further on every hop.
   */
  @HegelTest
  void theDocumentIsAFixedPointAfterOneRoundTrip(TestCase tc) throws Exception {
    XMLQuery original =
        queryWith(
            drawAwkwardText(tc, "keywordQuery"),
            drawAwkwardText(tc, "id"),
            drawAwkwardText(tc, "title"),
            drawAwkwardText(tc, "description"),
            drawAwkwardText(tc, "ddId"),
            tc.draw(integers().min(0).max(100000), "maxResults"),
            Arrays.asList("*/*"));

    String once = throughTheDocument(original).getXMLDocString();
    String twice = new XMLQuery(once).getXMLDocString();

    assertEquals(once, twice, "the document was still changing after a second round trip");
  }

  /**
   * The list of MIME types a client will accept survives, in order. It is how
   * the client says what it can decode; a server given a shortened list sends
   * back something the client cannot read, and one given a reordered list
   * chooses differently.
   */
  @HegelTest
  void theAcceptedMimeTypesSurviveTheRoundTrip(TestCase tc) throws Exception {
    List<String> accepted =
        tc.draw(lists(mimeTypes()).minSize(1).maxSize(5), "mimeAccept");

    XMLQuery original =
        queryWith("A EQ 1", "id", "title", "description", "dd", 42, accepted);

    assertEquals(
        accepted,
        new ArrayList<String>(throughTheDocument(original).getMimeAccept()),
        "the accepted MIME types changed");
  }

  /**
   * The parsed query — the where clause the catalogue actually executes —
   * survives the trip. The server does not re-parse the keyword string; it
   * reads the element sets out of the document, so these are the query.
   */
  @HegelTest
  void theParsedClausesSurviveTheRoundTrip(TestCase tc) throws Exception {
    String left = tc.draw(asciiWord(), "left");
    String right = tc.draw(asciiWord(), "right");
    String literal = tc.draw(asciiWord(), "literal");
    String returned = tc.draw(asciiWord(), "returned");
    String operator = tc.draw(sampledFrom(Arrays.asList("EQ", "LT", "GE", "LIKE")), "operator");
    String join = tc.draw(sampledFrom(Arrays.asList("AND", "OR")), "join");

    String written =
        left + " " + operator + " " + literal + " " + join + " " + right + " EQ " + literal
            + " AND RETURN EQ " + returned;
    tc.note("query = [" + written + "]");

    XMLQuery original =
        queryWith(written, "id", "title", "description", "dd", 42, Arrays.asList("*/*"));
    XMLQuery reread = throughTheDocument(original);

    assertEquals(
        original.getWhereElementSet(), reread.getWhereElementSet(), "the where clause changed");
    assertEquals(
        original.getSelectElementSet(), reread.getSelectElementSet(), "the select clause changed");
    assertEquals(
        original.getFromElementSet(), reread.getFromElementSet(), "the from clause changed");
  }

  /**
   * A textual result carried on the query reaches the other side unchanged.
   * This is the answer, not the question: a product server puts what it found
   * into the same document and sends it back, so a value mangled here is data
   * the caller never receives correctly.
   */
  @HegelTest
  void aTextualResultSurvivesTheRoundTrip(TestCase tc) throws Exception {
    String resultId = tc.draw(ordinaryText(), "resultId");
    String profileId = tc.draw(ordinaryText(), "profileId");
    String resourceId = tc.draw(ordinaryText(), "resourceId");
    String value = drawMarkupText(tc, "value");
    boolean classified = tc.draw(booleans(), "classified");
    long validity = tc.draw(longs().min(0).max(1000000), "validity");

    Result result =
        new Result(
            resultId, "text/plain", profileId, resourceId, new ArrayList<Object>(), value,
            classified, validity);

    XMLQuery original =
        queryWith("A EQ 1", "id", "title", "description", "dd", 42, Arrays.asList("*/*"));
    original.getResult().getList().add(result);

    XMLQuery reread = throughTheDocument(original);

    assertEquals(1, reread.getResults().size(), "the result did not come back");
    Result back = (Result) reread.getResults().get(0);
    assertEquals(resultId, back.getID(), "the result id changed");
    assertEquals("text/plain", back.getMimeType(), "the MIME type changed");
    assertEquals(profileId, back.getProfileID(), "the profile id changed");
    assertEquals(resourceId, back.getResourceID(), "the resource id changed");
    assertEquals(value, back.getValue(), "the result value changed");
    assertEquals(classified, back.isClassified(), "the classification changed");
    assertEquals(validity, back.getValidity(), "the validity changed");
    assertEquals(original, reread, "the query carrying the result is no longer equal to itself");
  }

  /**
   * The per-server timings a federated query accumulates survive the trip.
   * They are collected on the way home and are the only record of where the
   * time went.
   */
  @HegelTest
  void theStatisticsSurviveTheRoundTrip(TestCase tc) throws Exception {
    List<String> urls = tc.draw(lists(ordinaryText()).minSize(1).maxSize(4), "urls");
    List<Long> times = new ArrayList<Long>();
    for (int i = 0; i < urls.size(); ++i) {
      times.add(tc.draw(longs().min(0).max(1000000), "time" + i));
    }

    XMLQuery original =
        queryWith("A EQ 1", "id", "title", "description", "dd", 42, Arrays.asList("*/*"));
    for (int i = 0; i < urls.size(); ++i) {
      original.getStatistics().add(
          new Statistic("http://" + urls.get(i) + "/", times.get(i).longValue()));
    }

    List<?> back = throughTheDocument(original).getStatistics();

    assertEquals(urls.size(), back.size(), "a statistic went missing");
    for (int i = 0; i < urls.size(); ++i) {
      Statistic statistic = (Statistic) back.get(i);
      assertNotNull(statistic, "a statistic came back null");
      assertEquals("http://" + urls.get(i) + "/", statistic.getURL(), "a server URL changed");
      assertEquals(times.get(i).longValue(), statistic.getTime(), "a search time changed");
    }
  }

  /**
   * A query reports back the routing instructions it was built with. A
   * federating server reads these to decide whether to pass the query on and
   * how far; a query that misreports them is broadcast when it should not be.
   */
  @HegelTest
  void aQueryReportsTheRoutingItWasBuiltWith(TestCase tc) {
    String resultMode =
        tc.draw(sampledFrom(Arrays.asList("ATTRIBUTE", "INSTANCE", "PROFILE", "CLASS")), "mode");
    String propagationType =
        tc.draw(sampledFrom(Arrays.asList("BROADCAST", "PROPOGATE")), "propagationType");
    String propagationLevels =
        tc.draw(sampledFrom(Arrays.asList("N/A", "1", "2", "7")), "propagationLevels");

    XMLQuery query =
        new XMLQuery(
            "A EQ 1", "id", "title", "description", "dd", resultMode, propagationType,
            propagationLevels, 42, null, true);

    assertEquals(resultMode, query.getResultModeID(), "the result mode changed");
    assertEquals(propagationType, query.getPropagationType(), "the propagation type changed");
    assertEquals(
        propagationLevels, query.getPropagationLevels(), "the propagation levels changed");
    assertEquals(
        Arrays.asList("*/*"),
        new ArrayList<Object>(query.getMimeAccept()),
        "a query built without an Accept list did not default to accepting anything");
  }

  /**
   * A clone of a query is equal to it, and a where clause replaced wholesale is
   * the where clause the query then has. A federating server rewrites the
   * clause of a copy before passing it on, which is why both of these exist.
   */
  @HegelTest
  void aClonedQueryIsEqualToItsOriginalUntilItIsChanged(TestCase tc) {
    String name = tc.draw(asciiWord(), "name");
    String literal = tc.draw(asciiWord(), "literal");
    String replacement = tc.draw(asciiWord(), "replacement");

    XMLQuery original =
        queryWith(
            name + " EQ " + literal, "id", "title", "description", "dd", 42,
            Arrays.asList("*/*"));

    XMLQuery copy = (XMLQuery) original.clone();
    assertEquals(original, copy, "a clone is not equal to its original");
    assertEquals(original.hashCode(), copy.hashCode(), "a clone hashes differently");
    assertEquals(
        original.toString(), copy.toString(), "a clone describes itself differently");

    List<Object> rewritten = new ArrayList<Object>();
    rewritten.add(new QueryElement("elemName", replacement));
    copy.setWhereElementSet(rewritten);

    assertEquals(rewritten, copy.getWhereElementSet(), "the replaced where clause did not take");
  }

  /**
   * A query that will not parse is marked in error and still describes itself.
   * A server that receives one has to be able to say what it received.
   */
  @HegelTest
  void anUnparseableQueryIsMarkedInErrorAndStillDescribesItself(TestCase tc) {
    String nonsense = tc.draw(asciiWord(), "nonsense");

    XMLQuery query =
        queryWith(nonsense, "id", "title", "description", "dd", 42, Arrays.asList("*/*"));

    assertEquals("ERROR", query.getQueryHeader().getStatusID(), "a bare word parsed as a query");
    assertTrue(
        query.toString().contains(nonsense),
        "the query does not describe itself with the text it was given: " + query);
  }

  /**
   * A query built with parsing turned off keeps its text and says so, rather
   * than reporting an error it was never asked to look for. Callers construct
   * these to relay a query string a downstream server will parse in its own
   * dialect.
   */
  @HegelTest
  void anUnparsedQueryIsMarkedUnparsedRatherThanFailed(TestCase tc) {
    String text = drawMarkupText(tc, "text");

    XMLQuery query =
        new XMLQuery(
            text, "id", "title", "description", "dd", null, null, null, 42, false);

    assertEquals("NOTPARSED", query.getQueryHeader().getStatusID(), "the status is not NOTPARSED");
    assertEquals(text, query.getKwdQueryString(), "the query text changed");
    assertTrue(query.getWhereElementSet().isEmpty(), "an unparsed query has a where clause");
    assertTrue(query.getSelectElementSet().isEmpty(), "an unparsed query has a select clause");
  }

  /**
   * The DOM form and the string form describe the same query. A caller may take
   * either — {@link XMLQuery#getXMLDoc} for a server that is going to embed the
   * query in a larger document, the string for one that is going to post it —
   * and the two must not disagree.
   */
  @HegelTest
  void theDomFormAndTheStringFormAgree(TestCase tc) throws Exception {
    XMLQuery original =
        queryWith(
            tc.draw(ordinaryText(), "keywordQuery") + " EQ " + tc.draw(ordinaryText(), "literal"),
            tc.draw(ordinaryText(), "id"),
            drawMarkupText(tc, "title"),
            drawMarkupText(tc, "description"),
            tc.draw(ordinaryText(), "ddId"),
            tc.draw(integers().min(0).max(100000), "maxResults"),
            Arrays.asList("*/*"));

    XMLQuery fromDom = new XMLQuery(original.getXMLDoc().getDocumentElement());
    XMLQuery fromString = throughTheDocument(original);

    assertEquals(fromString, fromDom, "the DOM and string forms parsed to different queries");
  }
}
