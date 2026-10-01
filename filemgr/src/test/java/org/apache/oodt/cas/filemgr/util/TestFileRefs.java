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
package org.apache.oodt.cas.filemgr.util;

//JDK imports
import java.io.File;
import java.net.URISyntaxException;

//JUnit imports
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Whose filesystem a path string describes.
 *
 * <p>The incident these tests come from: a Windows compute node translated a
 * chunk, ingested it, and reported success, and the bytes arrived on a macOS
 * File Manager in a file named
 * {@code filemgr/bin/C:\Users\mattmann\bt-w1\data\translated-catalog\chunk-00001.json\chunk-00001.json}.
 * 231485 bytes, exactly the size of the file the node produced.</p>
 *
 * <p>Most of these run identically on every platform, which is the point: the
 * reference built for a product must not depend on the machine that built it.
 * The two that are about the <em>local</em> filesystem say so and pick their
 * example from the platform they are running on.</p>
 */
public class TestFileRefs {

    private static final boolean POSIX = File.separatorChar == '/';

    // ------------------------------------------------------------- naming ---

    /**
     * The reference is the same on every machine, because it is arithmetic.
     *
     * <p>This is the assertion that fails if anybody puts a {@code java.io.File}
     * back into the middle of it. On Windows, {@code new File(new
     * URI("file:///Users/x"))} is {@code \Users\x}, and {@code .toURI()} then
     * returns {@code file:/C:/Users/x} -- a reference naming a directory on the
     * client, for a repository the File Manager had said was
     * {@code /Users/x}.</p>
     */
    @Test
    public void testADirectoryReferenceDoesNotDependOnThisMachine()
            throws Exception {
        assertEquals("file:/Users/mattmann/bt-w1/data/translated-catalog/",
            FileRefs.directoryReference(
                "file:///Users/mattmann/bt-w1/data/translated-catalog"));
    }

    @Test
    public void testNoDriveLetterIsInventedForAPosixRepository()
            throws Exception {
        String ref = FileRefs.directoryReference("file:///archive/products");
        assertFalse("a drive letter appeared in [" + ref + "] that was not in "
            + "the repository path: something resolved it against a local "
            + "filesystem", ref.matches("(?i)file:/+[a-z]:.*"));
    }

    /** The repository path is not required to exist: naming is not I/O. */
    @Test
    public void testTheRepositoryNeedNotExist() throws Exception {
        assertEquals("file:/no/such/directory/anywhere/",
            FileRefs.directoryReference("file:///no/such/directory/anywhere"));
    }

    @Test
    public void testATrailingSlashIsAddedOnceAndOnlyOnce() throws Exception {
        assertEquals("file:/archive/", FileRefs.directoryReference("file:///archive"));
        assertEquals("file:/archive/", FileRefs.directoryReference("file:///archive/"));
    }

    /**
     * A Windows drive repository survives.
     *
     * <p>XmlStructFactory.normalizeRepositoryPath turns
     * {@code file://C:/archive} into {@code file:///C:/archive}, so policy on a
     * Windows File Manager produces this form and the reference has to keep the
     * drive it was given. Keeping a drive that is in the input is not the same
     * thing as inventing one that is not.</p>
     */
    @Test
    public void testAWindowsDriveRepositoryKeepsItsDrive() throws Exception {
        assertEquals("file:/C:/archive/products/",
            FileRefs.directoryReference("file:///C:/archive/products"));
    }

    @Test
    public void testASpaceInTheRepositoryIsEncoded() throws Exception {
        assertEquals("file:/My%20Archive/",
            FileRefs.directoryReference("file:///My%20Archive"));
    }

    @Test
    public void testAnEncodedNameIsNotEncodedTwice() throws Exception {
        // A product legitimately named "%25". Decoding it into a File and
        // re-encoding turned it into "%" and then the reference no longer
        // parsed; appending it as text keeps it usable.
        assertEquals("file:/archive/AProduct/%25",
            FileRefs.child(FileRefs.child("file:/archive/", "AProduct"), "%25"));
    }

    @Test
    public void testTheFinalSegmentIsNotDecoded() {
        assertEquals("%25", FileRefs.finalSegment("file:/archive/AProduct/%25"));
        assertEquals("chunk-00001.json", FileRefs.finalSegment(
            "file:/Users/x/data/translated-catalog/chunk-00001.json/chunk-00001.json"));
        assertEquals("AProduct", FileRefs.finalSegment("file:/archive/AProduct/"));
    }

    @Test
    public void testAMissingRepositoryPathIsAnError() {
        try {
            FileRefs.directoryReference(null);
            fail("a null repository path produced a reference");
        } catch (URISyntaxException expected) {
            // named, rather than a NullPointerException three frames away
        }
    }

    // ----------------------------------------------- reference or a path ---

    @Test
    public void testAReferenceIsRecognisedByItsScheme() {
        assertTrue(FileRefs.isReference("file:/archive/x"));
        assertTrue(FileRefs.isReference("file:///archive/x"));
        assertTrue(FileRefs.isReference("s3://bucket/key"));
    }

