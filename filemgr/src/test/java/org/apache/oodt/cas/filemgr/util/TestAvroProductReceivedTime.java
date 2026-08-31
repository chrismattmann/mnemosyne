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

package org.apache.oodt.cas.filemgr.util;

import junit.framework.TestCase;

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.avrotypes.AvroProduct;

/**
 * When a product arrived is a fact the catalog holds and callers ask for.
 * It used to survive only as far as the first RPC call.
 */
public class TestAvroProductReceivedTime extends TestCase {

  private static final String RECEIVED = "2026-08-31T10:51:09.771-07:00";

  public void testReceivedTimeSurvivesTheRoundTrip() {
    Product product = new Product();
    product.setProductId("urn:test:1");
    product.setProductName("rat_x-java-source_1788198618270_2024.log");
    product.setProductRecievedTime(RECEIVED);

    Product back = AvroTypeFactory.getProduct(
        AvroTypeFactory.getAvroProduct(product));

    assertEquals(RECEIVED, back.getProductReceivedTime());
  }

  public void testAProductWithNoReceivedTimeStillCrosses() {
    Product product = new Product();
    product.setProductId("urn:test:2");
    product.setProductName("no-time");

    Product back = AvroTypeFactory.getProduct(
        AvroTypeFactory.getAvroProduct(product));

    assertEquals("urn:test:2", back.getProductId());
    assertNull(back.getProductReceivedTime());
  }

  public void testTheFieldIsOptionalOnTheWire() {
    // Declared with a null default, so a peer that predates the field can
    // still read a record carrying it, and one that sends without it can
    // still be read here.
    AvroProduct avro = AvroTypeFactory.getAvroProduct(new Product());
    assertNull(avro.getProductReceivedTime());
    assertNull(AvroTypeFactory.getProduct(avro).getProductReceivedTime());
  }
}
