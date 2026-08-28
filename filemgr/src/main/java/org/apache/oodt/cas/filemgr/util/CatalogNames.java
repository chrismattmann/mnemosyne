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
import org.apache.oodt.cas.filemgr.structs.exceptions.QueryFormulationException;
import org.apache.oodt.cas.filemgr.structs.query.ComplexQuery;
import org.apache.oodt.cas.filemgr.system.FileManagerClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The names a catalog answers to, and the one place a parsed query is mapped
 * onto them.
 *
 * Every route into the File Manager that parses SQL needs this -- the web
 * service, the CLI and QueryTool all call SqlParser.parseSqlQuery -- so it
 * lives here rather than beside any one of them. Keyword case is handled in
 * the parser itself; this is about the identifiers.
 */
public final class CatalogNames {

    private static final Logger LOG = Logger.getLogger(CatalogNames.class.getName());

    /**
     * The field names every catalog has.
     *
     * A query naming one of these, in any casing, resolves without asking the
     * File Manager for anything -- which is the common case and the reason
     * the element lookup below is worth deferring.
     */
    public static final List<String> CORE_ELEMENT_NAMES =
        Collections.unmodifiableList(Arrays.asList(
            "Filename", "FileLocation", "FileSize", "ProductType", "ProductName",
            "InputFiles", "MimeType", "CAS.ProductName", "CAS.ProductId",
            "CAS.ProductReceivedTime"));

    private CatalogNames() {
    }

    /**
     * Rewrites a parsed query's identifiers to the catalog's own spelling, so
     * {@code select filename from employmentjob} is the same query as
     * {@code SELECT Filename FROM EmploymentJob}.
     *
     * Leaves the query alone if the product types cannot be read. An empty
     * list would make every FROM clause an unknown-type error, and failing to
     * reach the File Manager is not the same as the caller naming something
     * that does not exist -- the File Manager reports that itself.
     *
     * @param query  the query to rewrite in place; null is ignored
     * @param client a connected File Manager client; null is ignored
     * @throws QueryFormulationException if a name is unknown or ambiguous
     */
    public static void resolve(ComplexQuery query, final FileManagerClient client)
            throws QueryFormulationException {
        if (query == null || client == null) {
            return;
        }

        final List<ProductType> types = productTypes(client);
        if (types == null) {
            return;
        }

        List<String> typeNames = new ArrayList<String>();
        for (ProductType type : types) {
            if (type != null && type.getName() != null) {
                typeNames.add(type.getName());
            }
        }
        if (typeNames.isEmpty()) {
            return;
        }

        SqlParser.resolveIdentifiers(query, typeNames, CORE_ELEMENT_NAMES,
            new Supplier<Collection<String>>() {
                @Override
                public Collection<String> get() {
                    return elementNames(client, types);
                }
            });
    }

    /**
     * @return every product type the catalog holds, or null if they could not
     *         be read
     */
    private static List<ProductType> productTypes(FileManagerClient client) {
        try {
            return client.getProductTypes();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Unable to read the product types to resolve "
                + "query identifiers against; leaving the query as written: "
                + e.getMessage());
            return null;
        }
    }

    /**
     * Every element name the catalog declares, at one call per product type.
     *
     * Reached only for a field name the core keys do not cover. A product
     * type whose elements cannot be read contributes nothing rather than
     * failing the query: a deployment with no validation layer still has the
     * core keys, and an unrecognised field is left as the caller typed it.
     */
    private static List<String> elementNames(FileManagerClient client,
            List<ProductType> types) {
        List<String> names = new ArrayList<String>(CORE_ELEMENT_NAMES);
        for (ProductType type : types) {
            if (type == null) {
                continue;
            }
            try {
                List<Element> elements = client.getElementsByProductType(type);
                if (elements == null) {
                    continue;
                }
                for (Element element : elements) {
                    if (element != null && element.getElementName() != null
                            && !names.contains(element.getElementName())) {
                        names.add(element.getElementName());
                    }
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "No elements readable for product type ["
                    + type.getName() + "]: " + e.getMessage());
            }
        }
        return names;
    }
}
