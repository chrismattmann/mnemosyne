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

package org.apache.oodt.cas.resource.structs;

import org.junit.Test;

import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import static org.junit.Assert.*;

/**
 * getMetadata iterated props.values() and used each value as a key, so the
 * map came back keyed by values rather than names -- and each entry held
 * props.getProperty(someValue), which is null for anything not coincidentally
 * also a key.
 */
public class TestNameValueJobInputMetadata {

    private static NameValueJobInput inputOf(String... pairs) {
        Properties props = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            props.setProperty(pairs[i], pairs[i + 1]);
        }
        NameValueJobInput in = new NameValueJobInput();
        in.configure(props);
        return in;
    }

    /** The shrunk counterexample from the issue. */
    @Test
    public void testASinglePairIsKeyedByItsName() {
        Map<String, Vector<String>> met = inputOf("0", "").getMetadata();

        assertTrue("there is no entry under the name at all", met.containsKey("0"));
        assertEquals(1, met.get("0").size());
        assertEquals("", met.get("0").get(0));
    }

    @Test
    public void testEveryPairIsKeyedByItsName() {
        Map<String, Vector<String>> met =
                inputOf("alpha", "one", "beta", "two").getMetadata();

        assertEquals(2, met.size());
        assertEquals("one", met.get("alpha").get(0));
        assertEquals("two", met.get("beta").get(0));
    }

    /** and no entry appears under a value. */
    @Test
    public void testNothingIsKeyedByAValue() {
        Map<String, Vector<String>> met = inputOf("alpha", "one").getMetadata();

        assertFalse("the map is keyed by the value", met.containsKey("one"));
    }

    @Test
    public void testNoPropertiesIsAnEmptyMap() {
        Map<String, Vector<String>> met = new NameValueJobInput().getMetadata();

        assertNotNull(met);
        assertTrue(met.isEmpty());
    }
}
