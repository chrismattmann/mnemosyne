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

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.product.jaxrs.resources.ProductResource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Properties of the links an RSS response carries.
 *
 * <p>{@link RssWriter#getBaseUri()} is the one place every RSS writer in this
 * package works out where the service lives; every {@code link}, {@code guid}
 * and {@code atom:link} in every feed is built by prefixing it. Its documented
 * job is to give back "the HTTP servlet request URL up to the final '/'", so
 * the properties below are stated over the links a feed reader is handed.
 *
 * <p>The writer takes its {@code UriInfo} by JAX-RS {@code @Context} injection.
 * There is no setter, so the test plays the part of the container and assigns
 * the field directly; nothing else about the writer is stubbed.
 */
class RssWriterPropertyTest {

  private static final List<String> WORD_CHARS =
      Arrays.asList("a", "b", "c", "d", "f", "m", "p", "r", "1", "2");

  private static Generator<String> word() {
    return lists(sampledFrom(WORD_CHARS)).minSize(1).maxSize(6).map(cs -> String.join("", cs));
  }

  /** A {@code UriInfo} that knows its base URI and has no query parameters. */
  private static UriInfo uriInfoFor(final URI baseUri) {
    return (UriInfo)
        Proxy.newProxyInstance(
            RssWriterPropertyTest.class.getClassLoader(),
            new Class<?>[] {UriInfo.class},
            new InvocationHandler() {
              @Override
              public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getBaseUri".equals(method.getName())) {
                  return baseUri;
                }
                if ("getQueryParameters".equals(method.getName())) {
                  return (MultivaluedMap<String, String>)
                      new javax.ws.rs.core.MultivaluedHashMap<String, String>();
                }
                if ("toString".equals(method.getName())) {
                  return "UriInfo[" + baseUri + "]";
                }
                if ("hashCode".equals(method.getName())) {
                  return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                  return proxy == args[0];
                }
                return null;
              }
            });
  }

  private static void injectUriInfo(RssWriter writer, URI baseUri) throws Exception {
    Field field = RssWriter.class.getDeclaredField("uriInfo");
    field.setAccessible(true);
    field.set(writer, uriInfoFor(baseUri));
  }

  private static URI drawBaseUri(TestCase tc) {
    String host = tc.draw(word(), "host");
    String context = tc.draw(word(), "context");
    boolean trailingSlash = tc.draw(booleans(), "trailingSlash");
    return URI.create("http://" + host + ".example.org/" + context + (trailingSlash ? "/" : ""));
  }

  private static Product productWith(String id, String name, String typeName, int refCount) {
    ProductType type = new ProductType();
    type.setName(typeName);
    type.setProductTypeId(typeName + "Id");

    Product product = new Product();
    product.setProductId(id);
    product.setProductName(name);
    product.setProductStructure(Product.STRUCTURE_FLAT);
    product.setTransferStatus(Product.STATUS_RECEIVED);
    product.setProductType(type);

    List<Reference> refs = new ArrayList<Reference>();
    for (int i = 0; i < refCount; i++) {
      Reference ref = new Reference();
      ref.setOrigReference("file:/orig/" + name + "/" + i);
      ref.setDataStoreReference("file:/store/" + name + "/" + i);
      ref.setFileSize(i);
      refs.add(ref);
    }
    product.setProductReferences(refs);
    return product;
  }

  private static Document parse(byte[] xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
  }

  private static String firstChildText(Element parent, String name) {
    NodeList nodes = parent.getElementsByTagName(name);
    return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
  }

  /**
   * A path appended to the base returned by {@link RssWriter#getBaseUri()}
   * addresses the service. Every link in every RSS feed this package produces is
   * built that way, and a feed reader fetching one gets a fresh HTTP request
   * with no memory of where the feed came from — so the result has to be
   * absolute.
   */
  @HegelTest
  void aLinkBuiltOnTheBaseUriAddressesTheService(TestCase tc) throws Exception {
    URI baseUri = drawBaseUri(tc);
    String path = tc.draw(sampledFrom(Arrays.asList("product", "reference", "dataset")), "path");

    ProductRssWriter writer = new ProductRssWriter();
    injectUriInfo(writer, baseUri);

    String link = writer.getBaseUri() + path;

    assertTrue(
        URI.create(link).isAbsolute(),
        "the link [" + link + "] is relative; it cannot be followed away from the feed");
    assertEquals(baseUri.resolve(path).toString(), link);
  }

  /**
   * A product's RSS feed describes exactly the references the product has: one
   * item each, no more and no fewer, each with its own guid. A reader uses the
   * guid to tell one enclosure from another.
   */
  @HegelTest
  void everyReferenceGetsItsOwnItemInTheFeed(TestCase tc) throws Exception {
    URI baseUri = drawBaseUri(tc);
    String id = tc.draw(word(), "id");
    String name = tc.draw(word(), "name");
    String typeName = tc.draw(word(), "typeName");
    int refCount = tc.draw(dev.hegel.Generators.integers().min(0).max(6), "refCount");

    Product product = productWith(id, name, typeName, refCount);
    ProductResource resource =
        new ProductResource(product, new Metadata(), product.getProductReferences(), null);

    ProductRssWriter writer = new ProductRssWriter();
    injectUriInfo(writer, baseUri);

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    writer.writeTo(resource, null, null, null, null, null, bytes);

    Element channel = (Element) parse(bytes.toByteArray()).getElementsByTagName("channel").item(0);
    assertNotNull(channel, "the feed has no channel");
    assertEquals(name, firstChildText(channel, "title"));
    assertEquals(typeName, firstChildText(channel, "description"));

    NodeList items = channel.getElementsByTagName("item");
    assertEquals(refCount, items.getLength(), "the feed does not describe every reference");

    List<String> guids = new ArrayList<String>();
    for (int i = 0; i < items.getLength(); i++) {
      String guid = firstChildText((Element) items.item(i), "guid");
      assertTrue(!guids.contains(guid), "two items share the guid [" + guid + "]");
      guids.add(guid);
    }
  }
}
