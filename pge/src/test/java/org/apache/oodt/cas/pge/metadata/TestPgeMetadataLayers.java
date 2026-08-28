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

package org.apache.oodt.cas.pge.metadata;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.pge.metadata.PgeMetadata.Type;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Key-link cycles, and which metadata layer wins.
 */
public class TestPgeMetadataLayers {

    private static PgeMetadata pgeMetadata() {
        return new PgeMetadata(new Metadata(), new Metadata());
    }

    // ---- #112: key links must terminate ---------------------------------

    /** The smallest possible cycle: a key linked to itself. */
    @Test(timeout = 10000L)
    public void testAKeyLinkedToItselfIsRefused() {
        PgeMetadata met = pgeMetadata();

        try {
            met.linkKey("a", "a");
            fail("a self-link was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cycle"));
        }
    }

    /** a -> b -> a does the same. */
    @Test(timeout = 10000L)
    public void testATwoStepCycleIsRefused() {
        PgeMetadata met = pgeMetadata();
        met.linkKey("a", "b");

        try {
            met.linkKey("b", "a");
            fail("a two-step cycle was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cycle"));
        }
    }

    /** An ordinary chain of links still resolves. */
    @Test(timeout = 10000L)
    public void testAChainOfLinksStillResolves() {
        PgeMetadata met = pgeMetadata();
        met.linkKey("a", "b");
        met.linkKey("b", "c");

        assertEquals("c", met.resolveKey("a"));
        assertEquals(2, met.getReferenceKeyPath("a").size());
    }

    /** A key that is not a link resolves to itself. */
    @Test(timeout = 10000L)
    public void testAPlainKeyResolvesToItself() {
        assertEquals("plain", pgeMetadata().resolveKey("plain"));
        assertTrue(pgeMetadata().getReferenceKeyPath("plain").isEmpty());
    }

    // ---- #121.1: which layer wins ---------------------------------------

    /**
     * getAllMetadata(key, types...) returns the first listed type that holds
     * the key; asMetadata(types...) applied each layer in turn with
     * replaceMetadata, so the last listed type won and the same argument list
     * gave the two methods opposite answers. The asMetadata javadoc states
     * the first ordering explicitly, with a worked example.
     */
    @Test
    public void testAsMetadataAgreesWithGetAllMetadataOnPrecedence() {
        Metadata staticMet = new Metadata();
        staticMet.addMetadata("key", "staticValue");
        PgeMetadata met = new PgeMetadata(staticMet, new Metadata());
        met.replaceMetadata("key", "localValue");

        assertEquals("staticValue",
                met.getAllMetadata("key", Type.STATIC, Type.LOCAL).get(0));
        assertEquals("the two methods disagree about which layer wins",
                "staticValue",
                met.asMetadata(Type.STATIC, Type.LOCAL).getMetadata("key"));
    }

    /** and the other way round. */
    @Test
    public void testTheFirstListedTypeWinsEitherWayRound() {
        Metadata staticMet = new Metadata();
        staticMet.addMetadata("key", "staticValue");
        PgeMetadata met = new PgeMetadata(staticMet, new Metadata());
        met.replaceMetadata("key", "localValue");

        assertEquals("localValue",
                met.asMetadata(Type.LOCAL, Type.STATIC).getMetadata("key"));
    }

    /**
     * The no-argument form is unchanged. DEFAULT_COMBINE_ORDER is already the
     * exact reverse of DEFAULT_QUERY_ORDER, which is why the two agreed by
     * accident when no types were passed -- and every existing caller of the
     * no-argument form relies on that.
     */
    @Test
    public void testTheNoArgumentFormIsUnchanged() {
        Metadata staticMet = new Metadata();
        staticMet.addMetadata("key", "staticValue");
        PgeMetadata met = new PgeMetadata(staticMet, new Metadata());
        met.replaceMetadata("key", "localValue");

        assertEquals(met.getAllMetadata("key").get(0),
                met.asMetadata().getMetadata("key"));
    }

    // ---- #121.2: committing a marked key whose value has gone -----------

    /**
     * The documented sequence: set a local value, mark it dynamic, then link
     * that key to one held in STATIC. linkKey removes the LOCAL value as it
     * creates the link, so the marked key is guaranteed absent by the time
     * this runs -- and Metadata.getAllMetadata returns null for a key it does
     * not hold, which replaceMetadata passed to values.addAll.
     */
    @Test
    public void testCommittingAMarkedKeyWhoseValueHasGoneDoesNotThrow() {
        Metadata staticMet = new Metadata();
        staticMet.addMetadata("target", "staticValue");
        PgeMetadata met = new PgeMetadata(staticMet, new Metadata());

        met.replaceMetadata("marked", "localValue");
        met.markAsDynamicMetadataKey("marked");
        met.linkKey("marked", "target");

        met.commitMarkedDynamicMetadataKeys();

        assertTrue("the key should no longer be marked",
                met.getMarkedAsDynamicMetadataKeys().isEmpty());
    }

    /** A marked key that does still hold a value is moved into DYNAMIC. */
    @Test
    public void testAMarkedKeyWithAValueIsMovedIntoDynamic() {
        PgeMetadata met = pgeMetadata();
        met.replaceMetadata("marked", "localValue");
        met.markAsDynamicMetadataKey("marked");

        met.commitMarkedDynamicMetadataKeys();

        assertEquals("localValue",
                met.getAllMetadata("marked", Type.DYNAMIC).get(0));
    }
}
