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

import java.util.HashSet;
import java.util.Set;

import junit.framework.TestCase;

/**
 * Element overrode hashCode without equals, so two identical definitions
 * occupied two slots in a set, and hashCode dereferenced an id that a freshly
 * constructed element has not been given yet.
 */
public class TestElementEquality extends TestCase {

    private static Element element(String id, String name) {
        return new Element(id, name, "dc", "an element", "type", "unit");
    }

    public void testFreshlyConstructedElementCanBeHashed() {
        new Element().hashCode();
    }

    public void testIdenticalElementsAreEqual() {
        assertEquals(element("1", "Filename"), element("1", "Filename"));
    }

    public void testIdenticalElementsShareAHashCode() {
        assertEquals(element("1", "Filename").hashCode(),
                element("1", "Filename").hashCode());
    }

    public void testASetKeepsOneOfTwoIdenticalElements() {
        Set<Element> elements = new HashSet<Element>();
        elements.add(element("1", "Filename"));
        elements.add(element("1", "Filename"));
        assertEquals("identical definitions occupied two slots", 1, elements.size());
    }

    public void testElementsWithDifferentIdsDiffer() {
        assertFalse(element("1", "Filename").equals(element("2", "Filename")));
    }

    public void testElementsWithDifferentNamesDiffer() {
        assertFalse(element("1", "Filename").equals(element("1", "ProductType")));
    }

    public void testAnElementDoesNotEqualSomethingElse() {
        assertFalse(element("1", "Filename").equals("1"));
    }
}
