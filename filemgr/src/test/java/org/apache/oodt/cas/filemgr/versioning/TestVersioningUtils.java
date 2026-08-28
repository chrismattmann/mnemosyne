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

package org.apache.oodt.cas.filemgr.versioning;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * getAbsolutePathFromUri catches URISyntaxException and returns null, and its
 * callers (FileManager:1087, FinalFileLocationExtractor) null-check rather
 * than catch -- so null-on-failure is the contract it is read as having. But
 * new File(URI) throws unchecked IllegalArgumentException for a URI that
 * parses and is still not a usable file path, which escaped that contract
 * entirely.
 */
public class TestVersioningUtils {

    private static void assertNullRatherThanThrowing(String uri) {
        try {
            assertNull("[" + uri + "] produced a path", 
                    VersioningUtils.getAbsolutePathFromUri(uri));
        } catch (RuntimeException e) {
            fail("[" + uri + "] threw " + e.getClass().getName()
                    + " where the contract is null: " + e.getMessage());
        }
    }

    /** A URI that parses cleanly but names no file. */
    @Test
    public void testANonFileSchemeReturnsNull() {
        assertNullRatherThanThrowing("http://example.org/somewhere");
    }

    /** An opaque URI -- no leading slash, so no path to take. */
    @Test
    public void testAnOpaqueUriReturnsNull() {
        assertNullRatherThanThrowing("mailto:someone@example.org");
    }

    /** A file URI carrying an authority. */
    @Test
    public void testAFileUriWithAnAuthorityReturnsNull() {
        assertNullRatherThanThrowing("file://somehost/tmp/data.dat");
    }

    /** A relative URI: no scheme at all. */
    @Test
    public void testARelativeUriReturnsNull() {
        assertNullRatherThanThrowing("data.dat");
    }

    /** Unparseable input keeps returning null, as it always did. */
    @Test
    public void testUnparseableInputStillReturnsNull() {
        assertNullRatherThanThrowing("file:// not a uri at all");
    }

    /** and an ordinary file URI still resolves. */
    @Test
    public void testAnOrdinaryFileUriStillResolves() {
        String path = new File("/tmp/data.dat").toURI().toString();
        assertEquals(new File("/tmp/data.dat").getAbsolutePath(),
                VersioningUtils.getAbsolutePathFromUri(path));
    }
}
