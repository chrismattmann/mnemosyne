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

package org.apache.oodt.pcs.pedigree;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.time.Duration;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.system.FileManagerClient;
import org.apache.oodt.pcs.util.FileManagerUtils;
import org.junit.jupiter.api.Assertions;

/**
 * Properties of {@link Pedigree} when there is no catalog to consult.
 *
 * <p>{@code PCSTrace} runs this class against a File Manager that may well be
 * down, and a {@link FileManagerUtils} built on a null client is exactly that
 * situation: no socket is opened, every lookup returns its empty stand-in.
 * What the trace tool needs in that case is a usable, terminating answer
 * rather than a crash.
 */
class PedigreePropertyTest {

  private static final Generator<String> NAME =
      text().minSize(1).maxSize(12).categories("Lu", "Ll", "Nd");

  /**
   * With nothing to look up, the pedigree of a product is that product on its
   * own: one level, the original at its root, and no invented ancestry. The
   * traversal also has to stop — it walks a work stack, and a stack that never
   * empties would hang the trace tool.
   */
  @HegelTest
  void aPedigreeWithNoCatalogIsJustTheProductItself(TestCase tc) {
    String prodName = tc.draw(NAME, "prodName");
    boolean upstream = tc.draw(booleans(), "upstream");
    boolean listNotCataloged = tc.draw(booleans(), "listNotCataloged");
    List<String> excludeTypes = tc.draw(lists(NAME).minSize(0).maxSize(4), "excludeTypes");

    FileManagerUtils fm = new FileManagerUtils((FileManagerClient) null);
    Pedigree pedigree = new Pedigree(fm, listNotCataloged, excludeTypes);
    Product orig = Product.getDefaultFlatProduct(prodName, "urn:oodt:GenericFile");

    PedigreeTree tree =
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> pedigree.doPedigree(orig, upstream));

    assertNotNull(tree.getRoot(), "the pedigree lost the product it started from");
    assertSame(orig, tree.getRoot().getNodeProduct());
    assertEquals(0, tree.getRoot().getNumChildren(), "ancestry was invented from an empty catalog");
    assertEquals(1, tree.getNumLevels());
  }

  /**
   * The direct-relative lookups behave the same way: a list, never null, so
   * the caller's loop over the result is safe whether or not the catalog
   * answered.
   */
  @HegelTest
  void directRelativesAreAlwaysAList(TestCase tc) {
    String prodName = tc.draw(NAME, "prodName");
    List<String> excludeTypes = tc.draw(lists(NAME).minSize(0).maxSize(4), "excludeTypes");

    FileManagerUtils fm = new FileManagerUtils((FileManagerClient) null);
    Pedigree pedigree = new Pedigree(fm, false, excludeTypes);
    Product orig = Product.getDefaultFlatProduct(prodName, "urn:oodt:GenericFile");

    assertNotNull(pedigree.getUpstreamPedigreedProducts(orig));
    assertNotNull(pedigree.getDownstreamPedigreedProducts(orig));
    assertNotNull(pedigree.getWorkflowInstProds(prodName));
  }
}
