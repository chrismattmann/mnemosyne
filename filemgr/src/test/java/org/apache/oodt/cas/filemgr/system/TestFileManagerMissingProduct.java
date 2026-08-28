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

package org.apache.oodt.cas.filemgr.system;

import org.apache.oodt.cas.filemgr.catalog.MockCatalog;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A product the catalog does not hold.
 *
 * getProductById and getProductByName called product.setProductType(...) on
 * the null the catalog returned. Avro has no declared error to marshal a
 * NullPointerException into -- AvroFileManagerServer catches CatalogException
 * and converts it to OodtError, and an unchecked exception goes straight past
 * that -- so the call failed in a way the client read as the daemon being
 * gone, and the Status page showed File Manager DOWN while it was still
 * listening.
 *
 * OPSUI asks for ids that are not in the catalog as a matter of course: Solr
 * posting ids, old translated-job ids.
 */
public class TestFileManagerMissingProduct {

    private FileManager fileManager;

    /** A catalog that holds nothing at all. */
    private static final class EmptyCatalog extends MockCatalog {
        @Override
        public Product getProductById(String productId) {
            return null;
        }

        @Override
        public Product getProductByName(String productName) {
            return null;
        }
    }

    @Before
    public void setUp() throws Exception {
        fileManager = new FileManager();
        fileManager.setCatalog(new EmptyCatalog());
    }

    @Test
    public void testAnAbsentProductIdIsReportedAsACatalogException()
            throws Exception {
        try {
            Product product = fileManager.getProductById("no-such-id");
            fail("expected a CatalogException, got: " + product);
        } catch (CatalogException expected) {
            assertTrue("the message does not name the id: "
                    + expected.getMessage(),
                    expected.getMessage().contains("no-such-id"));
        } catch (NullPointerException e) {
            fail("a missing product still produces a NullPointerException, "
                    + "which Avro cannot marshal");
        }
    }

    @Test
    public void testAnAbsentProductNameIsReportedAsACatalogException()
            throws Exception {
        try {
            Product product = fileManager.getProductByName("no-such-name");
            fail("expected a CatalogException, got: " + product);
        } catch (CatalogException expected) {
            assertTrue("the message does not name the product: "
                    + expected.getMessage(),
                    expected.getMessage().contains("no-such-name"));
        } catch (NullPointerException e) {
            fail("a missing product still produces a NullPointerException, "
                    + "which Avro cannot marshal");
        }
    }

    /**
     * The exception has to be a CatalogException specifically, because that
     * is the only thing AvroFileManagerServer converts into the OodtError the
     * protocol declares. Anything else escapes as an undeclared type.
     */
    @Test
    public void testTheExceptionIsTheOneTheServerCanMarshal() {
        try {
            fileManager.getProductById("no-such-id");
            fail("expected a CatalogException");
        } catch (Exception e) {
            assertEquals(CatalogException.class, e.getClass());
        }
    }
}
