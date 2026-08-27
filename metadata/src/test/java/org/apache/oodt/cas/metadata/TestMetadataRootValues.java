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
package org.apache.oodt.cas.metadata;

import junit.framework.TestCase;

/**
 * Values held directly on a Metadata's root, rather than on one of its groups.
 *
 * The container already accepted them: getGroup resolves the empty key to the
 * root exactly as it does null. What it did not do was let you find them again
 * or take them away.
 */
public class TestMetadataRootValues extends TestCase {

    /** Whatever went in has to be findable by enumeration. */
    public void testAValueAddedAtTheRootIsEnumerated() {
        Metadata met = new Metadata();
        met.addMetadata("", "value");

        assertTrue("stored but not listed by getKeys", met.getKeys().contains(""));
        assertTrue("stored but not listed by getAllKeys", met.getAllKeys().contains(""));
    }

    /** It was already readable; that must not change. */
    public void testAValueAddedAtTheRootIsStillReadable() {
        Metadata met = new Metadata();
        met.addMetadata("", "value");

        assertTrue(met.containsKey(""));
        assertEquals("value", met.getMetadata(""));
    }

    /** Removing it used to dereference the root's absent parent. */
    public void testAValueAddedAtTheRootCanBeRemoved() {
        Metadata met = new Metadata();
        met.addMetadata("", "value");

        met.removeMetadata("");

        assertFalse(met.containsKey(""));
        assertFalse(met.getKeys().contains(""));
    }

    /** An ordinary Metadata must not grow an empty key from nowhere. */
    public void testAnOrdinaryMetadataHasNoEmptyKey() {
        Metadata met = new Metadata();
        met.addMetadata("Filename", "foo.txt");

        assertFalse(met.getKeys().contains(""));
        assertFalse(met.getAllKeys().contains(""));
        assertEquals(1, met.getKeys().size());
    }

    /** Ordinary keys must be unaffected throughout. */
    public void testOrdinaryKeysAreUnaffected() {
        Metadata met = new Metadata();
        met.addMetadata("Filename", "foo.txt");
        met.addMetadata("group/Nested", "v");

        assertEquals("foo.txt", met.getMetadata("Filename"));
        assertEquals("v", met.getMetadata("group/Nested"));
        met.removeMetadata("Filename");
        assertFalse(met.containsKey("Filename"));
        assertEquals("v", met.getMetadata("group/Nested"));
    }

    /**
     * getSubMetadata promises "group and all keys below it", but copied only
     * the children, so anything held on the group itself was dropped.
     */
    public void testSubMetadataKeepsTheGroupsOwnValues() {
        Metadata met = new Metadata();
        met.addMetadata("group", "the group's own value");
        met.addMetadata("group/child", "a child value");

        Metadata sub = met.getSubMetadata("group");

        assertEquals("the child was lost", "a child value", sub.getMetadata("child"));
        assertEquals("the group's own value was dropped",
                "the group's own value", sub.getMetadata(""));
    }

    public void testSubMetadataOfAGroupWithNoOwnValuesIsUnchanged() {
        Metadata met = new Metadata();
        met.addMetadata("group/child", "a child value");

        Metadata sub = met.getSubMetadata("group");
        assertEquals("a child value", sub.getMetadata("child"));
        assertFalse(sub.getKeys().contains(""));
    }
}
