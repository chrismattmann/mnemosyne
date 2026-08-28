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

//OODT imports
import org.apache.commons.lang.StringUtils;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.Product;

//JDK imports
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Stack;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author mattmann
 * @author bfoster
 * @version $Revision$
 * 
 * <p>
 * A Utility component to help out with versioning.
 * </p>
 * 
 * 
 */
public final class VersioningUtils {

    /* our log stream */
    private static final Logger LOG = Logger.getLogger(VersioningUtils.class
            .getName());

    // filter to only find directories when doing a listFiles
    private static FileFilter DIR_FILTER = new FileFilter() {
        public boolean accept(File file) {
            return file.isDirectory();
        }
    };

    // filter to only find files when doing a listFiles
    private static FileFilter FILE_FILTER = new FileFilter() {
        public boolean accept(File file) {
            return file.isFile();
        }
    };

    public static List<Reference> getReferencesFromDir(File dirRoot) {
        List<Reference> references;

        if (dirRoot == null) {
            throw new IllegalArgumentException("null");
        }
        if (!dirRoot.isDirectory()) {
            dirRoot = dirRoot.getParentFile();
        }

        references = new Vector<Reference>();

        Stack<File> stack = new Stack<File>();
        stack.push(dirRoot);
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            // add the reference for the dir
            // except if it's the rootDir, then, skip it
            if (!dir.equals(dirRoot)) {
                try {
                    Reference r = new Reference();
                    r.setOrigReference(dir.toURI().toURL().toExternalForm());
                    r.setFileSize(dir.length());
                    references.add(r);
                } catch (MalformedURLException e) {
                    LOG.log(Level.SEVERE, e.getMessage());
                    LOG.log(Level.WARNING,
                            "MalformedURLException when generating reference for dir: "
                                    + dir);
                }
            }

            File[] files = dir.listFiles(FILE_FILTER);

            if(files!=null) {
                for (File file : files) {
                    // add the file references
                    try {
                        Reference r = new Reference();
                        r.setOrigReference(file.toURI().toURL().toExternalForm());
                        r.setFileSize(file.length());
                        references.add(r);
                    } catch (MalformedURLException e) {
                        LOG.log(Level.SEVERE, e.getMessage());
                        LOG.log(Level.WARNING,
                            "MalformedURLException when generating reference for file: "
                            + file);
                    }

                }
                File[] subdirs = dir.listFiles(DIR_FILTER);
                if (subdirs != null) {
                    for (File subdir : subdirs) {
                        stack.push(subdir);
                    }
                }
            }
        }

