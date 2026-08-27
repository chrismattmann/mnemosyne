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
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;

/**
 * Properties of {@link SolrClient}'s failure reporting.
 *
 * <p>Everything this class does is an HTTP request, so there is very little of
 * it a property can reach without a server standing up — and standing one up
 * would make these tests about Solr rather than about the file manager. What is
 * reachable, and worth reaching, is what happens when the client is pointed
 * somewhere it cannot go: the address of the Solr instance comes out of a
 * configuration file that a person edits, and getting it wrong is the ordinary
 * failure.
 *
 * <p>Each property below uses an address that cannot form a URL at all, so the
 * request is refused before a socket is opened. Nothing here connects to
 * anything.
 *
 * <p>{@link QueryResponse} is covered here too. It is the object every
 * catalogue query returns, and its two views of the same results must not be
 * able to disagree.
 */
class SolrClientPropertyTest {

  /**
   * An address that cannot be parsed as a URI, so that the request is rejected
   * where it is built rather than attempted. The space in the authority is what
   * makes it unusable.
   */
  private static final String UNUSABLE_URL = "http://not a host:8983/solr";

  private static Generator<String> plainWords() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  /**
   * A misconfigured Solr address is reported as a catalogue failure by every
   * operation, rather than as an unchecked exception from whatever URL library
   * happens to be underneath. The file manager's callers are written to catch
   * {@link CatalogException}; a different exception escapes the layer that
   * knows how to describe the problem.
   */
  @HegelTest(testCases = 200)
  void aMisconfiguredAddressIsReportedAsACatalogFailure(TestCase tc) {
    SolrClient client = new SolrClient(UNUSABLE_URL);
    String id = tc.draw(plainWords(), "id");
    String mimeType =
        tc.draw(
            sampledFrom(Arrays.asList(Parameters.MIME_TYPE_XML, Parameters.MIME_TYPE_JSON)),
            "mimeType");
    String operation =
        tc.draw(
            sampledFrom(
                Arrays.asList(
                    "queryProductById",
                    "queryProductByName",
                    "queryProductsByDate",
                    "queryProductsByDateAndType",
                    "index",
                    "delete")),
            "operation");
    // Every value is drawn before the try: the block below catches
    // RuntimeException on purpose, and Hegel signals through one of its own.
    int rows = tc.draw(integers().min(1).max(100), "rows");
    ProductType type = new ProductType();
    type.setName(tc.draw(plainWords(), "typeName"));
    tc.note("operation = " + operation);

    try {
      switch (operation) {
        case "queryProductById":
          client.queryProductById(id, mimeType);
          break;
        case "queryProductByName":
          client.queryProductByName(id, mimeType);
          break;
        case "queryProductsByDate":
          client.queryProductsByDate(rows, mimeType);
          break;
        case "queryProductsByDateAndType":
          client.queryProductsByDateAndType(rows, type, mimeType);
          break;
        case "index":
          client.index(Arrays.asList("<doc><field name=\"id\">" + id + "</field></doc>"), false,
              mimeType);
          break;
        default:
          client.delete(id, false);
          break;
      }
      fail("[" + operation + "] against an unusable address reported no failure at all");
    } catch (CatalogException expected) {
      assertNotNull(
          expected.getMessage(), "the failure of [" + operation + "] says nothing about why");
    } catch (RuntimeException e) {
      throw new AssertionError(
          "[" + operation + "] failed as " + e.getClass().getName() + " rather than as a"
              + " CatalogException: " + e.getMessage(),
          e);
    }
  }

  /**
   * An identifier looked up in the index is escaped before it becomes part of a
   * Lucene query. Lucene reads {@code :}, {@code (}, {@code *} and their
   * fellows as syntax, so an identifier carrying one of them stops being an
   * identifier: the lookup becomes a different query, which silently matches
   * other products or none.
   *
   * <p>Both lookups are stated together because both take an identifier a
   * person or another component chose. A product's identifier is not always a
   * UUID — {@link NameProductIdGenerator} is a supported policy and sets it to
   * the product's name — so "the id is always safe" is not something the
   * catalogue may assume.
   *
   * <p>The query is read out of the failure message, which carries the URL the
   * client was about to request. That is the only place the built query is
   * visible without a server to send it to.
   */
  @HegelTest(testCases = 300)
  void anIdentifierIsEscapedBeforeItBecomesLuceneSyntax(TestCase tc) {
    SolrClient client = new SolrClient(UNUSABLE_URL);
    String metacharacter =
        tc.draw(
            sampledFrom(Arrays.asList(":", "(", ")", "[", "]", "\"", "*", "?", "^", "~", "+")),
            "metacharacter");
    String identifier =
        tc.draw(plainWords(), "head") + metacharacter + tc.draw(plainWords(), "tail");
    String lookup = tc.draw(sampledFrom(Arrays.asList("byId", "byName")), "lookup");

    String message;
    try {
      if ("byId".equals(lookup)) {
        client.queryProductById(identifier, Parameters.MIME_TYPE_XML);
      } else {
        client.queryProductByName(identifier, Parameters.MIME_TYPE_XML);
      }
      throw new AssertionError("the lookup reported no failure against an unusable address");
    } catch (CatalogException expected) {
      message = expected.getMessage();
    }
    assertNotNull(message, "the failure said nothing about the request it was building");

    String query = queryStringFrom(message);
    tc.note("lookup = " + lookup + ", query = " + query);

    // Everything after the first colon is the identifier as it was written into
    // the query; the colon itself separates the field name from it.
    int separator = query.indexOf(':');
    assertTrue(separator > 0, "the query does not name a field to search: " + query);
    String written = query.substring(separator + 1);

    int at = written.indexOf(metacharacter);
    assertTrue(at >= 0, "the identifier did not reach the query at all: " + query);
    assertTrue(
        at > 0 && written.charAt(at - 1) == '\\',
        "the "
            + lookup
            + " lookup put an unescaped ["
            + metacharacter
            + "] into the Lucene query: "
            + query);
  }

