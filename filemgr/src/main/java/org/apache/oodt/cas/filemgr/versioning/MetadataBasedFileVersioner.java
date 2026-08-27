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
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.exceptions.VersioningException;
import org.apache.oodt.cas.metadata.util.PathUtils;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * A Versioner class that uses {@link Metadata} fields and a given
 * <code>filePathSpec</code> to version references. The filePathSpec should be
 * of the form:<br>
 * <br>
 * 
 * <code>/[FieldName1]/other/text/.../[FieldName2]...</code>
 * 
 * where <code>FieldName1</code> and <code>FieldName2</code> are Metadata
 * fields provided by the given Metadata object.
 * </p>.
 */
public class MetadataBasedFileVersioner implements Versioner {

    /* file path spec denoted by metadata fields and static path */
    private String filePathSpec = null;

    /* our log stream */
    private static final Logger LOG = Logger
            .getLogger(MetadataBasedFileVersioner.class.getName());

    /* whether or not we only handle flat products */
    private boolean flatProducts = true;

    public MetadataBasedFileVersioner() {
        filePathSpec = "/[Filename]";
    }

    public MetadataBasedFileVersioner(String filePathSpec) {
        this.filePathSpec = filePathSpec;
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

        // since we need the metadata, if the metadata is null, throw an
        // exception
        if (metadata == null) {
            throw new VersioningException(
                    "Unable to version product with no metadata!");
        }

        // we also only deal with Flat products, that is, Products with a single
        // reference
        if (flatProducts
                && !product.getProductStructure()
                        .equals(Product.STRUCTURE_FLAT)) {
            throw new VersioningException(
                    "Can only handle FLAT product structures");
        }

        // get the product type repo path
        String productTypeRepoPath = VersioningUtils
                .getAbsolutePathFromUri(product.getProductType()
                        .getProductRepositoryPath());

        // parse the file path spec
        String filePathRef = parseFilePathSpec(filePathSpec,
                productTypeRepoPath, metadata);

        // The values substituted above are product metadata, and product
        // metadata is extracted from the file being ingested, so whoever
        // produces the data has a say in where the archive writes it. A
        // Filename of ".." walks the destination out of the product type's
        // repository, and LocalDataTransferer will follow wherever the
        // reference points. Normalising is not enough on its own: the result
        // has to still be inside the repository.
        //
        // This is lexical on purpose. It does not need the path to exist,
        // which matters because the destination is being decided here, and it
        // cannot be defeated by a value that merely looks harmless.
        Path repositoryRoot = Paths.get(productTypeRepoPath).normalize();
        Path versionedPath = Paths.get(filePathRef).normalize();
        if (!versionedPath.startsWith(repositoryRoot)) {
            throw new VersioningException("Versioned path escapes the product "
                    + "repository: [" + versionedPath + "] is not under ["
                    + repositoryRoot + "]");
        }

        String filePathUri = versionedPath.toFile().toURI().toString();

        Reference r = (Reference) product.getProductReferences().get(0);
        LOG.log(Level.INFO, "Generated data store ref: [" + filePathUri
                + "] from origRef: [" + r.getOrigReference() + "]");
        r.setDataStoreReference(filePathUri);
    }

    private String parseFilePathSpec(String filePathSpec,
            String productTypeRepoPath, Metadata metadata) {
        StringBuilder finalFilePath = new StringBuilder();
        finalFilePath.append(productTypeRepoPath);

        if (finalFilePath.charAt(finalFilePath.length() - 1) == '/') {
            finalFilePath.deleteCharAt(finalFilePath.length() - 1);
        }

        if (!filePathSpec.startsWith("/")) {
            filePathSpec = "/" + filePathSpec;
        }

        finalFilePath.append(PathUtils.replaceEnvVariables(filePathSpec,
                metadata));
        return finalFilePath.toString();
    }

    /**
     * @return the filePathSpec
     */
    public String getFilePathSpec() {
        return filePathSpec;
    }

    /**
     * @param filePathSpec
     *            the filePathSpec to set
     */
    public void setFilePathSpec(String filePathSpec) {
        this.filePathSpec = filePathSpec;
    }

    /**
     * @return the flatProducts
     */
    public boolean isFlatProducts() {
        return flatProducts;
    }

    /**
     * @param flatProducts
     *            the flatProducts to set
     */
    public void setFlatProducts(boolean flatProducts) {
        this.flatProducts = flatProducts;
    }
}
