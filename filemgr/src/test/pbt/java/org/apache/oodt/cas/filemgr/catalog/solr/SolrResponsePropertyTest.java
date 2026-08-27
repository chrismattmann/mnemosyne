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

package org.apache.oodt.cas.filemgr.catalog.solr;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;

/**
 * Properties of how a Solr answer is read back into products.
 *
 * <p>{@link DefaultProductSerializer#deserialize(String)} is the only route by
 * which anything comes out of a Solr-backed catalogue: the client posts a
 * query, Solr replies with an XML document, and this turns that document into
 * the products, references and metadata the file manager hands to its callers.
 * Whatever it fails to read is a product the catalogue reports as absent.
 *
 * <p>The responses below are built the way Solr builds one — {@code <str>} for
 * a single value, {@code <arr>} of {@code <str>} for several, values escaped
 * once because that is what putting text inside an XML element means. Nothing
 * here contacts a server; the wire format is written out in full so that what
 * is asserted is exactly what a reader of a real response would get.
 */
class SolrResponsePropertyTest {

  private static final DefaultProductSerializer SERIALIZER = new DefaultProductSerializer();

  /** Identifiers and field names safe in any XML context. */
  private static Generator<String> plainWords() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  /**
   * A field name outside the reserved {@code CAS.} namespace, and not caught by
   * the serializer's substring test against
   * {@link Parameters#PRODUCT_STRUCTURE}, so that a property about reading a
   * field is not answered by the field having been skipped.
   */
  private static Generator<String> ordinaryFieldNames() {
    return plainWords()
        .filter(name -> !name.startsWith(Parameters.NS))
        .filter(name -> !Parameters.PRODUCT_STRUCTURE.contains(name))
        .filter(name -> !Parameters.ID.equals(name));
  }

  /** A value with a character in it that XML gives a meaning to. */
  private static String drawValue(TestCase tc, String label) {
    String before = tc.draw(plainWords(), label + ".before");
    String special =
        tc.draw(
            sampledFrom(Arrays.asList("&", "<", ">", "\"", "'", " ", "/", "-", "é")),
            label + ".special");
    String after = tc.draw(plainWords(), label + ".after");
    return before + special + after;
  }

  private static String str(String name, String value) {
    return "<str name=\"" + name + "\">" + StringEscapeUtils.escapeXml(value) + "</str>";
  }

  private static String arr(String name, List<String> values) {
    StringBuilder sb = new StringBuilder("<arr name=\"" + name + "\">");
    for (String value : values) {
      sb.append("<str>").append(StringEscapeUtils.escapeXml(value)).append("</str>");
    }
    return sb.append("</arr>").toString();
  }

  private static String response(int numFound, int start, String... docs) {
    StringBuilder sb =
        new StringBuilder(
            "<response><result numFound=\"" + numFound + "\" start=\"" + start + "\">");
    for (String doc : docs) {
      sb.append("<doc>").append(doc).append("</doc>");
    }
    return sb.append("</result></response>").toString();
  }

  /**
   * The paging figures Solr reports come back as they were sent. The catalogue
   * turns them into page numbers and a total result count, so a caller paging
   * through a large result set walks off the end or stops early if they are
   * wrong.
   */
  @HegelTest
  void thePagingFiguresAreReadAsReported(TestCase tc) throws Exception {
    int numFound = tc.draw(integers().min(0).max(1000000), "numFound");
    int start = tc.draw(integers().min(0).max(1000000), "start");
    String id = tc.draw(plainWords(), "productId");

    QueryResponse response =
        SERIALIZER.deserialize(
            response(numFound, start, str(Parameters.PRODUCT_ID, id)));

    assertEquals(numFound, response.getNumFound(), "the total result count changed");
    assertEquals(start, response.getStart(), "the page offset changed");
  }

  /**
   * Every document in the answer becomes a product, in the order Solr listed
   * them. Solr is asked to sort by received time, so the order is the answer;
   * a document dropped is a product the catalogue says does not exist.
   */
  @HegelTest
  void everyDocumentInTheAnswerBecomesAProduct(TestCase tc) throws Exception {
    List<String> ids = tc.draw(lists(plainWords()).minSize(1).maxSize(5), "productIds");

    List<String> docs = new ArrayList<String>();
    for (String id : ids) {
      docs.add(str(Parameters.ID, id) + str(Parameters.PRODUCT_ID, id));
    }

    QueryResponse response =
        SERIALIZER.deserialize(response(ids.size(), 0, docs.toArray(new String[0])));

    List<String> got = new ArrayList<String>();
    for (Product product : response.getProducts()) {
      got.add(product.getProductId());
    }
    assertEquals(ids, got, "the products did not come back in the order they were listed");
    assertEquals(
        response.getCompleteProducts().size(),
        response.getProducts().size(),
        "the product view and the complete view disagree on how many there are");
  }

