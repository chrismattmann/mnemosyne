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
import java.net.URI;
import java.net.URISyntaxException;

/**
 * The one place a reference becomes a local file, and a local file becomes a
 * reference.
 *
 * <p>A path string that crosses a machine boundary has to say whose filesystem
 * it describes, and a native path does not. {@link
 * org.apache.oodt.cas.filemgr.structs.Reference} already carries URIs for that
 * reason. What went wrong is that the URI was converted to a native path at
 * three seams, and each conversion silently adopted the namespace of whichever
 * machine happened to run it.</p>
 *
 * <p>Measured on 2026-09-30, a Windows compute node and a macOS File Manager,
 * one chunk of a three node run:</p>
 *
 * <pre>
 *   the node's reference  file:/C:/Users/mattmann/bt-w1/data/translated-catalog/chunk-00001.json/chunk-00001.json
 *   a Linux node's        file:/Users/mattmann/bt-w1/data/translated-catalog/chunk-00002.json/chunk-00002.json
 * </pre>
 *
 * <p>and the bytes arrived on the manager in a file named</p>
 *
 * <pre>
 *   filemgr/bin/C:\Users\mattmann\bt-w1\data\translated-catalog\chunk-00001.json\chunk-00001.json
 * </pre>
 *
 * <p>231485 bytes, exactly the size of the file the node produced. The transfer
 * worked. A backslash is a legal filename character on POSIX, so an entire
 * Windows path became one filename in the File Manager's working directory, and
 * the product was catalogued RECEIVED.</p>
 *
 * <p>Three rules follow, and they are the whole of this class:</p>
 *
 * <ol>
 *   <li>Naming is arithmetic on URIs. Building a reference must not consult a
 *       filesystem, because consulting one is how a drive letter gets into it.</li>
 *   <li>The machine that owns a file is the only one that turns its reference
 *       into a path.</li>
 *   <li>A path that is absolute somewhere else and relative here is refused,
 *       not resolved against the working directory.</li>
 * </ol>
 */
public final class FileRefs {

    private FileRefs() {
    }

