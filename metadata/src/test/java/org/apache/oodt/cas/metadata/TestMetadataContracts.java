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

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The container's own contracts: equality, hashing, and whether reading
 * changes anything. TestMetadata covers flat keys on the happy path, which is
 * why these stayed hidden -- each needs two containers built the same way, or
 * a group whose name collides with the sentinel's.
 */
public class TestMetadataContracts {

    // ---- #106: equals and hashCode ---------------------------------------

    /** The shrunk counterexample: one key, empty value. */
    @Test
    public void testEqualContainersHaveEqualHashCodes() {
        Metadata a = new Metadata();
        a.addMetadata("0", "");
        Metadata b = new Metadata();
        b.addMetadata("0", "");

        assertEquals(a, b);
        assertEquals("equal containers hash differently", a.hashCode(), b.hashCode());
    }

    /** Which is what a HashSet needs. */
    @Test
    public void testAContainerCanBeUsedInAHashSet() {
        Metadata a = new Metadata();
        a.addMetadata("key", "value");
        Metadata b = new Metadata();
        b.addMetadata("key", "value");

        Set<Metadata> set = new HashSet<Metadata>();
        set.add(a);

        assertTrue("a lookup missed an entry that is present", set.contains(b));
        set.add(b);
        assertEquals("an equal container was stored twice", 1, set.size());
    }

    /** and as a HashMap key. */
    @Test
    public void testAContainerCanBeUsedAsAMapKey() {
        Metadata a = new Metadata();
        a.addMetadata("key", "value");
        Metadata b = new Metadata();
        b.addMetadata("key", "value");

        Map<Metadata, String> map = new HashMap<Metadata, String>();
        map.put(a, "found");

        assertEquals("found", map.get(b));
    }

    /** getKeys() returns a List, and List.equals is ordered. */
    @Test
    public void testEqualityIgnoresTheOrderDistinctKeysWereInserted() {
        Metadata forwards = new Metadata();
        forwards.addMetadata("0", "");
        forwards.addMetadata(";", "");

        Metadata backwards = new Metadata();
        backwards.addMetadata(";", "");
        backwards.addMetadata("0", "");

        assertEquals("insertion order changed equality", forwards, backwards);
        assertEquals(forwards.hashCode(), backwards.hashCode());
    }

    /**
     * equals walked getKeys(), which is the root's children only, so two
     * containers differing solely in a nested group's values compared equal.
     */
    @Test
    public void testContainersDifferingOnlyInANestedGroupAreNotEqual() {
        Metadata a = new Metadata();
        a.addMetadata("group/key", "one");
        Metadata b = new Metadata();
        b.addMetadata("group/key", "two");

        assertFalse("a difference below the root was invisible to equals",
                a.equals(b));
    }

    /** The order of a key's own values stays significant: it is data. */
    @Test
    public void testTheOrderOfAKeysValuesIsStillSignificant() {
        Metadata a = new Metadata();
        a.addMetadata("k", "one");
        a.addMetadata("k", "two");

        Metadata b = new Metadata();
        b.addMetadata("k", "two");
        b.addMetadata("k", "one");

        assertFalse(a.equals(b));
    }

    /** Different content is still unequal. */
    @Test
    public void testDifferentContentIsNotEqual() {
        Metadata a = new Metadata();
        a.addMetadata("k", "one");
        Metadata b = new Metadata();
        b.addMetadata("k", "two");

        assertFalse(a.equals(b));
    }

    // ---- #107: reading must not write ------------------------------------

    /** The shrunk counterexample: absentGroup = "0". */
    @Test
    public void testReadingTheKeysOfAGroupDoesNotCreateIt() {
        Metadata m = new Metadata();
        assertFalse(m.containsGroup("0"));

        m.getKeys("0");

        assertFalse("a read created the group", m.containsGroup("0"));
    }

    @Test
    public void testReadingAllKeysOfAGroupDoesNotCreateIt() {
        Metadata m = new Metadata();

        m.getAllKeys("nope");

        assertFalse("a read created the group", m.containsGroup("nope"));
    }

    @Test
    public void testReadingTheSubGroupsOfAGroupDoesNotCreateIt() {
        Metadata m = new Metadata();

        m.getGroups("nope");

        assertFalse("a read created the group", m.containsGroup("nope"));
    }

    /** So a container does not grow every time it is inspected. */
    @Test
    public void testInspectingAContainerDoesNotGrowIt() {
        Metadata m = new Metadata();
        m.addMetadata("real/key", "value");
        int before = m.getGroups().size();

        for (int i = 0; i < 10; i++) {
            m.getKeys("absent-" + i);
            m.getAllKeys("absent-" + i);
            m.getGroups("absent-" + i);
        }

        assertEquals("the container grew while being read", before,
                m.getGroups().size());
    }

    /** A group that is there still reads back. */
    @Test
    public void testReadingAGroupThatExistsStillWorks() {
        Metadata m = new Metadata();
        m.addMetadata("group/key", "value");

        assertTrue(m.getAllKeys("group").contains("group/key"));
    }

    // ---- #113: a group named 'root' --------------------------------------

    /**
     * getFullPath decided it had reached the top by comparing the parent's
     * name against ROOT_GROUP_NAME, the string "root", so a user group of
     * that name satisfied the test and every descendant reported a path with
     * a level missing.
     */
    @Test
    public void testAGroupNamedRootKeepsItsFullPath() {
        Metadata m = new Metadata();
        m.addMetadata("root/x", "v");

        assertTrue("the path was truncated: " + m.getAllKeys(),
                m.getAllKeys().contains("root/x"));
    }

    /** Every key a container lists can be read back. */
    @Test
    public void testEveryEnumeratedKeyCanBeReadBack() {
        Metadata m = new Metadata();
        m.addMetadata("root/x", "v");

        for (String key : m.getAllKeys()) {
            assertNotNull("enumerated but unreadable: " + key,
                    m.getMetadata(key));
        }
    }

    /** The copy constructor turned the truncation into an NPE. */
    @Test
    public void testAContainerWithAGroupNamedRootCanBeCopied() {
        Metadata m = new Metadata();
        m.addMetadata("root/x", "v");

        Metadata copy = new Metadata(m);

        assertEquals("v", copy.getMetadata("root/x"));
        assertEquals(m, copy);
    }

    /** Nested one further down, which is the shrunk case root/root. */
    @Test
    public void testAGroupNamedRootBelowAnotherOne() {
        Metadata m = new Metadata();
        m.addMetadata("root/root", "v");

        assertTrue(m.getAllKeys().contains("root/root"));
        assertEquals("v", new Metadata(m).getMetadata("root/root"));
    }

    /** The control from the issue: the same shape under another name. */
    @Test
    public void testAnOrdinaryGroupIsUnaffected() {
        Metadata m = new Metadata();
        m.addMetadata("other/x", "v");

        assertTrue(m.getAllKeys().contains("other/x"));
        assertEquals("v", new Metadata(m).getMetadata("other/x"));
    }
}