  /**
   * The core product attributes are read into the product itself, not only into
   * the metadata. A {@link Product} with no name or no transfer status is one
   * the file manager cannot serve, and every one of these fields has a caller
   * that reads it off the object.
   */
  @HegelTest
  void theCoreProductAttributesAreReadOntoTheProduct(TestCase tc) throws Exception {
    String id = tc.draw(plainWords(), "productId");
    String name = drawValue(tc, "productName");
    String structure = tc.draw(sampledFrom(Arrays.asList("Flat", "Hierarchical")), "structure");
    String status = tc.draw(sampledFrom(Arrays.asList("RECEIVED", "TRANSFERRING")), "status");
    String typeName = tc.draw(plainWords(), "typeName");
    String typeId = tc.draw(plainWords(), "typeId");
    String receivedTime = "2011-01-01T00:00:00Z";

    String doc =
        str(Parameters.ID, id)
            + str(Parameters.PRODUCT_ID, id)
            + str(Parameters.PRODUCT_NAME, name)
            + str(Parameters.PRODUCT_STRUCTURE, structure)
            + str(Parameters.PRODUCT_TRANSFER_STATUS, status)
            + str(Parameters.PRODUCT_TYPE_NAME, typeName)
            + str(Parameters.PRODUCT_TYPE_ID, typeId)
            + str(Parameters.PRODUCT_RECEIVED_TIME, receivedTime);

    CompleteProduct complete =
        SERIALIZER.deserialize(response(1, 0, doc)).getCompleteProducts().get(0);
    Product product = complete.getProduct();

    assertEquals(id, product.getProductId(), "the product id was not read");
    assertEquals(name, product.getProductName(), "the product name was not read");
    assertEquals(structure, product.getProductStructure(), "the structure was not read");
    assertEquals(status, product.getTransferStatus(), "the transfer status was not read");
    assertEquals(typeName, product.getProductType().getName(), "the type name was not read");
    assertEquals(typeId, product.getProductType().getProductTypeId(), "the type id was not read");
    assertEquals(
        name,
        complete.getMetadata().getMetadata(Parameters.PRODUCT_NAME),
        "the product name did not also reach the metadata");
  }

  /**
   * The file references come back paired: the nth original path goes with the
   * nth datastore path, the nth size and the nth MIME type. They are stored as
   * four separate multi-valued fields and only their position relates them, so
   * a mismatch hands a caller a file of the wrong size at the wrong path.
   */
  @HegelTest
  void theFileReferencesComeBackPairedByPosition(TestCase tc) throws Exception {
    int count = tc.draw(integers().min(1).max(4), "referenceCount");
    List<String> originals = new ArrayList<String>();
    List<String> datastores = new ArrayList<String>();
    List<String> sizes = new ArrayList<String>();
    List<String> mimeTypes = new ArrayList<String>();
    for (int i = 0; i < count; ++i) {
      originals.add("file:/original/" + tc.draw(plainWords(), "original" + i));
      datastores.add("file:/archive/" + tc.draw(plainWords(), "datastore" + i));
      sizes.add(Long.toString(tc.draw(longs().min(0).max(1000000000L), "size" + i)));
      mimeTypes.add(tc.draw(sampledFrom(Arrays.asList("text/plain", "image/jpeg")), "mime" + i));
    }

    String doc =
        str(Parameters.PRODUCT_ID, tc.draw(plainWords(), "productId"))
            + arr(Parameters.REFERENCE_ORIGINAL, originals)
            + arr(Parameters.REFERENCE_DATASTORE, datastores)
            + arr(Parameters.REFERENCE_FILESIZE, sizes)
            + arr(Parameters.REFERENCE_MIMETYPE, mimeTypes);

    CompleteProduct complete =
        SERIALIZER.deserialize(response(1, 0, doc)).getCompleteProducts().get(0);
    List<Reference> references = complete.getProduct().getProductReferences();

    assertEquals(count, references.size(), "a reference went missing");
    for (int i = 0; i < count; ++i) {
      Reference reference = references.get(i);
      assertEquals(originals.get(i), reference.getOrigReference(), "original path " + i);
      assertEquals(datastores.get(i), reference.getDataStoreReference(), "datastore path " + i);
      assertEquals(Long.parseLong(sizes.get(i)), reference.getFileSize(), "file size " + i);
      assertEquals(mimeTypes.get(i), reference.getMimeType().getName(), "MIME type " + i);
    }
  }