  /**
   * Recover the {@code q} parameter from the URL named in a failure message.
   * The message ends with the URL the client was about to request.
   */
  private static String queryStringFrom(String message) {
    int at = message.indexOf("?q=");
    if (at < 0) {
      throw new AssertionError("no query string in the failure message: " + message);
    }
    String encoded = message.substring(at + 3);
    int end = encoded.indexOf('&');
    if (end >= 0) {
      encoded = encoded.substring(0, end);
    }
    try {
      return java.net.URLDecoder.decode(encoded, "UTF-8");
    } catch (java.io.UnsupportedEncodingException cannotHappen) {
      throw new IllegalStateException(cannotHappen);
    }
  }

  /**
   * The two views a query answer offers are views of the same results. A caller
   * that only wants the products reads one; a caller that wants the metadata
   * too reads the other, and pairs them up by position.
   */
  @HegelTest
  void theTwoViewsOfAnAnswerAgree(TestCase tc) {
    List<String> ids = tc.draw(lists(plainWords()).minSize(0).maxSize(5), "productIds");
    int numFound = tc.draw(integers().min(0).max(1000000), "numFound");
    int start = tc.draw(integers().min(0).max(1000000), "start");

    List<CompleteProduct> completeProducts = new ArrayList<CompleteProduct>();
    for (String id : ids) {
      CompleteProduct complete = new CompleteProduct();
      complete.getProduct().setProductId(id);
      completeProducts.add(complete);
    }

    QueryResponse response = new QueryResponse();
    response.setResults(completeProducts);
    response.setNumFound(numFound);
    response.setStart(start);

    assertEquals(numFound, response.getNumFound(), "the total result count changed");
    assertEquals(start, response.getStart(), "the page offset changed");
    assertEquals(
        ids.size(), response.getProducts().size(), "the two views hold different numbers");
    for (int i = 0; i < ids.size(); ++i) {
      assertEquals(
          response.getCompleteProducts().get(i).getProduct(),
          response.getProducts().get(i),
          "the two views disagree at position " + i);
    }
  }

  /**
   * A fresh answer holds no results rather than none at all, so a caller can
   * iterate it without asking first.
   */
  @HegelTest(testCases = 20)
  void afreshAnswerHoldsAnEmptyResultList(TestCase tc) {
    QueryResponse response = new QueryResponse();

    assertNotNull(response.getCompleteProducts(), "a fresh answer has no result list");
    assertTrue(response.getCompleteProducts().isEmpty(), "a fresh answer already holds results");
    assertTrue(response.getProducts().isEmpty(), "a fresh answer already holds products");
    assertEquals(0, response.getNumFound(), "a fresh answer claims to have found something");
    assertEquals(0, response.getStart(), "a fresh answer starts somewhere other than the top");
  }

  /**
   * The identifier a product is filed under is decided by the configured
   * generator, and the two supplied generators promise opposite things. Naming
   * by product name is idempotent, so re-submitting a product replaces its
   * record; naming by a fresh identifier is not, so re-submitting adds another.
   * Which of those an installation gets is the difference between an index that
   * stays the size of the archive and one that grows without bound.
   */
  @HegelTest
  void theTwoIdentifierPoliciesDifferAsDocumented(TestCase tc) {
    String name = tc.draw(plainWords(), "productName");
    Product product = new Product();
    product.setProductName(name);

    ProductIdGenerator byName = new NameProductIdGenerator();
    assertEquals(name, byName.generateId(product), "the name generator did not use the name");
    assertEquals(
        byName.generateId(product),
        byName.generateId(product),
        "the name generator gave the same product two identifiers");

    ProductIdGenerator fresh = new UUIDProductIdGenerator();
    String first = fresh.generateId(product);
    String second = fresh.generateId(product);
    assertNotNull(first, "the fresh-identifier generator produced nothing");
    assertTrue(
        !first.equals(second),
        "the fresh-identifier generator repeated itself: " + first);
    assertEquals(
        first,
        java.util.UUID.fromString(first).toString(),
        "the fresh identifier is not a well-formed UUID");
  }
}
