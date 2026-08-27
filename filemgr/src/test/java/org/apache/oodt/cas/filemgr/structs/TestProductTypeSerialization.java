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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.oodt.cas.filemgr.structs.type.TypeHandler;
import org.apache.oodt.cas.metadata.Metadata;

import junit.framework.TestCase;

/**
 * ProductType declares Serializable, but declaring it is not the same as being
 * serializable: writing an object walks its fields, and a field whose type is
 * not serializable fails the whole write. OPSUI stores pages in Wicket's
 * session, which serializes them, so a ProductType carrying type metadata
 * could not be held on a page at all.
 */
public class TestProductTypeSerialization extends TestCase {

    private static ProductType populatedType() {
        ProductType type = new ProductType();
        type.setProductTypeId("urn:oodt:GenericFile");
        type.setName("GenericFile");
        type.setDescription("A generic file");
        type.setProductRepositoryPath("file:///archive");
        type.setVersioner("org.apache.oodt.cas.filemgr.versioning.BasicVersioner");

        Metadata met = new Metadata();
        met.addMetadata("Owner", "cas");
        met.addMetadata("Mission", "OODT");
        type.setTypeMetadata(met);

        List<ExtractorSpec> extractors = new ArrayList<ExtractorSpec>();
        ExtractorSpec spec = new ExtractorSpec();
        spec.setClassName("org.apache.oodt.cas.filemgr.metadata.extractors.CoreMetExtractor");
        extractors.add(spec);
        type.setExtractors(extractors);

        return type;
    }

    private static Object roundTrip(Object o) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bytes);
        try {
            out.writeObject(o);
        } finally {
            out.close();
        }
        ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()));
        try {
            return in.readObject();
        } finally {
            in.close();
        }
    }

    /** The whole point: a fully populated ProductType survives a round trip. */
    public void testProductTypeRoundTrips() throws Exception {
        ProductType original = populatedType();
        ProductType copy = (ProductType) roundTrip(original);

        assertNotNull(copy);
        assertEquals(original.getProductTypeId(), copy.getProductTypeId());
        assertEquals(original.getName(), copy.getName());
        assertEquals(original.getDescription(), copy.getDescription());
        assertEquals(original.getProductRepositoryPath(), copy.getProductRepositoryPath());
        assertEquals(original.getVersioner(), copy.getVersioner());
    }

    /** The metadata has to come back with it, not merely not explode. */
    public void testTypeMetadataSurvives() throws Exception {
        ProductType copy = (ProductType) roundTrip(populatedType());

        Metadata met = copy.getTypeMetadata();
        assertNotNull("type metadata was lost in serialization", met);
        assertEquals("cas", met.getMetadata("Owner"));
        assertEquals("OODT", met.getMetadata("Mission"));
    }

    public void testExtractorsSurvive() throws Exception {
        ProductType copy = (ProductType) roundTrip(populatedType());

        List<ExtractorSpec> extractors = copy.getExtractors();
        assertNotNull(extractors);
        assertEquals(1, extractors.size());
        assertEquals("org.apache.oodt.cas.filemgr.metadata.extractors.CoreMetExtractor",
                extractors.get(0).getClassName());
    }

    /** Metadata is a field of ProductType, so it has to travel on its own too. */
    public void testMetadataIsSerializableInItsOwnRight() throws Exception {
        Metadata met = new Metadata();
        met.addMetadata("Key", "value");
        met.addMetadata("Multi", "one");
        met.addMetadata("Multi", "two");

        Metadata copy = (Metadata) roundTrip(met);
        assertEquals("value", copy.getMetadata("Key"));
        assertEquals(2, copy.getAllMetadata("Multi").size());
    }

    /** The handlers list is the other field whose element type was not serializable. */
    public void testTypeHandlersSurvive() throws Exception {
        ProductType type = populatedType();
        List<TypeHandler> handlers = new ArrayList<TypeHandler>();
        handlers.add(new SerializableTestHandler("CAS.ProductName"));
        type.setHandlers(handlers);

        ProductType copy = (ProductType) roundTrip(type);
        assertNotNull("handlers were lost in serialization", copy.getHandlers());
        assertEquals(1, copy.getHandlers().size());
        assertEquals("CAS.ProductName", copy.getHandlers().get(0).getElementName());
    }

    /** A minimal concrete TypeHandler; the real ones live behind an abstract class. */
    private static class SerializableTestHandler extends TypeHandler {
        private static final long serialVersionUID = 1L;

        SerializableTestHandler(String elementName) {
            setElementName(elementName);
        }

        public void postGetMetadataHandle(Metadata metadata) { }
        public void preAddMetadataHandle(Metadata metadata) { }
        protected QueryCriteria handleRangeQueryCriteria(RangeQueryCriteria qc) { return qc; }
        protected QueryCriteria handleTermQueryCriteria(TermQueryCriteria qc) { return qc; }
    }
}
