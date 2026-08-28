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

import org.apache.oodt.cas.filemgr.structs.Reference;

import org.junit.Test;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The pure path construction that decides where an ingested product is
 * archived. VersioningUtils has no direct unit tests.
 */
public class TestVersioningUtilsRefs {

    private static Reference ref(String orig, String dataStore) {
        Reference r = new Reference();
        r.setOrigReference(orig);
        r.setDataStoreReference(dataStore);
        return r;
    }

    // ---- #119.1: a repeated path segment --------------------------------

    /**
     * The child was located by searching for the *name* of the staging
     * directory inside its URL with indexOf, which finds the first
     * occurrence -- so when the directory's name also appeared earlier in the
     * path, the child was cut at the wrong segment and archived one level
     * deeper than the catalog and the versioner agree it should be.
     *
     * The shrunk counterexample: rootSegments = ["data", "data"].
     */
    @Test
    public void testARepeatedSegmentDoesNotReRootTheProduct() {
        List<Reference> refs = new ArrayList<Reference>();
        refs.add(ref("file:/data/data", "file:/archive/AProduct/"));
        refs.add(ref("file:/data/data/data", null));

        VersioningUtils.createBasicDataStoreRefsHierarchical(refs);

        assertEquals("file:/archive/AProduct/data",
                refs.get(1).getDataStoreReference());
    }

    /** A deeper repeat behaves the same way. */
    @Test
    public void testARepeatedSegmentDeeperDown() {
        List<Reference> refs = new ArrayList<Reference>();
        refs.add(ref("file:/x/2024/2024", "file:/archive/P/"));
        refs.add(ref("file:/x/2024/2024/2024/f.dat", null));

        VersioningUtils.createBasicDataStoreRefsHierarchical(refs);

        assertEquals("file:/archive/P/2024/f.dat",
                refs.get(1).getDataStoreReference());
    }

    /** The ordinary case, which always worked, still works. */
    @Test
    public void testAnOrdinaryHierarchyIsUnchanged() {
        List<Reference> refs = new ArrayList<Reference>();
        refs.add(ref("file:/www/folder1", "file:/www/myfolder/product1/"));
        refs.add(ref("file:/www/folder1/file1", null));
        refs.add(ref("file:/www/folder1/folder2/file3", null));

        VersioningUtils.createBasicDataStoreRefsHierarchical(refs);

        assertEquals("file:/www/myfolder/product1/file1",
                refs.get(1).getDataStoreReference());
        assertEquals("file:/www/myfolder/product1/folder2/file3",
                refs.get(2).getDataStoreReference());
    }

    /** Every child stays below the archive root. */
    @Test
    public void testEveryChildStaysBelowTheArchiveRoot() {
        List<Reference> refs = new ArrayList<Reference>();
        refs.add(ref("file:/data/data", "file:/archive/AProduct/"));
        refs.add(ref("file:/data/data/a", null));
        refs.add(ref("file:/data/data/b/c", null));

        VersioningUtils.createBasicDataStoreRefsHierarchical(refs);

        for (int i = 1; i < refs.size(); i++) {
            assertTrue(refs.get(i).getDataStoreReference(),
                    refs.get(i).getDataStoreReference()
                        .startsWith("file:/archive/AProduct/"));
        }
    }

    // ---- #119.2: flat references must be usable URIs ---------------------

    /**
     * The file name was decoded out of the incoming URI and concatenated back
     * in raw, so a file named "%" produced "file:/archive/AProduct/%" -- not
     * a parseable URI. LocalDataTransferer does
     * new File(new URI(getDataStoreReference())) on this value, so it threw
     * at transfer time, after the catalog entry had been made.
     */
    @Test
    public void testAFileNamedPercentProducesAUsableUri() throws Exception {
        List<Reference> refs = new ArrayList<Reference>();
        refs.add(ref("file:/staging/%25", null));

        VersioningUtils.createBasicDataStoreRefsFlat("AProduct", "file:/archive", refs);

        String dataStoreRef = refs.get(0).getDataStoreReference();
        assertNotNull(dataStoreRef);
        // the assertion is that this does not throw
        File resolved = new File(new URI(dataStoreRef));
        assertEquals("%", resolved.getName());
    }

    /** A space fails the same way. */
    @Test
    public void testAFileNameWithASpaceProducesAUsableUri() throws Exception {
        List<Reference> refs = new ArrayList<Reference>();
        refs.add(ref("file:/staging/a%20b.dat", null));

        VersioningUtils.createBasicDataStoreRefsFlat("AProduct", "file:/archive", refs);

        File resolved = new File(new URI(refs.get(0).getDataStoreReference()));
        assertEquals("a b.dat", resolved.getName());
    }

    /**
     * A "#" was worse than the others: it parsed, but everything after it
     * became a fragment, so the transferer wrote to a different path than the
     * one recorded.
     */
    @Test
    public void testAFileNameWithAHashResolvesToTheRecordedPath() throws Exception {
        List<Reference> refs = new ArrayList<Reference>();
        refs.add(ref("file:/staging/a%23b.dat", null));

        VersioningUtils.createBasicDataStoreRefsFlat("AProduct", "file:/archive", refs);

        File resolved = new File(new URI(refs.get(0).getDataStoreReference()));
        assertEquals("a#b.dat", resolved.getName());
    }

    /** An ordinary name is unchanged. */
    @Test
    public void testAnOrdinaryFlatReferenceIsUnchanged() throws Exception {
        List<Reference> refs = new ArrayList<Reference>();
        refs.add(ref("file:/staging/data.dat", null));

        VersioningUtils.createBasicDataStoreRefsFlat("AProduct", "file:/archive", refs);

        assertEquals("file:/archive/AProduct/data.dat",
                refs.get(0).getDataStoreReference());
        assertEquals("data.dat",
                new File(new URI(refs.get(0).getDataStoreReference())).getName());
    }
}
