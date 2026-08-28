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

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.metadata.Metadata;

import org.junit.Test;

import java.util.Vector;

import static org.junit.Assert.*;

/**
 * The four-argument {@link Reference} constructor explicitly permits a null
 * mime type, and this extractor dereferenced it three times. #145 advises
 * avoiding the three-argument constructor because it builds a Tika config and
 * spawns a subprocess per reference -- so a caller taking that advice hands
 * this a reference with no mime type and takes the File Manager down on
 * addMetadata.
 */
public class TestMimeTypeExtractor {

    private static Product flatProductWith(Reference ref) {
        Product p = Product.getDefaultFlatProduct("test", "urn:oodt:GenericFile");
        Vector<Reference> refs = new Vector<Reference>();
        if (ref != null) {
            refs.add(ref);
        }
        p.setProductReferences(refs);
        return p;
    }

    @Test
    public void testAReferenceWithNoMimeTypeIsNotAFailure() throws Exception {
        Product product = flatProductWith(
                new Reference("file:/tmp/a.dat", "file:/archive/a.dat", 10L, null));

        Metadata met = new MimeTypeExtractor().doExtract(product, new Metadata());

        assertNotNull(met);
        assertNull("a mime type was invented for a reference that has none",
                met.getMetadata("MimeType"));
    }

    /** a product carrying no references at all is the same story. */
    @Test
    public void testAProductWithNoReferencesIsNotAFailure() throws Exception {
        Metadata met = new MimeTypeExtractor()
                .doExtract(flatProductWith(null), new Metadata());

        assertNotNull(met);
    }

    /** and a product with no structure set does not decide it is flat. */
    @Test
    public void testAProductWithNoStructureIsNotAFailure() throws Exception {
        Product product = flatProductWith(
                new Reference("file:/tmp/a.dat", "file:/archive/a.dat", 10L, null));
        product.setProductStructure(null);

        assertNotNull(new MimeTypeExtractor().doExtract(product, new Metadata()));
    }

    /** a reference that does carry a mime type still reports it. */
    @Test
    public void testAReferenceWithAMimeTypeStillReportsIt() throws Exception {
        Reference ref = new Reference("file:/tmp/a.txt", "file:/archive/a.txt", 10L);
        Product product = flatProductWith(ref);

        Metadata met = new MimeTypeExtractor().doExtract(product, new Metadata());

        assertNotNull(met);
        if (ref.getMimeType() != null) {
            assertNotNull("the mime type was dropped", met.getMetadata("MimeType"));
        }
    }
}
