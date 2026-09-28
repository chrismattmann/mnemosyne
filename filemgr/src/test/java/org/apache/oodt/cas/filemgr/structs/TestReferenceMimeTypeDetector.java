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

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Vector;
import org.apache.oodt.cas.filemgr.metadata.extractors.examples.MimeTypeExtractor;
import org.apache.oodt.cas.metadata.Metadata;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Contract tests for the selectable once-per-reference MIME decision. */
public class TestReferenceMimeTypeDetector {
    private HttpServer server;

    @After
    public void reset() {
        System.clearProperty(ReferenceMimeTypeDetectors.DETECTOR_PROPERTY);
        System.clearProperty(BytewiseServiceMimeTypeDetector.URL_PROPERTY);
        System.clearProperty(BytewiseServiceMimeTypeDetector.TIMEOUT_PROPERTY);
        if (server != null) server.stop(0);
        BytewiseServiceMimeTypeDetector.resetFallbackCount();
    }

    private void serve(String mime) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/detect/", exchange -> {
            byte[] response = ("{\"mime_type\":\"" + mime + "\",\"confidence\":0.99}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        System.setProperty(BytewiseServiceMimeTypeDetector.URL_PROPERTY,
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    public void testTikaRemainsTheDefault() {
        Reference reference = new Reference("file:/tmp/example.txt", "file:/archive/example.txt", 4L);
        assertNotNull(reference.getMimeType());
        assertEquals("text/plain", reference.getMimeType().getName());
    }

    @Test
    public void testBytewiseValueFlowsThroughMimeTypeExtractor() throws Exception {
        serve("application/pdf");
        System.setProperty(ReferenceMimeTypeDetectors.DETECTOR_PROPERTY, "bytewise");
        Reference reference = new Reference("file:/tmp/example.bin", "file:/archive/example.bin", 4L);
        assertEquals("application/pdf", reference.getMimeType().getName());

        Product product = Product.getDefaultFlatProduct("test", "urn:oodt:GenericFile");
        Vector<Reference> references = new Vector<Reference>();
        references.add(reference);
        product.setProductReferences(references);
        Metadata metadata = new MimeTypeExtractor().doExtract(product, new Metadata());
        assertEquals("application/pdf", metadata.getMetadata("MimeType"));
    }

    @Test
    public void testUnavailableBytewiseFallsBackToTika() {
        System.setProperty(ReferenceMimeTypeDetectors.DETECTOR_PROPERTY, "bytewise");
        System.setProperty(BytewiseServiceMimeTypeDetector.URL_PROPERTY, "http://127.0.0.1:1");
        System.setProperty(BytewiseServiceMimeTypeDetector.TIMEOUT_PROPERTY, "100");
        Reference reference = new Reference("file:/tmp/example.txt", "file:/archive/example.txt", 4L);
        assertEquals("text/plain", reference.getMimeType().getName());
        assertEquals(1L, BytewiseServiceMimeTypeDetector.getFallbackCount());
    }
}
