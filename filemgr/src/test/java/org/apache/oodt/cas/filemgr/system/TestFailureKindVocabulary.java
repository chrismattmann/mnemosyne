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
package org.apache.oodt.cas.filemgr.system;

import java.util.Set;
import java.util.TreeSet;

import org.apache.oodt.commons.rpc.FailureKinds;
import org.apache.oodt.cas.filemgr.structs.avrotypes.OodtFailureKind;

import junit.framework.TestCase;

/**
 * Each protocol declares its own copy of the failure vocabulary, because a
 * protocol's types live in its own namespace. FailureKinds is where the
 * vocabulary is decided; this is what stops this copy drifting from it.
 *
 * A drift matters: the server does OodtFailureKind.valueOf(classify(e)), so a
 * kind that FailureKinds returns and this enum does not declare becomes an
 * IllegalArgumentException thrown while reporting some other failure.
 */
public class TestFailureKindVocabulary extends TestCase {

    public void testProtocolDeclaresEveryKindTheClassifierCanReturn() {
        Set<String> declared = new TreeSet<String>();
        for (OodtFailureKind kind : OodtFailureKind.values()) {
            declared.add(kind.name());
        }
        Set<String> missing = new TreeSet<String>(FailureKinds.all());
        missing.removeAll(declared);
        assertTrue("the protocol does not declare " + missing
                + ", which the classifier can return", missing.isEmpty());
    }

    public void testProtocolDeclaresNothingExtra() {
        Set<String> extra = new TreeSet<String>();
        for (OodtFailureKind kind : OodtFailureKind.values()) {
            extra.add(kind.name());
        }
        extra.removeAll(FailureKinds.all());
        assertTrue("the protocol declares " + extra
                + ", which FailureKinds does not know about", extra.isEmpty());
    }

    /** Every declared kind must survive valueOf, which is how the server uses it. */
    public void testEveryKindResolvesByName() {
        for (String kind : FailureKinds.all()) {
            assertNotNull(kind, OodtFailureKind.valueOf(kind));
        }
    }
}