    /**
     * Whether a value is a reference rather than a native path.
     *
     * <p>A reference carries a URI scheme; a native path does not. The two are
     * distinguishable with no version negotiation, which is what lets a server
     * accept both while a mixed version cluster is in flight:</p>
     *
     * <pre>
     *   file:/archive/x    scheme "file"        a reference
     *   /archive/x         no scheme            a POSIX path
     *   C:\archive\x       does not parse       a Windows path
     * </pre>
     *
     * <p>The Windows case is safe to decide this way because a backslash is not
     * a legal URI character, so {@code C:\archive\x} throws rather than parsing
     * into something plausible. {@code C:/archive/x} would parse with scheme
     * "C", which is rejected here as well: a scheme of one letter is a drive,
     * not a protocol, and treating it as a reference is how a Windows path gets
     * through a check that was looking for one.</p>
     */
    public static boolean isReference(String value) {
        if (value == null) {
            return false;
        }
        try {
            String scheme = new URI(value).getScheme();
            return scheme != null && scheme.length() > 1;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * The directory reference a product repository path denotes: normalised,
     * with a trailing slash, and without ever touching a filesystem.
     *
     * <p>This replaces {@code new File(new URI(repoPath)).toURI().toURL()},
     * which is where a drive letter entered the reference. The repository path
     * comes from the ProductType, which comes from the File Manager, so it is
     * already in the server's namespace and already correct. Round tripping it
     * through the client's {@code java.io.File} resolved a leading "/" against
     * the client's current drive and then stamped that drive back in.</p>
     *
     * <p>The output form is unchanged: an empty authority is dropped, so
     * {@code file:///archive} becomes {@code file:/archive/}, which is what the
     * File implementation produced and what existing catalogs hold.</p>
     *
     * <p>The decoded path is re-encoded, again matching the File round trip:
     * {@code new File(URI)} decodes and {@code File.toURI()} re-encodes, so a
     * repository directory with a space in it ends up encoded either way.</p>
     */
    public static String directoryReference(String repositoryPath)
            throws URISyntaxException {
        if (repositoryPath == null) {
            throw new URISyntaxException("null", "no product repository path");
        }
        URI uri = new URI(repositoryPath);
        String path = uri.isOpaque() ? uri.getSchemeSpecificPart() : uri.getPath();
        if (path == null || path.length() == 0) {
            throw new URISyntaxException(repositoryPath,
                "no path in the product repository path");
        }
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        // Multi argument, so the path is quoted rather than taken verbatim.
        return new URI(uri.getScheme() == null ? "file" : uri.getScheme(),
            null, path, null, null).toString();
    }

    /**
     * A directory reference with an already encoded segment appended.
     *
     * <p>The segment is appended as text rather than through the URI
     * constructor, because it is already in the encoded form it has to keep.
     * Re-quoting it would turn a product legitimately named {@code %25} into
     * {@code %2525}.</p>
     */
    public static String child(String directoryReference, String encodedSegment) {
        String base = directoryReference.endsWith("/")
            ? directoryReference
            : directoryReference + "/";
        return base + encodedSegment;
    }

    /**
     * The last path segment of a reference, left in whatever encoded form it
     * carries.
     *
     * <p>Taken as text rather than through {@code File.getName()}, which
     * decodes: a name like {@code %25} came back as {@code %} and the reference
     * built from it was no longer a parseable URI.</p>
     */
    public static String finalSegment(String reference) {
        String trimmed = reference.endsWith("/")
            ? reference.substring(0, reference.length() - 1)
            : reference;
        int lastSlash = trimmed.lastIndexOf('/');
        return lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
    }

    /**
     * The local file a value names, on this machine.
     *
     * <p>Only ever called by the machine that owns the file. A reference is
     * resolved with this platform's rules; a native path is accepted for a
     * client that predates references on the wire, but only if it is absolute
     * <em>here</em>.</p>
     *
     * <p>That last condition is the one that matters. {@code C:\Users\x} is
     * absolute on the sender and relative on a POSIX receiver, where {@code new
     * File(String)} resolves it against the process working directory and
     * creates a file whose name is the whole foreign path. Nothing in that
     * sequence fails, which is why it went unnoticed for a full run.</p>
     *
     * @throws IllegalArgumentException if the value cannot name a file on this
     *     machine, with the reason in the message. Raised rather than returned
     *     as null: every caller here is about to write, read or delete bytes,
     *     and guessing is what produced the backslash file.
     */
    public static File toFile(String value) {
        if (value == null || value.length() == 0) {
            throw new IllegalArgumentException("no file reference given");
        }
        if (isReference(value)) {
            URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("reference [" + value
                    + "] is not a URI: " + e.getMessage(), e);
            }
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("reference [" + value
                    + "] is not a file: reference, so it does not name a file "
                    + "on this machine");
            }
            // new File(URI) rejects an authority, and file:// with an empty
            // one is both legal and common in policy -- normalizeRepositoryPath
            // emits it for a Windows drive. Drop an empty authority rather
            // than fail on it; keep a real one, because file://host/share
            // names another machine and this method answers for this one.
            if (uri.getAuthority() != null && uri.getAuthority().length() > 0) {
                throw new IllegalArgumentException("reference [" + value
                    + "] names the host [" + uri.getAuthority() + "], which is "
                    + "not this machine's filesystem");
            }
            try {
                return new File(new URI("file", null, uri.getPath(), null));
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("reference [" + value
                    + "] has no usable path: " + e.getMessage(), e);
            }
        }
        File file = new File(value);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("[" + value + "] is not a "
                + "reference and is not an absolute path on this machine. A "
                + "path that is absolute on the sender and relative here is "
                + "refused rather than resolved against the working directory: "
                + "that is how a Windows path became a filename under "
                + "filemgr/bin. Send a file: reference instead.");
        }
        return file;
    }

    /**
     * The reference for a local file.
     *
     * <p>{@code File.toURI()} is the platform's own answer, which is correct
     * here because the file is on this machine. The result is normalised to the
     * no-authority form the catalog holds.</p>
     */
    public static String toReference(File file) {
        return file.toURI().toString();
    }
}