  /**
   * A multi-valued field keeps all of its values, in order. Solr preserves the
   * indexing order deliberately — the serializer's own comment says so — and a
   * caller reading, say, a list of scan targets is relying on it.
   */
  @HegelTest
  void aMultiValuedFieldKeepsAllOfItsValuesInOrder(TestCase tc) throws Exception {
    String field = tc.draw(ordinaryFieldNames(), "field");
    int count = tc.draw(integers().min(1).max(5), "valueCount");
    List<String> values = new ArrayList<String>();
    for (int i = 0; i < count; ++i) {
      values.add(drawValue(tc, "value" + i));
    }

    String doc =
        str(Parameters.PRODUCT_ID, tc.draw(plainWords(), "productId")) + arr(field, values);

    CompleteProduct complete =
        SERIALIZER.deserialize(response(1, 0, doc)).getCompleteProducts().get(0);

    assertEquals(
        values,
        complete.getMetadata().getAllMetadata(field),
        "the values of [" + field + "] changed on the way back");
  }

  /**
   * Solr's own identifier field is not published as metadata. It duplicates
   * {@code CAS.ProductId}, and a caller enumerating metadata keys would
   * otherwise see the same identifier twice under two names.
   */
  @HegelTest
  void solrsOwnIdentifierIsNotPublishedAsMetadata(TestCase tc) throws Exception {
    String id = tc.draw(plainWords(), "productId");

    CompleteProduct complete =
        SERIALIZER.deserialize(
                response(1, 0, str(Parameters.ID, id) + str(Parameters.PRODUCT_ID, id)))
            .getCompleteProducts()
            .get(0);

    assertTrue(
        complete.getMetadata().containsKey(Parameters.PRODUCT_ID),
        "the CAS product id is missing from the metadata");
    assertEquals(
        null,
        complete.getMetadata().getMetadata(Parameters.ID),
        "Solr's internal id was published as metadata as well");
  }

  /**
   * Whitespace between elements does not change what a response says. XML is
   * defined that way, and the choice to indent an answer belongs to whoever
   * configured the request handler, not to the reader: {@code indent} is an
   * ordinary Solr request parameter, and a proxy or a log replay may reformat a
   * response in transit. A reader that only works on unindented output makes
   * the catalogue's correctness depend on a formatting setting nobody thinks of
   * as load bearing.
   */
  @HegelTest
  void indentingTheAnswerDoesNotChangeWhatItSays(TestCase tc) throws Exception {
    String id = tc.draw(plainWords(), "productId");
    String field = tc.draw(ordinaryFieldNames(), "field");
    String value = drawValue(tc, "value");

    String compact =
        response(1, 0, str(Parameters.PRODUCT_ID, id) + str(field, value));
    String indented =
        "<response>\n  <result numFound=\"1\" start=\"0\">\n    <doc>\n      "
            + str(Parameters.PRODUCT_ID, id)
            + "\n      "
            + str(field, value)
            + "\n    </doc>\n  </result>\n</response>";
    tc.note(indented);

    QueryResponse fromCompact = SERIALIZER.deserialize(compact);
    QueryResponse fromIndented;
    try {
      fromIndented = SERIALIZER.deserialize(indented);
    } catch (CatalogException e) {
      throw new AssertionError(
          "an indented response could not be read at all: " + e.getCause(), e);
    }

    assertEquals(
        fromCompact.getCompleteProducts().size(),
        fromIndented.getCompleteProducts().size(),
        "indenting changed how many products the answer holds");
    assertEquals(
        fromCompact.getProducts().get(0).getProductId(),
        fromIndented.getProducts().get(0).getProductId(),
        "indenting changed the product id");
    assertEquals(
        fromCompact.getCompleteProducts().get(0).getMetadata().getMetadata(field),
        fromIndented.getCompleteProducts().get(0).getMetadata().getMetadata(field),
        "indenting changed the value of [" + field + "]");
  }

  /**
   * An answer that cannot be read is reported as a catalogue failure, not as an
   * unchecked exception out of a parser. The file manager's callers catch
   * {@link CatalogException}; anything else escapes the layer that knows what
   * to do about it.
   */
  @HegelTest(testCases = 500)
  void anUnreadableAnswerIsReportedAsACatalogFailure(TestCase tc) {
    String body =
        tc.draw(
            sampledFrom(
                Arrays.asList(
                    "",
                    "<response/>",
                    "<response><result/></response>",
                    "<response><result numFound=\"x\" start=\"0\"/></response>",
                    "<response><result numFound=\"1\" start=\"0\"><doc/></result></response>",
                    "not xml at all",
                    "<response><result numFound=\"1\" start=\"0\"><doc>"
                        + "<arr name=\"CAS.ReferenceFileSize\"><str>huge</str></arr>"
                        + "</doc></result></response>",
                    "<unrelated/>")),
            "body");
    tc.note(body);

    try {
      QueryResponse response = SERIALIZER.deserialize(body);
      assertTrue(response.getNumFound() >= 0, "a negative result count was accepted");
    } catch (CatalogException expected) {
      // the documented failure
    } catch (RuntimeException e) {
      throw new AssertionError(
          "an unreadable answer escaped as " + e.getClass().getName() + ": " + e.getMessage(), e);
    }
  }
}
