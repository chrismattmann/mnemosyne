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

import org.apache.avro.AvroRemoteException;
import org.apache.avro.ipc.NettyTransceiver;
import org.apache.avro.ipc.Transceiver;
import org.apache.avro.ipc.specific.SpecificRequestor;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.util.HashedWheelTimer;
import org.apache.oodt.cas.filemgr.datatransfer.DataTransfer;
import org.apache.oodt.cas.filemgr.structs.Element;
import org.apache.oodt.cas.filemgr.structs.FileTransferStatus;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductPage;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.avrotypes.AvroElement;
import org.apache.oodt.cas.filemgr.structs.avrotypes.AvroFileManager;
import org.apache.oodt.cas.filemgr.structs.avrotypes.AvroFileTransferStatus;
import org.apache.oodt.cas.filemgr.structs.avrotypes.AvroProduct;
import org.apache.oodt.cas.filemgr.structs.avrotypes.AvroProductType;
import org.apache.oodt.cas.filemgr.structs.avrotypes.AvroQueryResult;
import org.apache.oodt.cas.filemgr.structs.avrotypes.AvroReference;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;
import org.apache.oodt.cas.filemgr.structs.exceptions.ConnectionException;
import org.apache.oodt.cas.filemgr.structs.exceptions.DataTransferException;
import org.apache.oodt.cas.filemgr.structs.exceptions.RepositoryManagerException;
import org.apache.oodt.cas.filemgr.structs.exceptions.ValidationLayerException;
import org.apache.oodt.cas.filemgr.structs.exceptions.VersioningException;
import org.apache.oodt.cas.filemgr.structs.query.ComplexQuery;
import org.apache.oodt.cas.filemgr.structs.query.QueryResult;
import org.apache.oodt.cas.filemgr.util.AvroTypeFactory;
import org.apache.oodt.cas.filemgr.util.GenericFileManagerObjectFactory;
import org.apache.oodt.cas.filemgr.versioning.Versioner;
import org.apache.oodt.cas.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author radu
 *
 * <p>Implementaion of FileManagerClient that uses apache avro-ipc API.</p>
 */
public class AvroFileManagerClient implements FileManagerClient {

    private static final Logger logger = LoggerFactory.getLogger(AvroFileManagerClient.class);

