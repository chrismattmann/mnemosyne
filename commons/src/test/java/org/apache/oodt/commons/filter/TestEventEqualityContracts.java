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
package org.apache.oodt.commons.filter;

import java.util.HashSet;
import java.util.Set;

import junit.framework.TestCase;

/**
 * equals and hashCode have to agree, and equals has to be symmetric. Where
 * they do not, a HashSet quietly keeps duplicates and a lookup misses an entry
 * that is present.
 */
public class TestEventEqualityContracts extends TestCase {

    /** Equal objects must have equal hash codes. */
    public void testEqualTimeEventsHaveEqualHashCodes() {
        TimeEvent a = new TimeEvent(0L, 0L, 0.0);
        TimeEvent b = new TimeEvent(0L, 0L, 1.0);

        assertEquals("these compare equal", a, b);
        assertEquals("so they must hash alike", a.hashCode(), b.hashCode());
    }

    /** The consequence: a set keeps both. */
    public void testASetDoesNotKeepTwoEqualTimeEvents() {
        Set<TimeEvent> events = new HashSet<TimeEvent>();
        events.add(new TimeEvent(0L, 0L, 0.0));
        events.add(new TimeEvent(0L, 0L, 1.0));
        assertEquals("equal events occupied two slots", 1, events.size());
    }

    /** equals must be symmetric, including across a subclass. */
    public void testTimeEventEqualityIsSymmetricWithItsSubclass() {
        TimeEvent plain = new TimeEvent(0L, 0L, 0.0);
        ObjectTimeEvent<String> withPayload = new ObjectTimeEvent<String>(0L, 0L, 0.0, "x");

        assertEquals("a.equals(b) and b.equals(a) disagree",
                plain.equals(withPayload), withPayload.equals(plain));
    }

    /** The payload may be null; the class's own hashCode already allows for it. */
    public void testObjectTimeEventToleratesANullPayload() {
        ObjectTimeEvent<String> a = new ObjectTimeEvent<String>(0L, 0L, 0.0, null);
        ObjectTimeEvent<String> b = new ObjectTimeEvent<String>(0L, 0L, 0.0, null);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    public void testObjectTimeEventsWithDifferentPayloadsDiffer() {
        ObjectTimeEvent<String> a = new ObjectTimeEvent<String>(0L, 0L, 0.0, "x");
        ObjectTimeEvent<String> b = new ObjectTimeEvent<String>(0L, 0L, 0.0, "y");
        assertFalse(a.equals(b));
    }

    /** A null payload must not be equal to a present one, in either direction. */
    public void testNullAndPresentPayloadsAreNotEqual() {
        ObjectTimeEvent<String> withNull = new ObjectTimeEvent<String>(0L, 0L, 0.0, null);
        ObjectTimeEvent<String> withValue = new ObjectTimeEvent<String>(0L, 0L, 0.0, "x");

        assertFalse(withNull.equals(withValue));
        assertFalse(withValue.equals(withNull));
    }
}
