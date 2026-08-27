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

package org.apache.oodt.cas.webcomponents.filemgr.model;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;

/**
 * Properties of the serialisable copy of a {@link Product} that
 * {@link ProductModel} hands to Wicket.
 *
 * <p>A Wicket page holds its model across requests, so whatever the model keeps
 * is the whole of what the page can still show on the next request. A field the
 * copy drops is a field the browser page silently loses.
 */
class ProductModelPropertyTest {

  private static Generator<String> word() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  private static Product drawProduct(TestCase tc) {
    ProductType type = new ProductType();
    type.setName(tc.draw(word(), "typeName"));
    type.setProductTypeId(tc.draw(word(), "typeId"));
    type.setDescription(tc.draw(word(), "typeDescription"));

    Product product = new Product();
    product.setProductType(type);
    product.setProductId(tc.draw(word(), "productId"));
    product.setProductName(tc.draw(word(), "productName"));
    product.setProductStructure(
        tc.draw(
            sampledFrom(
                Arrays.asList(
                    Product.STRUCTURE_FLAT,
                    Product.STRUCTURE_HIERARCHICAL,
                    Product.STRUCTURE_STREAM)),
            "structure"));
    product.setTransferStatus(
        tc.draw(
            sampledFrom(
                Arrays.asList(
                    Product.STATUS_RECEIVED, Product.STATUS_TRANSFER)),
            "transferStatus"));
    product.setProductRecievedTime(tc.draw(word(), "productReceivedTime"));

    List<String> refNames = tc.draw(lists(word()).maxSize(3), "refNames");
    List<Reference> refs = new ArrayList<Reference>();
    for (String refName : refNames) {
      Reference ref = new Reference();
      ref.setOrigReference("file:/orig/" + refName);
      ref.setDataStoreReference("file:/store/" + refName);
      ref.setFileSize(tc.draw(integers().min(0).max(1_000_000), "fileSize-" + refName));
      refs.add(ref);
    }
    product.setProductReferences(refs);

    Reference root = new Reference();
    root.setOrigReference("file:/orig/root");
    root.setDataStoreReference("file:/store/root");
    product.setRootRef(root);

    return product;
  }

  /**
   * The model gives back a product describing the same product it was made
   * with. Everything the browser prints — the name, the type, the references,
   * the received time — is read back off the model, so a field that does not
   * survive the copy is a field the page cannot show.
   */
  @HegelTest
  void theModelKeepsEveryFieldOfTheProductItWasGiven(TestCase tc) {
    Product original = drawProduct(tc);

    Product held = new ProductModel(original).getObject();

    assertNotNull(held, "the model gave back no product at all");
    assertEquals(original.getProductId(), held.getProductId(), "productId");
    assertEquals(original.getProductName(), held.getProductName(), "productName");
    assertEquals(original.getProductStructure(), held.getProductStructure(), "productStructure");
    assertEquals(original.getTransferStatus(), held.getTransferStatus(), "transferStatus");
    assertEquals(
        original.getProductType().getName(), held.getProductType().getName(), "productType name");
    assertEquals(
        original.getProductReferences().size(),
        held.getProductReferences().size(),
        "reference count");
    assertEquals(
        original.getRootRef().getDataStoreReference(),
        held.getRootRef().getDataStoreReference(),
        "rootRef");
    assertEquals(
        original.getProductReceivedTime(), held.getProductReceivedTime(), "productReceivedTime");
  }

  /**
   * What the model holds survives a trip through the session store. That is the
   * model's entire reason for existing: Wicket writes a page's models out
   * between requests and reads them back to render the next one, so a product
   * that comes back empty is a page that comes back empty.
   */
  @HegelTest
  void theProductSurvivesATripThroughTheSessionStore(TestCase tc) throws Exception {
    Product original = drawProduct(tc);
    Product held = new ProductModel(original).getObject();

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ObjectOutputStream out = new ObjectOutputStream(bytes);
    try {
      out.writeObject(held);
    } finally {
      out.close();
    }

    ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    Product restored;
    try {
      restored = (Product) in.readObject();
    } finally {
      in.close();
    }

    assertEquals(original.getProductName(), restored.getProductName(), "productName");
    assertEquals(original.getProductId(), restored.getProductId(), "productId");
    assertNotNull(restored.getProductType(), "the product type did not survive");
    assertEquals(
        original.getProductType().getName(), restored.getProductType().getName(), "type name");
  }
}