    /** Shared Netty resources for all Avro file manager clients in this JVM. */
    private static final ChannelFactory CHANNEL_FACTORY = newSharedChannelFactory("avro-filemgr-client");
    private static final Map<String, SharedConnection> SHARED_CONNECTIONS =
        new ConcurrentHashMap<String, SharedConnection>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                for (SharedConnection connection : SHARED_CONNECTIONS.values()) {
                    try {
                        closeSharedNettyTransceiver((NettyTransceiver) connection.client);
                    } catch (IOException e) {
                        logger.warn("Unable to close shared file manager client", e);
                    }
                }
                CHANNEL_FACTORY.releaseExternalResources();
            }
        }, "avro-filemgr-client-shutdown"));
    }

    /** Avro-Rpc client */
    private transient Transceiver client;

    /** proxy for the server */
    private transient AvroFileManager proxy;

    /* URL where the fileManager is */
    private URL fileManagerUrl;

    /*DataTransfer class for transferring products*/
    private DataTransfer dataTransfer = null;

    /* false when this client owns its connection rather than sharing the
       per-URL one; see the constructor for why that matters */
    private final boolean sharedConnection;

    private static final class SharedConnection {
        private final Transceiver client;
        private final AvroFileManager proxy;

        private SharedConnection(Transceiver client, AvroFileManager proxy) {
            this.client = client;
            this.proxy = proxy;
        }
    }

    public AvroFileManagerClient(final URL url) throws ConnectionException {
        this(url, true);
    }

    public AvroFileManagerClient(final URL url, boolean testConnection) throws ConnectionException {
        this(url, testConnection, true);
    }

    /**
     * @param shareConnection whether to reuse the per-URL connection shared by
     *   every client in this JVM. Sharing is the right default: it keeps one
     *   socket per file manager however many clients are constructed.
     *
     *   Pass false when the caller may issue a request while a request is
     *   already in flight on that shared connection and is waiting for it. The
     *   server processes one channel's requests in order, so the second request
     *   queues behind the first, which is blocked waiting for the second: both
     *   sides park forever, and neither call has a timeout. A client running
     *   inside the file manager itself is the case that matters, because its
     *   loopback URL is the same URL its callers use, so it is handed the very
     *   connection their request arrived on.
     */
    public AvroFileManagerClient(final URL url, boolean testConnection, boolean shareConnection)
            throws ConnectionException {
        //setup the and start the client
        this.sharedConnection = shareConnection;
        try {
            this.fileManagerUrl = url;
            SharedConnection connection = shareConnection ? getSharedConnection(url) : newConnection(url);
            this.client = connection.client;
            proxy = connection.proxy;
        } catch (IOException e) {
            logger.error("Error occurred when creating file manager: {}", url, e);
        }

        if (testConnection && !isAlive()) {
            throw new ConnectionException("Exception connecting to filemgr: [" + this.fileManagerUrl + "]");
        }
    }

    @Override
    public boolean refreshConfigAndPolicy() {
        boolean success = false;

        try {
            success = proxy.refreshConfigAndPolicy();
        } catch (AvroRemoteException e) {
            logger.error("AvroRemoteException when connecting to filemgr: {}", fileManagerUrl, e);
        }

        return success;
    }

    @Override
    public boolean isAlive() {
        boolean success;

        try {
            if (proxy != null) {
                success = proxy.isAlive();
            } else return false;
        } catch (AvroRemoteException e) {
            logger.error("Error when connecting to filemgr: {}", fileManagerUrl);
            success = false;
        }

        return success;
    }

    @Override
    public boolean transferringProduct(Product product) throws DataTransferException {
        boolean success;

        try {
            success = proxy.transferringProduct(AvroTypeFactory.getAvroProduct(product));
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }

        return success;
    }

    @Override
    public boolean removeProductTransferStatus(Product product) throws DataTransferException {
        boolean success;
        try {
            success = proxy.removeProductTransferStatus(AvroTypeFactory.getAvroProduct(product));
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }
        return success;
    }

    @Override
    public boolean isTransferComplete(Product product) throws DataTransferException {
        boolean success;
        try {
            success = this.proxy.isTransferComplete(AvroTypeFactory.getAvroProduct(product));
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }
        return success;
    }

    @Override
    public boolean moveProduct(Product product, String newPath) throws DataTransferException {
        boolean success;
        try {
            success = this.proxy.moveProduct(AvroTypeFactory.getAvroProduct(product), newPath);
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }
        return success;
    }

    @Override
    public boolean modifyProduct(Product product) throws CatalogException {
        boolean success;
        try {
            success = this.proxy.modifyProduct(AvroTypeFactory.getAvroProduct(product));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
        return success;
    }

    @Override
    public boolean removeProduct(Product product) throws CatalogException {
        boolean success;
        try {
            success = this.proxy.removeProduct(AvroTypeFactory.getAvroProduct(product));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
        return success;
    }

    @Override
    public FileTransferStatus getCurrentFileTransfer() throws DataTransferException {
        try {
            return AvroTypeFactory.getFileTransferStatus(this.proxy.getCurrentFileTransfer());
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }
    }

    @Override
    public List<FileTransferStatus> getCurrentFileTransfers() throws DataTransferException {
        List<FileTransferStatus> fileTransferStatuses = new ArrayList<FileTransferStatus>();
        try {
            for (AvroFileTransferStatus afts : this.proxy.getCurrentFileTransfers()) {
                fileTransferStatuses.add(AvroTypeFactory.getFileTransferStatus(afts));
            }
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }
        return fileTransferStatuses;
    }

    @Override
    public double getProductPctTransferred(Product product) throws DataTransferException {
        try {
            return this.proxy.getProductPctTransferred(AvroTypeFactory.getAvroProduct(product));
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }
    }

    @Override
    public double getRefPctTransferred(Reference reference) throws DataTransferException {
        try {
            return this.proxy.getRefPctTransferred(AvroTypeFactory.getAvroReference(reference));
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }
    }

    @Override
    public ProductPage pagedQuery(Query query, ProductType type, int pageNum) throws CatalogException {
        try {

            return AvroTypeFactory.getProductPage(this.proxy.pagedQuery(
                    AvroTypeFactory.getAvroQuery(query),
                    AvroTypeFactory.getAvroProductType(type),
                    pageNum
            ));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public ProductPage getFirstPage(ProductType type) throws CatalogException {
        logger.debug("Getting first page for product type: {}", type.toString());
        try {
            return AvroTypeFactory.getProductPage(this.proxy.getFirstPage(AvroTypeFactory.getAvroProductType(type)));
        } catch (AvroRemoteException e) {
            logger.error("Unable to get first page for product type: {}", type.toString(), e);
            throw new CatalogException("Unable to get first page", e);
        }
    }

    @Override
    public ProductPage getLastPage(ProductType type) throws CatalogException {
        logger.debug("Getting last page for product type: {}", type.toString());
        try {
            return AvroTypeFactory.getProductPage(this.proxy.getLastPage(AvroTypeFactory.getAvroProductType(type)));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public ProductPage getNextPage(ProductType type, ProductPage currPage) throws CatalogException {
        logger.debug("Getting next page for product type: {}, current page: {}", type.toString(), currPage.getPageNum());
        try {
            return AvroTypeFactory.getProductPage(this.proxy.getNextPage(
                    AvroTypeFactory.getAvroProductType(type),
                    AvroTypeFactory.getAvroProductPage(currPage)
            ));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public ProductPage getPrevPage(ProductType type, ProductPage currPage) throws CatalogException {
        logger.debug("Getting previous page for product type: {}, current page: {}", type.toString(), currPage.getPageNum());
        try {
            return AvroTypeFactory.getProductPage(this.proxy.getPrevPage(
                    AvroTypeFactory.getAvroProductType(type),
                    AvroTypeFactory.getAvroProductPage(currPage)
            ));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public String addProductType(ProductType type) throws RepositoryManagerException {
        logger.debug("Adding product type: {}", type.toString());
        try {
            return this.proxy.addProductType(AvroTypeFactory.getAvroProductType(type));
        } catch (AvroRemoteException e) {
            throw new RepositoryManagerException(e.getMessage());
        }
    }

    @Override
    public boolean hasProduct(String productName) throws CatalogException {
        try {
            return this.proxy.hasProduct(productName);
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public int getNumProducts(ProductType type) throws CatalogException {
        try {
            return this.proxy.getNumProducts(AvroTypeFactory.getAvroProductType(type));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public List<Product> getTopNProducts(int n) throws CatalogException {
        List<Product> products = new ArrayList<Product>();
        try {
            for (AvroProduct p : this.proxy.getTopNProducts(n)) {
                products.add(AvroTypeFactory.getProduct(p));
            }
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
        return products;
    }

    @Override
    public List<Product> getTopNProducts(int n, ProductType type) throws CatalogException {
        List<Product> products = new ArrayList<Product>();
        try {
            for (AvroProduct p : this.proxy.getTopNProductsByProductType(n, AvroTypeFactory.getAvroProductType(type))) {
                products.add(AvroTypeFactory.getProduct(p));
            }
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
        return products;
    }

    @Override
    public void setProductTransferStatus(Product product) throws CatalogException {
        try {
            this.proxy.setProductTransferStatus(AvroTypeFactory.getAvroProduct(product));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public void addProductReferences(Product product) throws CatalogException {
        try {
            this.proxy.addProductReferences(AvroTypeFactory.getAvroProduct(product));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public void addMetadata(Product product, Metadata metadata) throws CatalogException {
        try {
            this.proxy.addMetadata(AvroTypeFactory.getAvroProduct(product),
                    AvroTypeFactory.getAvroMetadata(metadata));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }

    }

    @Override
    public boolean updateMetadata(Product product, Metadata met) throws CatalogException {
        try {
            return this.proxy.updateMetadata(
                    AvroTypeFactory.getAvroProduct(product),
                    AvroTypeFactory.getAvroMetadata(met)
            );
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public String catalogProduct(Product product) throws CatalogException {
        try {
            return this.proxy.catalogProduct(AvroTypeFactory.getAvroProduct(product));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public Metadata getMetadata(Product product) throws CatalogException {
        try {
            return AvroTypeFactory.getMetadata(this.proxy.getMetadata(AvroTypeFactory.getAvroProduct(product)));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public Metadata getReducedMetadata(Product product, List<?> elements) throws CatalogException {
        try {
            return AvroTypeFactory.getMetadata(
                    this.proxy.getReducedMetadata(AvroTypeFactory.getAvroProduct(product), (List<String>) elements));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public boolean removeFile(String filePath) throws DataTransferException {
        try {
            return this.proxy.removeFile(filePath);
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }
    }

    @Override
    public byte[] retrieveFile(String filePath, int offset, int numBytes) throws DataTransferException {
        try {
            return this.proxy.retrieveFile(filePath, offset, numBytes).array();
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }
    }

    @Override
    public void transferFile(String filePath, byte[] fileData, int offset, int numBytes) throws DataTransferException {
        try {
            this.proxy.transferFile(filePath, ByteBuffer.wrap(fileData), offset, numBytes);
        } catch (AvroRemoteException e) {
            throw new DataTransferException(e.getMessage());
        }
    }

    @Override
    public List<Product> getProductsByProductType(ProductType type) throws CatalogException {
        List<Product> products = new ArrayList<Product>();
        try {
            for (AvroProduct ap : this.proxy.getProductsByProductType(AvroTypeFactory.getAvroProductType(type))) {
                products.add(AvroTypeFactory.getProduct(ap));
            }
            return products;

        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public List<Element> getElementsByProductType(ProductType type) throws ValidationLayerException {
        List<Element> products = new ArrayList<Element>();
        try {
            for (AvroElement ap : this.proxy.getElementsByProductType(AvroTypeFactory.getAvroProductType(type))) {
                products.add(AvroTypeFactory.getElement(ap));
            }
        } catch (AvroRemoteException e) {
            throw new ValidationLayerException(e.getMessage());
        }
        return products;
    }

    @Override
    public Element getElementById(String elementId) throws ValidationLayerException {
        try {
            return AvroTypeFactory.getElement(this.proxy.getElementById(elementId));
        } catch (AvroRemoteException e) {
            throw new ValidationLayerException(e.getMessage());
        }
    }

    @Override
    public Element getElementByName(String elementName) throws ValidationLayerException {
        try {
            return AvroTypeFactory.getElement(this.proxy.getElementByName(elementName));
        } catch (AvroRemoteException e) {
            throw new ValidationLayerException(e.getMessage());
        }
    }

    @Override
    public List<QueryResult> complexQuery(ComplexQuery complexQuery) throws CatalogException {
        List<QueryResult> queryResults = new ArrayList<QueryResult>();
        try {
            List<AvroQueryResult> avroQueryResults = this.proxy.complexQuery(AvroTypeFactory.getAvroComplexQuery(complexQuery));
            for (AvroQueryResult aqr : avroQueryResults) {
                queryResults.add(AvroTypeFactory.getQueryResult(aqr));
            }
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
        return queryResults;
    }

    @Override
    public List<Product> query(Query query, ProductType type) throws CatalogException {
        List<Product> products = new ArrayList<Product>();
        try {
            for (AvroProduct ap : this.proxy.query(AvroTypeFactory.getAvroQuery(query), AvroTypeFactory.getAvroProductType(type))) {
                products.add(AvroTypeFactory.getProduct(ap));
            }
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
        return products;
    }

    @Override
    public ProductType getProductTypeByName(String productTypeName) throws RepositoryManagerException {
        try {
            return AvroTypeFactory.getProductType(this.proxy.getProductTypeByName(productTypeName));
        } catch (AvroRemoteException e) {
            throw new RepositoryManagerException(e.getMessage());
        }
    }

    @Override
    public ProductType getProductTypeById(String productTypeId) throws RepositoryManagerException {
        try {
            return AvroTypeFactory.getProductType(this.proxy.getProductTypeById(productTypeId));
        } catch (AvroRemoteException e) {
            throw new RepositoryManagerException(e.getMessage());
        }
    }

    @Override
    public List<ProductType> getProductTypes() throws RepositoryManagerException {
        List<ProductType> productTypes = new ArrayList<ProductType>();
        try {
            for (AvroProductType apt : this.proxy.getProductTypes()) {
                productTypes.add(AvroTypeFactory.getProductType(apt));
            }
        } catch (AvroRemoteException e) {
            throw new RepositoryManagerException(e.getMessage());
        }
        return productTypes;
    }

    @Override
    public List<Reference> getProductReferences(Product product) throws CatalogException {
        List<Reference> references = new ArrayList<Reference>();
        try {
            for (AvroReference ar : this.proxy.getProductReferences(AvroTypeFactory.getAvroProduct(product))) {
                references.add(AvroTypeFactory.getReference(ar));
            }
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
        return references;
    }

    @Override
    public Product getProductById(String productId) throws CatalogException {
        try {
            return AvroTypeFactory.getProduct(this.proxy.getProductById(productId));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public Product getProductByName(String productName) throws CatalogException {
        try {
            return AvroTypeFactory.getProduct(this.proxy.getProductByName(productName));
        } catch (AvroRemoteException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    @Override
    public String ingestProduct(Product product, Metadata metadata, boolean clientTransfer) throws Exception {
        logger.debug("Ingesting product: {}", product.getProductName());
        try {
            // ingest product
            String productId = this.proxy.ingestProduct(
                    AvroTypeFactory.getAvroProduct(product),
                    AvroTypeFactory.getAvroMetadata(metadata),
                    clientTransfer);

            if (clientTransfer) {
                logger.debug("clientTransfer enabled: transfering product: {}", product.getProductName());

                // we need to transfer the product ourselves
                // make sure we have the product ID
                if (productId == null) {
                    logger.error("Product ID is null for product: {}", product.getProductName());
                    throw new Exception("Request to ingest product: "
                            + product.getProductName()
                            + " but no product ID returned from File "
                            + "Manager ingest");
                }

                if (dataTransfer == null) {
                    logger.warn("Data transferer is null. Product: {}", product.getProductName());
                    throw new Exception("Request to ingest product: ["
                            + product.getProductName()
                            + "] using client transfer, but no "
                            + "dataTransferer specified!");
                }

                product.setProductId(productId);

                if (!Boolean.getBoolean("org.apache.oodt.cas.filemgr.serverside.versioning")) {
                    // version the product
                    Versioner versioner = GenericFileManagerObjectFactory
                            .getVersionerFromClassName(product.getProductType()
                                    .getVersioner());
                    versioner.createDataStoreReferences(product, metadata);

                    // add the newly versioned references to the data store
                    try {
                        addProductReferences(product);
                    } catch (CatalogException e) {
                        logger.error("Error when adding Product references for Product [{}] to repository manager: {}",
                                product.getProductName(), e.getMessage());
                        throw e;
                    }
                } else {
                    product.setProductReferences(getProductReferences(product));
                }

                // now transfer the product
                try {
                    dataTransfer.transferProduct(product);
                    // now update the product's transfer status in the data
                    // store
                    product.setTransferStatus(Product.STATUS_RECEIVED);

                    try {
                        setProductTransferStatus(product);
                    } catch (CatalogException e) {
                        logger.error("Error when updating product transfer status for Product[{}]: {}",
                                product.getProductName(), e.getMessage());
                        throw e;
                    }
                } catch (Exception e) {
                    logger.error("DataTransferException when transferring Product[{}]: {}",
                            product.getProductName(), e.getMessage());
                    throw new DataTransferException(e);
                }

            }
            return productId;

            // error versioning file
        } catch (VersioningException e) {
            logger.error("VersioningException when versioning Product[{}] with versioner: {}: {}",
                    product.getProductName(), product.getProductType().getVersioner(), e.getMessage());
            throw new VersioningException(e);
        } catch (Exception e) {
            logger.error("Failed to ingest product [{}]. -- rolling back ingest", product, e);
            try {
                AvroProduct avroProduct = AvroTypeFactory.getAvroProduct(product);
                this.proxy.removeProduct(avroProduct);
            } catch (Exception e1) {
                logger.error("Failed to rollback ingest of product [{}]", product, e);
            }
            throw new Exception("Failed to ingest product [" + product + "] : " + e.getMessage());
        }
    }

    @Override
    public Metadata getCatalogValues(Metadata metadata, ProductType productType) throws Exception {
        return AvroTypeFactory.getMetadata(this.proxy.getCatalogValues(
                AvroTypeFactory.getAvroMetadata(metadata),
                AvroTypeFactory.getAvroProductType(productType)));
    }

    @Override
    public Metadata getOrigValues(Metadata metadata, ProductType productType) throws Exception {
        return AvroTypeFactory.getMetadata(this.proxy.getOrigValues(
                AvroTypeFactory.getAvroMetadata(metadata),
                AvroTypeFactory.getAvroProductType(productType)));
    }

    @Override
    public Query getCatalogQuery(Query query, ProductType productType) throws Exception {
        return AvroTypeFactory.getQuery(this.proxy.getCatalogQuery(
                AvroTypeFactory.getAvroQuery(query),
                AvroTypeFactory.getAvroProductType(productType)));
    }

    @Override
    public URL getFileManagerUrl() {
        return this.fileManagerUrl;
    }

    @Override
    public void setFileManagerUrl(URL fileManagerUrl) {
        this.fileManagerUrl = fileManagerUrl;
    }

    @Override
    public DataTransfer getDataTransfer() {
        return this.dataTransfer;
    }

    @Override
    public void setDataTransfer(DataTransfer dataTransfer) {
        this.dataTransfer = dataTransfer;
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing file manager client for URL: {}", fileManagerUrl);
        // a shared connection outlives this client and is closed at shutdown;
        // one we own is ours to release, or it leaks a socket per client.
        if (!sharedConnection && client != null) {
            closeSharedNettyTransceiver((NettyTransceiver) client);
        }
        client = null;
        proxy = null;
    }

    /** A connection owned by a single client. Netty threads stay shared. */
    private static SharedConnection newConnection(URL url) throws IOException {
        InetSocketAddress inetSocketAddress = new InetSocketAddress(url.getHost(), url.getPort());
        Transceiver transceiver = new NettyTransceiver(inetSocketAddress, CHANNEL_FACTORY, 40000L);
        AvroFileManager clientProxy = (AvroFileManager) SpecificRequestor.getClient(
            AvroFileManager.class, transceiver);
        return new SharedConnection(transceiver, clientProxy);
    }

    private static synchronized SharedConnection getSharedConnection(URL url) throws IOException {
        String key = url.toExternalForm();
        SharedConnection connection = SHARED_CONNECTIONS.get(key);
        if (connection == null) {
            connection = newConnection(url);
            SHARED_CONNECTIONS.put(key, connection);
        }
        return connection;
    }

    private static void closeTransceiver(Transceiver transceiver) throws IOException {
        if (transceiver instanceof NettyTransceiver) {
            closeSharedNettyTransceiver((NettyTransceiver) transceiver);
        } else {
            transceiver.close();
        }
    }

    private static void closeSharedNettyTransceiver(NettyTransceiver transceiver) throws IOException {
        try {
            Field stopping = NettyTransceiver.class.getDeclaredField("stopping");
            stopping.setAccessible(true);
            stopping.set(transceiver, Boolean.TRUE);

            Method disconnect = NettyTransceiver.class.getDeclaredMethod(
                "disconnect", boolean.class, boolean.class, Throwable.class);
            disconnect.setAccessible(true);
            disconnect.invoke(transceiver, true, true, null);
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException e) {
            throw new IOException("Unable to close shared Avro Netty transceiver", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IOException("Unable to close shared Avro Netty transceiver", cause);
        }
    }

    private static ExecutorService newDaemonCachedThreadPool(final String namePrefix) {
        return Executors.newCachedThreadPool(newDaemonThreadFactory(namePrefix));
    }

    private static ChannelFactory newSharedChannelFactory(String namePrefix) {
        return new NioClientSocketChannelFactory(
            newDaemonCachedThreadPool(namePrefix + "-boss"),
            1,
            new NioWorkerPool(newDaemonCachedThreadPool(namePrefix + "-worker"), getIoWorkerCount()),
            new HashedWheelTimer(newDaemonThreadFactory(namePrefix + "-timer")));
    }

    private static int getIoWorkerCount() {
        return Integer.getInteger("org.apache.oodt.avro.client.ioWorkers", 2);
    }

    private static ThreadFactory newDaemonThreadFactory(final String namePrefix) {
        final AtomicInteger count = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, namePrefix + "-" + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
