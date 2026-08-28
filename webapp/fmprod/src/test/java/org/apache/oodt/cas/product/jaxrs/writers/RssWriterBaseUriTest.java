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

package org.apache.oodt.cas.product.jaxrs.writers;

import junit.framework.TestCase;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;

import javax.ws.rs.core.UriInfo;

/**
 * getBaseUri read the base URI and then threw it away, returning either ""
 * or "/" -- the "baseUri +" was simply missing, while its own javadoc says it
 * returns "the HTTP servlet request URL up to the final '/'".
 *
 * ProductRssWriter builds &lt;link&gt;, &lt;guid&gt; and the atom:link href on
 * that value, so a feed served from http://host/fmprod/ advertised links like
 * "/product?productId=..." -- every link in every JAX-RS RSS feed was
 * relative, and guid, which aggregators use as an item's stable identity, was
 * not a URI at all. Shared by DatasetRssWriter, ReferenceRssWriter,
 * TransfersRssWriter and RdfWriter.
 */
public class RssWriterBaseUriTest extends TestCase {

    public RssWriterBaseUriTest(String id) {
        super(id);
    }

    /** A concrete RssWriter; only getBaseUri is under test. */
    private static final class StubWriter extends RssWriter {
    }

    /**
     * uriInfo is a private @Context field, so it is set directly. UriInfo has
     * a large surface and only getBaseUri is needed, so it is a proxy rather
     * than a hand-written stub.
     */
    private static String baseUriFor(final String base) throws Exception {
        UriInfo uriInfo = (UriInfo) Proxy.newProxyInstance(
                UriInfo.class.getClassLoader(),
                new Class<?>[] { UriInfo.class },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getBaseUri".equals(method.getName())) {
                            return URI.create(base);
                        }
                        return null;
                    }
                });

        StubWriter writer = new StubWriter();
        Field field = RssWriter.class.getDeclaredField("uriInfo");
        field.setAccessible(true);
        field.set(writer, uriInfo);
        return writer.getBaseUri();
    }

    /** A base URI with a trailing slash keeps everything but the slash. */
    public void testATrailingSlashIsTrimmedAndTheRestKept() throws Exception {
        assertEquals("http://host/fmprod",
                baseUriFor("http://host/fmprod/"));
    }

    /** One without a trailing slash is returned as it stands. */
    public void testABaseUriWithoutATrailingSlashIsKept() throws Exception {
        assertEquals("http://host/fmprod",
                baseUriFor("http://host/fmprod"));
    }

    /** A link built on it addresses the service rather than being relative. */
    public void testALinkBuiltOnItAddressesTheService() throws Exception {
        String link = baseUriFor("http://host/fmprod/") + "/product?productId=1";

        assertTrue("the link is relative: " + link,
                link.startsWith("http://"));
        assertEquals("http://host/fmprod/product?productId=1", link);
    }

    /** A bare host is handled too. */
    public void testABareHostIsHandled() throws Exception {
        assertEquals("http://host", baseUriFor("http://host/"));
    }
}