        return references;
    }

    public static List<String> getURIsFromDir(File dirRoot) {
        List<String> uris;

        if (dirRoot == null) {
            throw new IllegalArgumentException("null");
        }
        if (!dirRoot.isDirectory()) {
            dirRoot = dirRoot.getParentFile();
        }

        uris = new Vector<String>();

        Stack<File> stack = new Stack<File>();
        stack.push(dirRoot);
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            // add the reference for the dir
            // except if it's the rootDir, then, skip it
            if (!dir.equals(dirRoot)) {
                uris.add(dir.toURI().toString());
            }

            File[] files = dir.listFiles(FILE_FILTER);

            if(files!=null) {
                for (File file : files) {
                    // add the file references
                    uris.add(file.toURI().toString());
                }
            }

            File[] subdirs = dir.listFiles(DIR_FILTER);
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    stack.push(subdir);
                }
            }
        }

        return uris;
    }

    public static void createBasicDataStoreRefsHierarchical(List<Reference> references) {
        // file:///www/folder1
        // file:///www/folder1/file1
        // file:///www/folder1/file2
        // file:///www/folder1/folder2/
        // file:///www/folder1/folder2/file3

        // toDir: file:///www/myfolder/product1
        // origDir: file:///www/folder1

        String toDirRef = references.get(0)
                .getDataStoreReference();
        String origDirRef = references.get(0).getOrigReference();

        // Each child is located by cutting its reference at the staging
        // root's full prefix. The two share that prefix by construction --
        // the children were found by walking the root -- so this is both
        // simpler and correct.
        //
        // It used to search for the *name* of the staging directory inside
        // the child's URL with indexOf, which finds the first occurrence. So
        // whenever the directory's name also appeared earlier in the path,
        // the child was cut at the wrong segment and archived one level
        // deeper than the catalog and the versioner agree it should be:
        //
        //   staging root : file:/data/data
        //   child        : file:/data/data/data
        //   produced     : file:/archive/AProduct/data/data
        //   correct      : file:/archive/AProduct/data
        //
        // A repeated segment is entirely ordinary -- /data/data, /2024/2024,
        // a product type named after its parent directory.
        String origDirPrefix = origDirRef.endsWith("/") ? origDirRef : origDirRef + "/";
        String toDirPrefix = toDirRef.endsWith("/") ? toDirRef : toDirRef + "/";

        for (Reference r : references) {
            // don't bother with the first one, because it's already set
            // correctly
            if (r.getOrigReference().equals(origDirRef)) {
                continue;
            }

            String origRef = r.getOrigReference();
            if (!origRef.startsWith(origDirPrefix)) {
                LOG.log(Level.WARNING, "VersioningUtils: reference [" + origRef
                    + "] is not below the staging root [" + origDirRef
                    + "]; leaving its data store reference alone");
                continue;
            }

            String dataStoreRef = toDirPrefix
                + origRef.substring(origDirPrefix.length());

            LOG.log(Level.FINE, "VersioningUtils: Generated data store ref: "
                                + dataStoreRef + " from origRef: " + r.getOrigReference());
            r.setDataStoreReference(dataStoreRef);
        }

    }

    /**
     * The last path segment of a URI reference, left in whatever encoded form
     * it already carries.
     *
     * Taking it as text rather than through File.getName() keeps a name like
     * "%25" escaped, so the reference built from it is still a URI that
     * LocalDataTransferer can resolve.
     */
    private static String encodedFinalSegment(String reference) {
        String trimmed = reference.endsWith("/")
            ? reference.substring(0, reference.length() - 1)
            : reference;
        int lastSlash = trimmed.lastIndexOf('/');
        return lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
    }

    public static void createBasicDataStoreRefsFlat(String productName,
            String productRepoPath, List<Reference> references) {
        for (Reference r : references) {
            String dataStoreRef = null;
            String productRepoPathRef;

            try {
                productRepoPathRef = new File(new URI(productRepoPath)).toURI().toURL()
                                                                       .toExternalForm();

                if (!productRepoPathRef.endsWith("/")) {
                    productRepoPathRef += "/";
                }

                // The final segment is taken in the encoded form it already
                // has, rather than decoded out of the URI and concatenated
                // back in raw. new File(new URI(...)).getName() decodes, so
                // a file named "%" arrived as "%" and produced
                // "file:/archive/AProduct/%" -- not a parseable URI
                // ("Malformed escape pair at index 23"). LocalDataTransferer
                // does new File(new URI(getDataStoreReference())) on this
                // value, so it threw at transfer time, after the catalog
                // entry had been made. A space failed the same way, and a
                // "#" was worse: it parsed, but everything after it became a
                // fragment, so the transferer wrote to a different path than
                // the one recorded.
                dataStoreRef = productRepoPathRef
                               + URLEncoder.encode(productName, "UTF-8") + "/"
                               + encodedFinalSegment(r.getOrigReference());
            } catch (IOException e) {
                LOG.log(Level.WARNING,
                    "VersioningUtils: Error generating dataStoreRef for "
                    + r.getOrigReference() + ": Message: "
                    + e.getMessage());
            } catch (URISyntaxException e) {
                LOG.log(Level.WARNING,
                    "VersioningUtils: Error generating dataStoreRef for "
                    + r.getOrigReference() + ": Message: "
                    + e.getMessage());
            }

            LOG.log(Level.FINE, "VersioningUtils: Generated data store ref: "
                                + dataStoreRef + " from origRef: " + r.getOrigReference());
            r.setDataStoreReference(dataStoreRef);
        }

    }
    public static void createBasicDataStoreRefsStream(String productName,
        String productRepoPath, List<Reference> references,String postfix) {
        for (Reference r : references) {
            createDataStoreRefStream(productName, productRepoPath, r, postfix);
        }

    }
    public static void createDataStoreRefStream(String pn, String productRepoPath, Reference ref, String postfix) {
        URI uri = URI.create(ref.getOrigReference());
        String[] parts = (postfix.equals(""))?new String[] {uri.toString()}:new String[] {uri.toString(),postfix};
        ref.setDataStoreReference(StringUtils.join(parts,Reference.STREAM_REFERENCE_DELIMITER));
    }
    public static void addRefsFromUris(Product p, List<String> uris) {
        // add the refs to the Product
        for (String ref : uris) {
            Reference r = new Reference(ref, null,
                (p.getProductStructure().equals(Product.STRUCTURE_STREAM) ? -1 : quietGetFileSizeFromUri(ref)));
            p.getProductReferences().add(r);
        }
    }

    public static String getAbsolutePathFromUri(String uriStr) {
        URI uri;
        String absPath = null;

        try {
            uri = new URI(uriStr);
            absPath = new File(uri).getAbsolutePath();
        } catch (URISyntaxException e) {
            LOG.log(Level.WARNING,
                    "URISyntaxException getting URI from URI str: [" + uriStr
                            + "]");
        } catch (IllegalArgumentException e) {
            // new File(URI) throws this, unchecked, for a URI that parses but
            // is not a usable file path: a non-file scheme, an opaque URI, one
            // carrying an authority or a fragment. Callers here
            // (FileManager:1087, FinalFileLocationExtractor) null-check rather
            // than catch, so null-on-failure is the contract this method is
            // read as having, and an unchecked throw escaped it.
            LOG.log(Level.WARNING,
                    "URI is not a usable file path: [" + uriStr + "]: "
                            + e.getMessage());
        }

        return absPath;
    }

    private static long quietGetFileSizeFromUri(String uri) {
        File fileRef;

        try {
            fileRef = new File(new URI(uri));
        } catch (URISyntaxException e) {
            LOG.log(Level.WARNING,
                    "URISyntaxException when getting file size from uri: ["
                            + uri + "]: Message: " + e.getMessage());
            return -1L;
        }

        return fileRef.length();
    }

}