    @Test
    public void testAPosixPathIsNotAReference() {
        assertFalse(FileRefs.isReference("/archive/x"));
        assertFalse(FileRefs.isReference("relative/x"));
    }

    /**
     * A Windows path is not a reference, in either of its spellings.
     *
     * <p>The backslash form does not parse as a URI at all, which is what makes
     * this decidable without asking anyone's version. The forward slash form
     * parses with scheme "C", and a one letter scheme is a drive rather than a
     * protocol -- treating it as a reference is how a Windows path gets through
     * a check that was looking for one.</p>
     */
    @Test
    public void testAWindowsPathIsNotAReference() {
        assertFalse(FileRefs.isReference("C:\\Users\\mattmann\\bt-w1\\x"));
        assertFalse(FileRefs.isReference("C:/Users/mattmann/bt-w1/x"));
    }

    // ------------------------------------------------- resolving locally ---

    @Test
    public void testAFileReferenceResolvesToALocalFile() {
        File resolved = FileRefs.toFile(
            POSIX ? "file:/archive/products/x.json" : "file:/C:/archive/x.json");
        assertTrue("[" + resolved + "] should be absolute on this machine",
            resolved.isAbsolute());
        assertEquals("x.json", resolved.getName());
    }

    @Test
    public void testAnEmptyAuthorityIsAcceptedAndDropped() {
        File a = FileRefs.toFile(POSIX ? "file:/archive/x" : "file:/C:/archive/x");
        File b = FileRefs.toFile(POSIX ? "file:///archive/x" : "file:///C:/archive/x");
        assertEquals(a, b);
    }

    @Test
    public void testAnEncodedNameIsDecodedWhenItBecomesAFile() {
        File resolved = FileRefs.toFile(
            POSIX ? "file:/archive/My%20Product" : "file:/C:/archive/My%20Product");
        assertEquals("My Product", resolved.getName());
    }

    /**
     * A reference naming another host is refused.
     *
     * <p>{@code file://server/share/x} is a legal reference and it is not this
     * machine's filesystem, so answering with a local file would be a guess.</p>
     */
    @Test
    public void testAReferenceNamingAnotherHostIsRefused() {
        try {
            FileRefs.toFile("file://someserver/share/x");
            fail("a reference on another host was resolved to a local file");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("someserver"));
        }
    }

    @Test
    public void testANonFileSchemeIsRefused() {
        try {
            FileRefs.toFile("s3://bucket/key");
            fail("an s3 reference was resolved to a local file");
        } catch (IllegalArgumentException expected) {
            // a file on this machine is not what an s3 reference names
        }
    }

    /**
     * A legacy client's native path still works, as long as it is absolute
     * here.
     *
     * <p>Before references went on the wire this was the only form, and a
     * client that has not been upgraded still sends it. It is accepted because
     * historically both ends had the same syntax and layout -- which is exactly
     * the assumption that stopped holding.</p>
     */
    @Test
    public void testALegacyNativePathIsStillAccepted() {
        File here = new File(System.getProperty("java.io.tmpdir"), "legacy.bin");
        File resolved = FileRefs.toFile(here.getAbsolutePath());
        assertEquals(here.getAbsoluteFile(), resolved.getAbsoluteFile());
    }

    /**
     * The incident, as a test.
     *
     * <p>Absolute on the sender, relative here. {@code new File(String)}
     * resolved it against the process working directory and the write
     * succeeded, so the only report of a destination in the wrong namespace was
     * a file with a very odd name. Refused now.</p>
     *
     * <p>POSIX only, because the example has to be a path this platform reads
     * as relative, and that is what a Windows path is on a POSIX machine. The
     * Windows side of the same rule is below.</p>
     */
    @Test
    public void testAWindowsPathIsRefusedOnAPosixMachine() {
        if (!POSIX) {
            return;
        }
        String fromTheIncident =
            "C:\\Users\\mattmann\\bt-w1\\data\\translated-catalog"
            + "\\chunk-00001.json\\chunk-00001.json";
        try {
            FileRefs.toFile(fromTheIncident);
            fail("a Windows path was resolved against the working directory, "
                + "which is how 231485 bytes ended up in a file named after "
                + "the whole path under filemgr/bin");
        } catch (IllegalArgumentException expected) {
            assertTrue("the message should say what is wrong with it",
                expected.getMessage().contains("absolute"));
        }
    }

    /** Any path this machine reads as relative is refused, whatever platform. */
    @Test
    public void testARelativePathIsRefused() {
        try {
            FileRefs.toFile("data/translated-catalog/chunk-00001.json");
            fail("a relative destination was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("absolute"));
        }
    }

    @Test
    public void testNothingIsRefused() {
        try {
            FileRefs.toFile(null);
            fail("null was accepted as a file reference");
        } catch (IllegalArgumentException expected) {
            // nothing to resolve
        }
    }

    // --------------------------------------------------------- round trip ---

    @Test
    public void testALocalFileRoundTripsThroughItsReference() {
        File original = new File(System.getProperty("java.io.tmpdir"),
            "round trip.json");
        File back = FileRefs.toFile(FileRefs.toReference(original));
        assertEquals(original.getAbsoluteFile(), back.getAbsoluteFile());
    }
}
