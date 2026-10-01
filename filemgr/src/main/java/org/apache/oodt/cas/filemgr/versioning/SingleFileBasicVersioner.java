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

//JDK imports
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.exceptions.VersioningException;
import org.apache.oodt.cas.filemgr.util.FileRefs;
import org.apache.oodt.cas.metadata.Metadata;

import java.io.File;
import java.net.URLEncoder;
import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * A {@link Versioner} that handles single file {@link Product}s by storing
 * them in <code>productTypeRepoPath/Filename</code>.
 * </p>
 * 
 */
public class SingleFileBasicVersioner implements Versioner {

    /* filename fields */
    public static final String FILENAME_FIELD = "Filename";

    /* our log stream */
    private static final Logger LOG = Logger.getLogger(SingleFileBasicVersioner.class
            .getName());

    /**
     * 
     */
    public SingleFileBasicVersioner() {
        super();
        // TODO Auto-generated constructor stub
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.versioning.Versioner#createDataStoreReferences(gov.nasa.
     *      jpl.oodt.cas.filemgr.structs.Product,
     *      org.apache.oodt.cas.metadata.Metadata)
     */
    public void createDataStoreReferences(Product product, Metadata metadata)
            throws VersioningException {
        // we only handle single files, so throw exception if product is
        // heirarhical

        if (!product.getProductStructure().equals(Product.STRUCTURE_FLAT)) {
            throw new VersioningException(
                    "SingleFileVersioner: unable to version" + " Product: ["
                            + product.getProductName()
                            + "] with heirarchical/stream structure");
        }

        // we need the Filename Metadata parameter for this to work
        String filename = metadata.getMetadata(FILENAME_FIELD);

        if (filename == null || (filename.equals(""))) {
            throw new VersioningException(
                    "SingleFileVersioner: unable to version without "
                            + "Filename metadata field specified!");
        }

        // now we need the product type repo path
        String productTypeRepoPathUri = product.getProductType()
                .getProductRepositoryPath();
        // Built from the repository URI rather than from an absolute path
        // derived from it. getAbsolutePathFromUri goes through
        // new File(URI).getAbsolutePath(), which on Windows resolves the
        // repository's leading "/" against the current drive, and
        // new File(path).toURI() then stamped that drive into a reference
        // naming a directory on the File Manager. See FileRefs.
        String dataStoreRef;
        try {
            dataStoreRef = FileRefs.child(
                FileRefs.directoryReference(productTypeRepoPathUri),
                URLEncoder.encode(filename, "UTF-8"));
        } catch (Exception e) {
            throw new VersioningException("Could not build a data store "
                + "reference from repository [" + productTypeRepoPathUri
                + "] and filename [" + filename + "]: " + e.getMessage(), e);
        }

        // get the first reference back
        // set its data store ref
        Reference ref = product.getProductReferences().get(0);
        LOG.log(Level.INFO, "Generated data store ref: [" + dataStoreRef
                + "] from origRef: [" + ref.getOrigReference() + "]");
        ref.setDataStoreReference(dataStoreRef);

    }
}
