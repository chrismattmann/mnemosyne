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

import org.apache.oodt.cas.filemgr.structs.Element;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.query.ComplexQuery;
import org.apache.oodt.cas.filemgr.system.MockFileManagerClient;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The one place that maps a parsed query's identifiers onto the catalog's own
 * spelling, shared by the web service, the CLI and QueryTool -- all three
 * parse the same SQL.
 */
public class TestCatalogNames {

    private static ProductType type(String name) {
        ProductType t = new ProductType();
        t.setName(name);
        t.setProductTypeId("urn:oodt:" + name);
        return t;
    }

    private static Element element(String name) {
        Element e = new Element();
        e.setElementName(name);
        e.setElementId(name);
        return e;
    }

    /** A client answering with a fixed catalog, counting what it is asked. */
    private static class StubClient extends MockFileManagerClient {
        private final List<ProductType> types;
        private final List<Element> elements;
        int productTypeCalls;
        int elementCalls;
        boolean productTypesFail;

        StubClient(List<ProductType> types, List<Element> elements)
                throws Exception {
            this.types = types;
            this.elements = elements;
        }

        @Override
        public List<ProductType> getProductTypes() {
            productTypeCalls++;
            if (productTypesFail) {
                throw new IllegalStateException("file manager unreachable");
            }
            return types;
        }

        @Override
        public List<Element> getElementsByProductType(ProductType type) {
            elementCalls++;
            return elements;
        }
    }

    private static StubClient catalog() throws Exception {
        return new StubClient(
            new ArrayList<ProductType>(Arrays.asList(type("EmploymentJob"))),
            new ArrayList<Element>(Arrays.asList(element("CustomField"))));
    }

    /** The reported case: everything lower case. */
    @Test
    public void testAMiscasedQueryIsResolved() throws Exception {
        ComplexQuery query = SqlParser.parseSqlQuery(
                "select filename from employmentjob where filename == 'x'");

        CatalogNames.resolve(query, catalog());

        assertEquals(Arrays.asList("EmploymentJob"), query.getReducedProductTypeNames());
        assertEquals(Arrays.asList("Filename"), query.getReducedMetadata());
        assertEquals("Filename", query.getCriteria().get(0).getElementName());
    }

    /** The core keys cover it, so no element lookup happens. */
    @Test
    public void testACoreFieldCostsNoElementLookup() throws Exception {
        StubClient client = catalog();

        CatalogNames.resolve(SqlParser.parseSqlQuery(
                "select filename from employmentjob"), client);

        assertEquals("the elements were enumerated needlessly", 0, client.elementCalls);
        assertEquals(1, client.productTypeCalls);
    }

    /** A field the core keys do not cover is worth the lookup. */
    @Test
    public void testACustomFieldIsResolvedFromTheValidationLayer() throws Exception {
        StubClient client = catalog();
        ComplexQuery query = SqlParser.parseSqlQuery(
                "select customfield from employmentjob");

        CatalogNames.resolve(query, client);

        assertEquals(Arrays.asList("CustomField"), query.getReducedMetadata());
        assertEquals(1, client.elementCalls);
    }

    /** An unknown product type is an error, since the FROM clause must name one. */
    @Test
    public void testAnUnknownProductTypeIsAnError() throws Exception {
        try {
            CatalogNames.resolve(
                SqlParser.parseSqlQuery("SELECT Filename FROM nope"), catalog());
            fail("expected a QueryFormulationException");
        } catch (Exception expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("nope"));
        }
    }

    /**
     * Failing to reach the File Manager is not the same as the caller naming
     * something that does not exist. An empty type list would make every FROM
     * clause an unknown-type error; the query is left as written instead, and
     * the File Manager reports the real problem itself.
     */
    @Test
    public void testAnUnreachableCatalogLeavesTheQueryAlone() throws Exception {
        StubClient client = catalog();
        client.productTypesFail = true;
        ComplexQuery query = SqlParser.parseSqlQuery("select filename from employmentjob");

        CatalogNames.resolve(query, client);

        assertEquals(Arrays.asList("employmentjob"), query.getReducedProductTypeNames());
    }

    /** A null client or query is ignored rather than dereferenced. */
    @Test
    public void testNullsAreIgnored() throws Exception {
        CatalogNames.resolve(null, catalog());
        CatalogNames.resolve(SqlParser.parseSqlQuery("SELECT Filename FROM EmploymentJob"), null);
    }
}
