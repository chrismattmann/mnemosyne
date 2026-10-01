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

//JDK imports
import java.io.File;

//OODT imports
import org.apache.oodt.cas.filemgr.catalog.MockCatalog;
import org.apache.oodt.cas.filemgr.util.FileRefs;

//JUnit imports
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the File Manager does with a destination it cannot resolve.
 *
 * <p>This is the line that wrote the file. A Windows node sent
 * {@code C:\Users\mattmann\bt-w1\data\translated-catalog\chunk-00001.json\chunk-00001.json}
 * as its destination; on a POSIX File Manager that is not an absolute path, so
 * {@code new File(String)} resolved it against the process working directory
 * and the whole foreign path became one filename -- a backslash being a
 * perfectly legal filename character here. 231485 bytes were written, true was
 * returned, and the product was catalogued RECEIVED.</p>
 *
 * <p>These run on any platform; the one that needs a foreign path says so and
 * takes its example from the platform it is on.</p>
 */
public class TestFileManagerForeignDestinations {

    private static final boolean POSIX = File.separatorChar == '/';

    /** The path from the incident, verbatim. */
    private static final String FROM_THE_INCIDENT =
        "C:\\Users\\mattmann\\bt-w1\\data\\translated-catalog"
        + "\\chunk-00001.json\\chunk-00001.json";

    private FileManager fileManager;
    private File archive;
    private File before;

    @Before
    public void setUp() throws Exception {
        fileManager = new FileManager();
        fileManager.setCatalog(new MockCatalog());
        archive = File.createTempFile("archive", "");
        assertTrue(archive.delete());
        assertTrue(archive.mkdirs());
        before = new File(System.getProperty("user.dir"));
    }

    @After
    public void tearDown() {
        delete(archive);
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                delete(kid);
            }
        }
        f.delete();
    }

    private static byte[] someBytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    // ------------------------------------------------------- what it does ---

    @Test
    public void testAFileReferenceIsWrittenWhereItNames() throws Exception {
        File destination = new File(new File(archive, "AProduct"), "x.bin");
        byte[] data = someBytes(64);

        assertTrue("the file manager refused a destination in its own archive",
            fileManager.transferFile(FileRefs.toReference(destination), data,
                0, data.length));

        assertTrue("nothing was written to " + destination,
            destination.exists());
        assertEquals(data.length, destination.length());
    }

    @Test
    public void testTheParentDirectoryIsCreated() throws Exception {
        // Not merely a convenience: this is where lastIndexOf("/") used to cut
        // the path apart, which throws on a Windows File Manager because an
        // absolute path there contains no forward slash at all.
        File destination = new File(
            new File(new File(archive, "deep"), "deeper"), "x.bin");
        byte[] data = someBytes(8);

        assertTrue(fileManager.transferFile(FileRefs.toReference(destination),
            data, 0, data.length));
        assertTrue(destination.exists());
    }

    /**
     * A legacy client's native path still works.
     *
     * <p>Before references travelled, this was the only form. A client that
     * has not been upgraded keeps sending it, and it is accepted as long as it
     * is absolute on this machine.</p>
     */
    @Test
    public void testALegacyNativePathStillWorks() throws Exception {
        File destination = new File(archive, "legacy.bin");
        byte[] data = someBytes(16);

        assertTrue(fileManager.transferFile(destination.getAbsolutePath(), data,
            0, data.length));
        assertTrue(destination.exists());
    }

    // --------------------------------------------------- what it refuses ---

    /**
     * The incident. Refused, and nothing written anywhere.
     *
     * <p>Refusing is what lets the sender find out: the client raises on a
     * false return, so the product does not reach RECEIVED with its bytes
     * missing.</p>
     */
    @Test
    public void testAForeignAbsolutePathIsRefusedAndWritesNothing()
            throws Exception {
        if (!POSIX) {
            return;
        }
        byte[] data = someBytes(32);

        assertFalse("a destination this machine cannot resolve was accepted",
            fileManager.transferFile(FROM_THE_INCIDENT, data, 0, data.length));

        File wouldHaveBeen = new File(before, FROM_THE_INCIDENT);
        assertFalse("the foreign path was resolved against the working "
            + "directory and written as [" + wouldHaveBeen.getName() + "]",
            wouldHaveBeen.exists());
    }

    @Test
    public void testARelativeDestinationIsRefused() throws Exception {
        byte[] data = someBytes(4);
        assertFalse(fileManager.transferFile(
            "data/translated-catalog/chunk-00001.json", data, 0, data.length));
        assertFalse(new File(before,
            "data/translated-catalog/chunk-00001.json").exists());
    }

    @Test
    public void testADestinationOnAnotherHostIsRefused() throws Exception {
        byte[] data = someBytes(4);
        assertFalse("file://host/share names another machine's filesystem",
            fileManager.transferFile("file://someserver/share/x.bin", data, 0,
                data.length));
    }
}
