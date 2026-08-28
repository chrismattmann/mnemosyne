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
package org.apache.oodt.cas.filemgr.metadata.extractors.examples;

import java.io.File;

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.metadata.Metadata;

import junit.framework.TestCase;

/**
 * Parses a real Word document, which is the only thing here that crosses
 * POI's module boundary.
 *
 * Nothing in this project imports POI; it arrives through tika-parsers, which
 * declares poi, poi-ooxml and poi-scratchpad together and expects one version
 * across all three. A .doc is read by HWPF in poi-scratchpad, which compiles
 * against poi core, so parsing one exercises exactly the pair that was
 * mismatched: poi pinned to 3.2-FINAL while scratchpad resolved to 3.15-beta1.
 *
 * The existing TestTikaAutoDetectExtractor reads a .txt file and never reaches
 * POI at all, so it could not have caught this.
 */
public class TestTikaOfficeExtraction extends TestCase {

    public void testParsesAWordDocument() throws Exception {
        // read from the module rather than the classpath: main resources are
        // packaged into the jar, not copied to the test classpath
        File doc = new File("src/main/resources/CAS File Manager User Guide.doc");
        assertTrue("the .doc fixture is missing: " + doc.getAbsolutePath(), doc.isFile());

        Reference ref = new Reference();
        ref.setOrigReference(doc.toURI().toString());
        ref.setDataStoreReference(doc.toURI().toString());

        Product product = new Product();
        product.getProductReferences().add(ref);
        product.setProductStructure(Product.STRUCTURE_FLAT);

        Metadata met = new TikaAutoDetectExtractor().doExtract(product, new Metadata());

        assertNotNull(met);
        assertTrue("no metadata came back from the document", met.getAllKeys().size() > 0);

        // Renamed into the X-TIKA namespace at Tika 2.0. It read
        // "X-Parsed-By" and got null, so String.valueOf gave "null", which
        // does not contain "EmptyParser" -- the assertion below passed
        // without ever seeing what parsed the document.
        String parsedBy = String.valueOf(met.getMetadata("X-TIKA:Parsed-By"));
        assertFalse("no parser was recorded", "null".equals(parsedBy));
        assertFalse("Tika fell back to the empty parser, so the document was never read: "
                        + parsedBy,
                parsedBy.contains("EmptyParser"));

        assertEquals("application/msword", met.getMetadata("Content-Type"));
    }
}
