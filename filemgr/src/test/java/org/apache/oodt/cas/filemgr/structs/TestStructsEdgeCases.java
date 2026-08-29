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

package org.apache.oodt.cas.filemgr.structs;

import org.apache.oodt.cas.filemgr.structs.avrotypes.AvroProduct;
import org.apache.oodt.cas.filemgr.structs.type.examples.MajorMinorVersionTypeHandler;
import org.apache.oodt.cas.filemgr.util.AvroTypeFactory;
import org.apache.oodt.cas.metadata.Metadata;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Small structural defects from #134, items 13, 14, 16 and 17.
 */
public class TestStructsEdgeCases {

  /** setProductStructure sat inside a null check on the product *type*. */
  @Test
  public void aTypelessProductKeepsItsStructureOverTheWire() {
    Product product = new Product();
    product.setProductId("id");
    product.setProductName("name");
    product.setProductStructure(Product.STRUCTURE_FLAT);
    product.setProductType(null);
    product.setProductReferences(new ArrayList<Reference>());

    AvroProduct avro = AvroTypeFactory.getAvroProduct(product);

    assertEquals(Product.STRUCTURE_FLAT, avro.getProductStructure());
  }

  @Test
  public void aTypedProductStillKeepsItsStructure() {
    Product product = new Product();
    product.setProductId("id");
    product.setProductName("name");
    product.setProductStructure(Product.STRUCTURE_HIERARCHICAL);
    ProductType type = new ProductType();
    type.setName("GenericFile");
    type.setProductTypeId("urn:oodt:GenericFile");
    product.setProductType(type);
    product.setProductReferences(new ArrayList<Reference>());

    AvroProduct avro = AvroTypeFactory.getAvroProduct(product);

    assertEquals(Product.STRUCTURE_HIERARCHICAL, avro.getProductStructure());
  }

  /**
   * The class javadoc documents \d{1,2}.\d{0,2} as the input format, so an
   * empty minor part is valid -- and split returns one element for it. Driven
   * through preAddMetadataHandle, which is the ingest path the exception used
   * to escape from.
   */
  @Test
  public void anEmptyMinorVersionIsPadded() {
    assertEquals("00.00", catalogValueOf("0."));
  }

  @Test
  public void aNormalVersionIsStillPadded() {
    assertEquals("01.20", catalogValueOf("1.2"));
  }

  @Test
  public void aTwoDigitVersionIsUnchanged() {
    assertEquals("11.22", catalogValueOf("11.22"));
  }

  private String catalogValueOf(String version) {
    MajorMinorVersionTypeHandler handler = new MajorMinorVersionTypeHandler();
    handler.setElementName("Version");

    Metadata metadata = new Metadata();
    metadata.addMetadata("Version", version);
    handler.preAddMetadataHandle(metadata);

    return metadata.getMetadata("Version");
  }

  /** 0.0/0.0 is NaN, and NaN fails every comparison a poll loop can make. */
  @Test
  public void aZeroByteFileIsFullyTransferred() {
    Reference reference = new Reference("file:/a", "file:/b", 0L);
    FileTransferStatus status =
        new FileTransferStatus(reference, 0L, 0L, new Product());

    assertEquals(1.0, status.computePctTransferred(), 0.0);
    assertTrue(status.computePctTransferred() >= 1.0);
  }

  @Test
  public void apartlyTransferredFileIsStillAFraction() {
    Reference reference = new Reference("file:/a", "file:/b", 100L);
    FileTransferStatus status =
        new FileTransferStatus(reference, 100L, 25L, new Product());

    assertEquals(0.25, status.computePctTransferred(), 0.0);
  }

  /** Page zero of zero is neither a real page nor the first one. */
  @Test
  public void ablankProductPageIsBothFirstAndLast() {
    ProductPage blank = ProductPage.blankPage();

    assertTrue("a blank page should be the first page", blank.isFirstPage());
    assertTrue("a blank page should be the last page", blank.isLastPage());
    assertTrue(blank.getPageProducts().isEmpty());
  }

  @Test
  public void arealFirstPageIsUnaffected() {
    ProductPage page = new ProductPage(1, 3, 10, new ArrayList<Product>());

    assertTrue(page.isFirstPage());
    assertTrue(!page.isLastPage());
  }
}
