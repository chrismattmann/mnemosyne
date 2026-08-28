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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Constructing a Reference used to build a whole Tika configuration.
 *
 * new Tika() loads the default parser set, and every ExternalParser in it
 * checks whether its tool exists by running it. One property run logged 4,105
 * "ffmpeg -version" spawns and about seven minutes on them -- from a
 * three-field value object whose signature promises no I/O. A hierarchical
 * product with a thousand files paid that a thousand times on ingest, and
 * where subprocess execution is restricted, constructing a value object
 * stalled or threw.
 *
 * None of it bought anything: Tika.detect(String) is name-based detection, so
 * the parser set was loaded and probed as a side effect of construction and
 * never consulted.
 */
public class TestReferenceMimeTypeCost {

    /**
     * The cost, asserted as a budget rather than a count of processes, which
     * is not observable from inside the JVM.
     *
     * A thousand references is a plausible hierarchical product. Building a
     * Tika configuration per reference took roughly a tenth of a second each
     * once the tool probes ran, so the old code could not come close to this;
     * name resolution against a shared repository is microseconds.
     */
    @Test(timeout = 30000L)
    public void testBuildingManyReferencesIsCheap() {
        long started = System.currentTimeMillis();

        for (int i = 0; i < 1000; i++) {
            new Reference("file:/staging/data" + i + ".txt",
                    "file:/archive/data" + i + ".txt", 100L);
        }

        long elapsed = System.currentTimeMillis() - started;
        assertTrue("a thousand references took " + elapsed + "ms; the mime "
                + "repository is being rebuilt per reference", elapsed < 10000L);
    }

    /** And the mime type is still resolved. */
    @Test
    public void testTheMimeTypeIsStillDetectedFromTheName() {
        Reference ref = new Reference("file:/staging/data.txt",
                "file:/archive/data.txt", 100L);

        assertNotNull(ref.getMimeType());
        assertEquals("text/plain", ref.getMimeType().getName());
    }

    /** A name with no recognisable extension still gets an answer. */
    @Test
    public void testAnUnknownExtensionStillGetsAMimeType() {
        Reference ref = new Reference("file:/staging/data.nosuchextension",
                "file:/archive/data.nosuchextension", 100L);

        assertNotNull(ref.getMimeType());
    }

    /** The explicit-mime-type constructor is unaffected. */
    @Test
    public void testAnExplicitMimeTypeIsKept() {
        Reference ref = new Reference("file:/staging/a", "file:/archive/a",
                100L, null);

        assertNull(ref.getMimeType());
    }
}
