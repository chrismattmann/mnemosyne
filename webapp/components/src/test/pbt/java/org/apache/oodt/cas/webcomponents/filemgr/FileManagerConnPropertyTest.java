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

package org.apache.oodt.cas.webcomponents.filemgr;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Arrays;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;

/**
 * Properties of {@link FileManagerConn} when there is no file manager.
 *
 * <p>The whole point of this class is that the browser pages can call it
 * whether or not the file manager is up: it logs the problem and answers with
 * something the page can render. A Wicket page has no way to recover from an
 * exception thrown while it is building itself, so "throws" and "returns null"
 * are both failures of that contract.
 *
 * <p>Every URL below is one the JDK cannot parse, so the connection is never
 * attempted and no case touches the network.
 */
class FileManagerConnPropertyTest {

  private static final List<String> UNPARSEABLE =
      Arrays.asList("", "not a url", "://nowhere", "filemgr.example.org", "1234", " ");

  private static Generator<String> unusableUrl() {
    return sampledFrom(UNPARSEABLE);
  }

  /**
   * With no file manager behind it, the connection still answers every question
   * a page asks it, with something the page can render. This is the class's
   * stated reason for existing.
   */
  @HegelTest
  void withNoFileManagerEveryAnswerIsStillRenderable(TestCase tc) {
    String url = tc.draw(unusableUrl(), "url");
    String name = tc.draw(sampledFrom(Arrays.asList("GenericFile", "BookPage", "")), "name");

    FileManagerConn conn = new FileManagerConn(url);
    assertNull(conn.getFm(), "a connection was made to an unusable URL");

    ProductType type = conn.safeGetProductTypeByName(name);
    assertNotNull(type, "the page was handed no product type at all");

    Product product = conn.safeGetProductById(name);
    assertNotNull(product, "the page was handed no product at all");

    assertNotNull(conn.safeGetProductTypes(), "the page was handed no product type list");
    assertNotNull(conn.safeGetElementsForProductType(type), "the page was handed no element list");
    assertNotNull(conn.getProductReferences(product), "the page was handed no reference list");
    assertNotNull(conn.getMetadata(product), "the page was handed no metadata");
  }

  /**
   * A product's received time is always a string the page can print. It is
   * rendered straight into a table cell, so an unknown time has to read as
   * something rather than as nothing.
   */
  @HegelTest
  void aReceivedTimeIsAlwaysSomethingThePageCanPrint(TestCase tc) {
    String url = tc.draw(unusableUrl(), "url");
    List<String> names = tc.draw(lists(sampledFrom(UNPARSEABLE)).minSize(1).maxSize(3), "names");

    FileManagerConn conn = new FileManagerConn(url);

    for (String name : names) {
      String received = conn.getProdReceivedTime(conn.safeGetProductById(name));
      assertNotNull(received, "the received time was null");
      assertTrue(received.length() > 0, "the received time was empty");
      assertEquals("UNKNOWN", received, "an unknown received time did not read as unknown");
    }
  }
}
