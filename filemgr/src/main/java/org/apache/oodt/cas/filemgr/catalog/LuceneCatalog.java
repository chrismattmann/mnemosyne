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

package org.apache.oodt.cas.filemgr.catalog;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.oodt.cas.filemgr.structs.*;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;
import org.apache.oodt.cas.filemgr.structs.exceptions.ValidationLayerException;
import org.apache.oodt.cas.filemgr.validation.ValidationLayer;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.commons.pagination.PaginationUtils;
import org.safehaus.uuid.UUID;
import org.safehaus.uuid.UUIDGenerator;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author mattmann
 * @author bfoster
 * @author luca
 * @version $Revision$
 * 
 * <p>
 * An implementation of a File {@link Catalog} using Apache's popular <a
 * href="http://lucene.apache.org">Lucene</a> text indexing engine as a
 * backend.
 * </p>
 * 
 *
 * <h2>Indexes written before Mnemosyne 1.12.0 cannot be read</h2>
 *
 * Lucene reads one major version back, and this moved from Lucene 6 to 10.
 * A catalog written by Apache OODT will not open; it has to be built again by
 * re-ingesting or re-indexing from whatever holds the authority. If the
 * catalog is the only copy of anything, export it before upgrading. See the
 * README.
 */
public class LuceneCatalog implements Catalog {

    /* our log stream */
    @Deprecated
    private static final Logger LOG = Logger.getLogger(LuceneCatalog.class.getName());

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(LuceneCatalog.class);

    Directory indexDir = null;

    // The reader used to be a field that every read method reassigned, so
    // concurrent reads clobbered each other's and only the last one could
    // ever be closed. Each read opens its own now, and closes it.

    /* the path to the index directory for this catalog */
    private String indexFilePath = null;

    /* validation layer */
    private ValidationLayer valLayer = null;

    /*
     * temporary Cache of product/metadata/reference information before it is
     * written to the index
     */
    // Was static, so every LuceneCatalog in the JVM shared one cache
    // whatever index directory it was pointed at: two catalogs over two
    // unrelated indexes saw each other's products. Per catalog now, and
    // cleared when the catalog closes -- it had no eviction at all, so it
    // grew for the life of the process.
    private final ConcurrentHashMap<String, CompleteProduct> catalogCache =
        new ConcurrentHashMap<String, CompleteProduct>();

    /* the single writer this catalog holds; see writer() */
    private IndexWriter writer;

    /* our product ID generator */
    private static UUIDGenerator generator = UUIDGenerator.getInstance();

    /* page size for pagination */
    private int pageSize = -1;

    /* write lock timeout for writing to the index */
    private long writeLockTimeout = -1L;

    /* commit lock timeout for writing/reading to the index */
    private long commitLockTimeout = -1L;

    /* lucene index merge factor */
    private int mergeFactor = -1;



    /**
     * 
     * @param idxFilePath
     *            A file path pointing to the lucene index directory for this
     *            catalog.
     * @param vLayer
     *            The validation layer to be used for this catalog.
     * @param pgSize
     *            The size of pages to be used when doing pagination of the
     *            catalog.
     * 
     * @param commitTimeout
     *            The amount of time (in seconds) that should be flowed down to
     *            the Lucene IndexReader and IndexWriters for their commit lock
     *            timeout property.
     * 
     * @param writeTimeout
     *            The amount of time (in seconds) that should be flowed down to
     *            the Lucene IndexWriters for their commit lock timeout
     *            property.
     * 
     * @param mergeFactor
     *            The merge factor to use when writing to the index.
     */
    public LuceneCatalog(String idxFilePath, ValidationLayer vLayer,
            int pgSize, long commitTimeout, long writeTimeout, int mergeFactor) {
        this.indexFilePath = idxFilePath;
        this.valLayer = vLayer;
        this.pageSize = pgSize;
        this.writeLockTimeout = writeTimeout;
        this.commitLockTimeout = commitTimeout;
        this.mergeFactor = mergeFactor;

        try {
            indexDir = FSDirectory.open(new File( indexFilePath ).toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }





    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#addMetadata(org.apache.oodt.cas.metadata.Metadata,
     *      org.apache.oodt.cas.filemgr.structs.Product)
     */
    @Override
    public synchronized void addMetadata(Metadata m, Product product)
            throws CatalogException {
        CompleteProduct p;
        if(product.getProductId()!=null && catalogCache.containsKey(product.getProductId())) {
             p = catalogCache.get(product.getProductId());
        }
        else{
                // move product from index to cache
                // it will be moved back after metadata is added
                p = getCompleteProductById(product.getProductId(), true, true);
                LOG.log(Level.FINE, "Product not found in local cache, retrieved from index");
                removeProduct(product);
        }

        p.setMetadata(m);
        if (hasMetadataAndRefs(p)) {
            LOG.log(Level.FINE,
                "metadata and references present for product: ["
                    + product.getProductId() + "]");
            addCompleteProductToIndex(p);
            // now remove its entry from the cache
            catalogCache.remove(product.getProductId());
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#removeMetadata(org.apache.oodt.cas.metadata.Metadata,
     *      org.apache.oodt.cas.filemgr.structs.Product)
     */
    @Override
    public synchronized void removeMetadata(Metadata m, Product product)
            throws CatalogException {
        CompleteProduct p;

        if(product.getProductId()!=null && catalogCache.containsKey(product.getProductId())) {
             p = catalogCache.get(product.getProductId());
        }
        else{
            String prodId = product.getProductId();
            p = getCompleteProductById(prodId, true, true);
            removeProductDocument(product);
        }




        Metadata currMet = p.getMetadata();
        List<String> metadataTypes = new ArrayList<String>();

        if (valLayer!=null) {
	        try {
	        		// remove metadata elements specified by validation layer
	        		for (Element element : valLayer.getElements(product.getProductType())) {
	        			metadataTypes.add(element.getElementName());
	        		}
	        } catch (ValidationLayerException e) {
	            LOG.log(Level.SEVERE, e.getMessage());
	            throw new CatalogException(
	                    "ValidationLayerException when trying to obtain element list for product type: "
	                            + product.getProductType().getName()
	                            + ": Message: " + e.getMessage(), e);
	        }
        } else {
        	// remove all metadata
        	metadataTypes = currMet.getAllKeys();
        }

        for (String name : metadataTypes) {
            currMet.removeMetadata(name);
        }

        p.setMetadata(currMet);

        if (hasMetadataAndRefs(p)) {
            LOG.log(Level.FINE,
                    "metadata and references present for product: ["
                            + product.getProductId() + "]");
            addCompleteProductToIndex(p);
            // now remove its entry from the cache
            catalogCache.remove(product.getProductId());
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#addProduct(org.apache.oodt.cas.filemgr.structs.Product)
     */
    @Override
    public synchronized void addProduct(Product product)
            throws CatalogException {
        if(product.getProductId()!=null && catalogCache.containsKey(product.getProductId())) {
            throw new CatalogException(
                "Attempt to add a product that already existed: product: ["
                + product.getProductName() + "]");





        } else {
            // haven't cached this product yet, so let's cache it
            CompleteProduct completeProduct = new CompleteProduct();

            // NOTE: reuse existing ID if possible
            if (product.getProductId() == null) {
                synchronized (completeProduct) {
                    // now generate a unique ID for the product
                    UUID prodUUID = generator.generateTimeBasedUUID();
                    product.setProductId(prodUUID.toString());
                }
            }

            completeProduct.setProduct(product);
            catalogCache.put(product.getProductId(), completeProduct);

        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#modifyProduct(org.apache.oodt.cas.filemgr.structs.Product)
     */
    @Override
    public synchronized void modifyProduct(Product product)
            throws CatalogException {
        if (product.getProductId()!=null && catalogCache.containsKey(product.getProductId())) {
            LOG.log(Level.FINE, "Modifying product: [" + product.getProductId()
                    + "]: found product in cache!");
            CompleteProduct cp = catalogCache.get(product
                    .getProductId());
            cp.setProduct(product);
        } else {
            // need to grab the metadata for the existing product, and make sure
            // we don't lose it
            Metadata metadata = getMetadata(product);

            CompleteProduct completeProduct = new CompleteProduct();
            completeProduct.setMetadata(metadata);

            // now remove the product's document from the catalog
            removeProductDocument(product);

            // now add it back
            completeProduct.setProduct(product);
            addCompleteProductToIndex(completeProduct);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#removeProduct(org.apache.oodt.cas.filemgr.structs.Product)
     */
    @Override
    public synchronized void removeProduct(Product product)
            throws CatalogException {
        removeProductDocument(product);
        // The document went out of the index and the cache entry stayed, so
        // a removed product id still resolved from cache -- and adding that
        // id again threw "already existed" for a product the catalog no
        // longer held.
        if (product.getProductId() != null) {
            catalogCache.remove(product.getProductId());
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#setProductTransferStatus(org.apache.oodt.cas.filemgr.structs.Product)
     */
    @Override
    public synchronized void setProductTransferStatus(Product product)
            throws CatalogException {
        LOG.log(Level.FINE,
                "LuceneCatalog: seting product transfer status to: ["
                        + product.getTransferStatus() + "] for " + "product: ["
                        + product.getProductId() + "]");
        modifyProduct(product);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#addProductReferences(org.apache.oodt.cas.filemgr.structs.Product)
     */
    @Override
    public synchronized void addProductReferences(Product product)
            throws CatalogException {
        if(product.getProductId()!=null && catalogCache.containsKey(product.getProductId())) {
            CompleteProduct p = catalogCache.get(product
                .getProductId());
            p.getProduct().setProductReferences(product.getProductReferences());
                if (hasMetadataAndRefs(p)) {
                    LOG.log(Level.FINE,
                        "metadata and references present for product: ["
                        + product.getProductId() + "]");
                    addCompleteProductToIndex(p);
                    // now remove its entry from the cache
                    catalogCache.remove(product.getProductId());
                }

        }
        else{
                // move product from index to cache
                // it will be moved back after metadata is added
                getCompleteProductById(product.getProductId(), true, true);
                LOG.log(Level.FINE, "Product not found in local cache, retrieved from index");
                removeProduct(product);

        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getProductById(java.lang.String)
     */
    @Override
    public Product getProductById(String productId) throws CatalogException {
        CompleteProduct prod = getCompleteProductById(productId, false);
        return prod.getProduct();
    }

    private Product getProductById(String productId, boolean getRefs)
            throws CatalogException {
        CompleteProduct prod = getCompleteProductById(productId, getRefs);
        return prod.getProduct();
    }

    private CompleteProduct getCompleteProductById(String productId)
            throws CatalogException {
        return getCompleteProductById(productId, false);
    }

    /**
     * Opens a reader over the index.
     *
     * Every call site used to catch the IOException, print the stack trace
     * and carry on, so a directory that could not be opened surfaced as a
     * NullPointerException on the next line -- or worse, as a search against
     * whichever reader the previous call had left in the field.
     */
    private DirectoryReader openReader() throws CatalogException {
        try {
            return DirectoryReader.open(indexDir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Unable to open index directory: ["
                    + indexFilePath + "]: Message: " + e.getMessage(), e);
            throw new CatalogException("Unable to open index directory: ["
                    + indexFilePath + "]: Message: " + e.getMessage(), e);
        }
    }

    /**
     * Closes a reader, which the six read methods left as //TODO CLOSE.
     *
     * A file manager reading products leaked a file handle per read, so the
     * failure arrived far from its cause as some unrelated open reporting
     * "Too many open files".
     */
    private static void closeQuietly(DirectoryReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (Exception ignore) {
            // Nothing a caller can do about a reader that will not close, and
            // the read itself has already succeeded or failed on its own.
        }
    }

    private CompleteProduct getCompleteProductById(String productId,
            boolean getRefs) throws CatalogException {
        return getCompleteProductById(productId, getRefs, false);
    }

    private CompleteProduct getCompleteProductById(String productId,
            boolean getRefs, boolean getMet) throws CatalogException {
        IndexSearcher searcher = null;
        DirectoryReader reader = null;
        try {
            reader = openReader();

            searcher = new IndexSearcher(reader);
            Term productIdTerm = new Term("product_id", productId);
            org.apache.lucene.search.Query query = new TermQuery(productIdTerm);
            // count(), not the hit count of a one-hit search. From Lucene 8
            // a search stops counting once it can answer and reports a lower
            // bound, so a query matching five documents would report one --
            // and a uniqueness check would pass on a record that is not
            // unique.
            int hitCount = searcher.count(query);
            TopDocs topDocs = searcher.search(query, Math.max(hitCount, 1));

            ScoreDoc[] hits = topDocs.scoreDocs;

            // should be exactly 1 hit
            if (hitCount == 0) {
            	throw new CatalogException("Product: [" + productId + "] NOT found in the catalog!");
            }
            if (hitCount > 1) {
                throw new CatalogException("Product: [" + productId+ "] is not unique in the catalog!");
            }

            Document productDoc = searcher.storedFields().document(hits[0].doc);
            return toCompleteProduct(productDoc, getRefs,
                    getMet);
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            if (searcher != null) {
                try {
                    //TODO shutdown  reader
                } catch (Exception ignore) {
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getProductByName(java.lang.String)
     */
    @Override
    public Product getProductByName(String productName) throws CatalogException {
        return getProductByName(productName, false);
    }

    private Product getProductByName(String productName, boolean getRefs)
            throws CatalogException {
        IndexSearcher searcher = null;
        DirectoryReader reader = null;
        try {
            reader = openReader();
            searcher = new IndexSearcher(reader);
            Term productIdTerm = new Term("product_name", productName);
            org.apache.lucene.search.Query query = new TermQuery(productIdTerm);
            Sort sort = new Sort(new SortField("CAS.ProductReceivedTime",
                    SortField.Type.STRING, true));
            //TODO FIX NUMBER OF RECORDS
            // count() rather than reading totalHits off a one-hit search.
            // Until Lucene 8 that count was exact; now a search stops
            // counting once it has enough hits to answer and reports a
            // lower bound, so asking for one hit and trusting the count
            // would fetch one product where there are hundreds. The
            // compiler is perfectly happy with it either way.
            int checkCount = searcher.count(query);
            if(checkCount > 0) {
                TopDocs topDocs = searcher.search(query, checkCount, sort);

                ScoreDoc[] hits = topDocs.scoreDocs;

                // should be > 0 hits
                if (hits.length > 0) {
                    // just get the first hit back
                    Document productDoc = searcher.storedFields().document(hits[0].doc);
                    CompleteProduct prod = toCompleteProduct(productDoc, getRefs,
                            false);
                    return prod.getProduct();
                } else {
                    LOG.log(Level.FINEST, "Request for product by name: ["
                            + productName + "] returned no results");
                    return null;
                }
            }
            else{
                return null;
            }

        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            closeQuietly(reader);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getProductReferences(org.apache.oodt.cas.filemgr.structs.Product)
     */
    @Override
    public List<Reference> getProductReferences(Product product) throws CatalogException {
        Product prod = getProductById(product.getProductId(), true);
        if (prod != null) {
            return prod.getProductReferences();
        } else {
            return Collections.emptyList();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getProducts()
     */
    @Override
    public List<Product> getProducts() throws CatalogException {
        return getProducts(false);
    }

    private List<Product> getProducts(boolean getRefs) throws CatalogException {
        IndexSearcher searcher = null;
        DirectoryReader reader = null;
        List<Product> products = new Vector<Product>();

        try {
            reader = openReader();
            searcher = new IndexSearcher(reader);
            Term productIdTerm = new Term("myfield", "myvalue");
            org.apache.lucene.search.Query query = new TermQuery(productIdTerm);
            Sort sort = new Sort(new SortField("CAS.ProductReceivedTime",
                    SortField.Type.STRING, true));
            //TODO FIX NUMBER OF RECORDS
            // count() rather than reading totalHits off a one-hit search.
            // Until Lucene 8 that count was exact; now a search stops
            // counting once it has enough hits to answer and reports a
            // lower bound, so asking for one hit and trusting the count
            // would fetch one product where there are hundreds. The
            // compiler is perfectly happy with it either way.
            int checkCount = searcher.count(query);
            if(checkCount > 0) {
                TopDocs topDocs = searcher.search(query, checkCount, sort);

                ScoreDoc[] hits = topDocs.scoreDocs;

                // should be > 0 hits
                if (hits.length > 0) {
                    for (ScoreDoc hit : hits) {
                        Document productDoc = searcher.storedFields().document(hit.doc);
                        CompleteProduct prod = toCompleteProduct(productDoc,
                            getRefs, false);
                        products.add(prod.getProduct());
                    }
                } else {
                    LOG.log(Level.FINEST,
                        "Request for products returned no results");
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            if (searcher != null) {
                try {
                    //TODO close searcher
                } catch (Exception ignore) {
                }
            }
        }

        return products;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getProductsByProductType(org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    @Override
    public List<Product> getProductsByProductType(ProductType type)
            throws CatalogException {
        return getProductsByProductType(type, false);
    }

    private List<Product> getProductsByProductType(ProductType type, boolean getRefs)
            throws CatalogException {
        IndexSearcher searcher = null;
        DirectoryReader reader = null;
        List<Product> products = new Vector<Product>();

        try {
            reader = openReader();
            searcher = new IndexSearcher(reader);
            Term productIdTerm = new Term("product_type_id", type
                    .getProductTypeId());
            org.apache.lucene.search.Query query = new TermQuery(productIdTerm);
            Sort sort = new Sort(new SortField("CAS.ProductReceivedTime",
                    SortField.Type.STRING, true));
            //TODO FIX NUMBER OF RECORDS
            // count() rather than reading totalHits off a one-hit search.
            // Until Lucene 8 that count was exact; now a search stops
            // counting once it has enough hits to answer and reports a
            // lower bound, so asking for one hit and trusting the count
            // would fetch one product where there are hundreds. The
            // compiler is perfectly happy with it either way.
            int checkCount = searcher.count(query);
            if(checkCount > 0) {
                TopDocs topDocs = searcher.search(query, checkCount, sort);

                ScoreDoc[] hits = topDocs.scoreDocs;

                // should be > 0 hits
                if (hits.length > 0) {
                    for (ScoreDoc hit : hits) {
                        Document productDoc = searcher.storedFields().document(hit.doc);
                        CompleteProduct prod = toCompleteProduct(productDoc,
                            getRefs, false);
                        products.add(prod.getProduct());
                    }
                } else {
                    LOG.log(Level.FINEST, "Request for products by type: ["
                        + type.getProductTypeId() + "] returned no results");
                }
            }

        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            closeQuietly(reader);
        }

        return products;
    }

    @Override
    public synchronized Metadata getMetadata(Product product) throws CatalogException {
        IndexSearcher searcher = null;
        DirectoryReader reader = null;
        try {
            reader = openReader();
            searcher = new IndexSearcher(reader);
            TermQuery qry = new TermQuery(new Term("*", "*"));
            TopDocs tdocks  = searcher.search(qry, 100);
            Term productIdTerm = new Term("product_id", product.getProductId());
            org.apache.lucene.search.Query query = new TermQuery(productIdTerm);
            //TODO FIX NUMBER OF RECORDS
            // count(), not the hit count of a one-hit search. From Lucene 8
            // a search stops counting once it can answer and reports a lower
            // bound, so a query matching five documents would report one --
            // and a uniqueness check would pass on a record that is not
            // unique.
            int hitCount = searcher.count(query);
            TopDocs topDocs = searcher.search(query, Math.max(hitCount, 1));

            ScoreDoc[] hits = topDocs.scoreDocs;

            // should be exactly 1 hit
            if (hitCount != 1) {
                throw new CatalogException("Product: ["
                        + product.getProductId()
                        + "] is not unique in the catalog! Num Hits: ["
                        + hits.length + "]");
            }

            Document productDoc = searcher.storedFields().document(hits[0].doc);

            CompleteProduct prod = toCompleteProduct(productDoc, false, true);
            return prod.getMetadata();
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            closeQuietly(reader);
        }
    }
    
    @Override
    public Metadata getReducedMetadata(Product product, List<String> elements) throws CatalogException {
        Metadata fullMetadata = getMetadata(product);
        Metadata reducedMetadata = new Metadata();
        for (String element : elements) {
            if (fullMetadata.containsKey(element)) {
                reducedMetadata.replaceMetadata(element, fullMetadata.getAllMetadata(element));
            }
        }
        return reducedMetadata;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#query(org.apache.oodt.cas.filemgr.structs.Query,
     *      org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    @Override
    public List<String> query(Query query, ProductType type) throws CatalogException {
        // paginate products returns full products, but the query method
        // is expected to return product ids
        List<Product> fullProducts = paginateQuery(query, type, -1, null);
        List<String> productIds = new Vector<String>();

        if (fullProducts != null && fullProducts.size() > 0) {
            productIds = new Vector<String>(fullProducts.size());

            for (Product p : fullProducts) {
                productIds.add(p.getProductId());
            }
        }

        return productIds;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getTopNProducts(int)
     */
    @Override
    public synchronized List<Product> getTopNProducts(int n) throws CatalogException {
        List<Product> products = new Vector<Product>();
        IndexSearcher searcher = null;
        DirectoryReader reader = null;

        try {
            reader = openReader();
            searcher = new IndexSearcher(reader);

            // construct a Boolean query here
            BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
            TermQuery tq = new TermQuery(new Term("myfield", "myvalue"));
            booleanQuery.add(tq, BooleanClause.Occur.MUST);

            Sort sort = new Sort(new SortField("CAS.ProductReceivedTime",
                    SortField.Type.STRING, true));
            LOG.log(Level.FINE, "Querying LuceneCatalog: q: [" + booleanQuery
                    + "]");
            //TODO FIX NUMBER OF RECORDS
            // count() rather than reading totalHits off a one-hit search.
            // Until Lucene 8 that count was exact; now a search stops
            // counting once it has enough hits to answer and reports a
            // lower bound, so asking for one hit and trusting the count
            // would fetch one product where there are hundreds. The
            // compiler is perfectly happy with it either way.
            int checkCount = searcher.count(booleanQuery.build());
            if(checkCount > 0) {
                TopDocs topDocs = searcher.search(booleanQuery.build(), checkCount, sort);

                ScoreDoc[] hits = topDocs.scoreDocs;

                if (hits.length > 0) {
                    int i = 0;
                    while (products.size() < Math.min(n, hits.length)) {
                        Document productDoc = searcher.storedFields().document(hits[i].doc);
                        CompleteProduct prod = toCompleteProduct(productDoc, false,
                                false);
                        products.add(prod.getProduct());
                        i++;
                    }
                } else {
                    LOG.log(Level.WARNING, "Top N query produced no products!");
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            closeQuietly(reader);
        }

        return products;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getTopNProducts(int,
     *      org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    @Override
    public synchronized List<Product> getTopNProducts(int n, ProductType type)
            throws CatalogException {
        int numPages = 1;
        if (n > this.pageSize) {
            numPages = n / this.pageSize + (n % this.pageSize == 0 ? 0 : 1);
        }

        List<Product> products = new Vector<Product>(n);
        Query query = new Query();

        for (int pageNum = 1; pageNum < numPages + 1; pageNum++) {
            List<Product> pageProducts = paginateQuery(query, type, pageNum, null);
            if(pageProducts!=null) {
                products.addAll(pageProducts);
            }
        }

        if(n<=products.size()) {
         return products.subList(0, n);
        }

        return products;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getValidationLayer()
     */
    @Override
    public ValidationLayer getValidationLayer() {
        return valLayer;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getNumProducts(org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    @Override
    public int getNumProducts(ProductType type) throws CatalogException {
        Query query = new Query();
        return getNumHits(query, type);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.util.Pagination#getFirstPage(org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    @Override
    public ProductPage getFirstPage(ProductType type) {
        logger.debug("Getting first page for product type: {}", type);
        ProductPage firstPage = new ProductPage();
        List<Product> products;
        Query query = new Query();
        
        // now construct the page
        firstPage.setPageNum(1);
        firstPage.setPageSize(pageSize);
        try {
          products = paginateQuery(query, type, 1, firstPage);
        } catch (CatalogException e) {
            LOG.log(Level.WARNING,
                    "CatalogException getting first page for product type: ["
                            + type.getProductTypeId()
                            + "] from catalog: Message: " + e.getMessage());
            logger.error("Unable to get first page for product type: {} - {}", type, e.getMessage());
            return ProductPage.blankPage();
        }
        // There are no products and thus no first page
        if (products == null || (products.size() == 0)) {
            logger.warn("No product found for first page for product type: {}", type);
            return ProductPage.blankPage();
        }

        firstPage.setPageProducts(products);

        logger.debug("Found first page with products: {}", firstPage.getPageProducts());

        return firstPage;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.util.Pagination#getLastProductPage(org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    @Override
    public ProductPage getLastProductPage(ProductType type) {
        ProductPage lastPage = new ProductPage();
        ProductPage firstPage = getFirstPage(type);
        List<Product> products;
        Query query = new Query();
        
        // now construct the page
        lastPage.setPageNum(firstPage.getTotalPages());
        lastPage.setPageSize(pageSize);
        try {
            products = paginateQuery(query, type, firstPage.getTotalPages(), lastPage);
        } catch (CatalogException e) {
          	LOG.log(Level.WARNING,
                  "CatalogException getting last page for product type: ["
                          + type.getProductTypeId()
                          + "] from catalog: Message: " + e.getMessage());
            return ProductPage.blankPage();
        }
        // There are no products thus there is no last page
        if (products == null || (products.size() == 0)) {
            return ProductPage.blankPage();
        }
        lastPage.setPageProducts(products);

        return lastPage;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.util.Pagination#getNextPage(org.apache.oodt.cas.filemgr.structs.ProductType,
     *      org.apache.oodt.cas.filemgr.structs.ProductPage)
     */
    @Override
    public ProductPage getNextPage(ProductType type, ProductPage currentPage) {
        if(type==null){
            LOG.warning("getNextPage: Provided type was null: Returning blank page.");
            return ProductPage.blankPage();
        }
        if (currentPage == null) {
            return getFirstPage(type);
        }

        if (currentPage.isLastPage()) {
            return currentPage;
        }

        List<Product> products;
        ProductPage nextPage = new ProductPage();
        Query query = new Query();

        // now construct the page
        nextPage.setPageNum(currentPage.getPageNum() + 1);
        nextPage.setPageSize(pageSize);
        try {
            products = paginateQuery(query, type, currentPage.getPageNum() + 1, nextPage);
        } catch (CatalogException e) {
            LOG.log(Level.WARNING,
                  "CatalogException getting next page for product type: ["
                          + type.getProductTypeId()
                          + "] from catalog: Message: " + e.getMessage());
            return ProductPage.blankPage();
        }
        // There are no products and thus no next page
        if (products == null || (products.size() == 0)) {
        	  return ProductPage.blankPage();
        }
        nextPage.setPageProducts(products);

        return nextPage;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.util.Pagination#getPrevPage(org.apache.oodt.cas.filemgr.structs.ProductType,
     *      org.apache.oodt.cas.filemgr.structs.ProductPage)
     */
    @Override
    public ProductPage getPrevPage(ProductType type, ProductPage currentPage) {
        if(type==null){
            LOG.warning("getPrevPage: Provided type was null: Returning blank page.");
            return ProductPage.blankPage();
        }

        if (currentPage == null) {
            return getFirstPage(type);
        }

        if (currentPage.isFirstPage()) {
            return currentPage;
        }
        List<Product> products;

        Query query = new Query();

        // now construct the page
        ProductPage prevPage = new ProductPage();
        prevPage.setPageNum(currentPage.getPageNum() - 1);
        prevPage.setPageSize(pageSize);
        try {
            products = paginateQuery(query, type, currentPage.getPageNum() - 1, prevPage);
        } catch (CatalogException e) {
            LOG.log(Level.WARNING,
                    "CatalogException getting prev page for product type: ["
                            + type.getProductTypeId()
                            + "] from catalog: Message: " + e.getMessage());
            return ProductPage.blankPage();
        }
        
        // There are no products and thus no pages
        if (products == null || (products.size() == 0)) {
            return ProductPage.blankPage();
        }
        prevPage.setPageProducts(products);

        return prevPage;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#pagedQuery(org.apache.oodt.cas.filemgr.structs.Query,
     *      org.apache.oodt.cas.filemgr.structs.ProductType, int)
     */
    @Override
    public ProductPage pagedQuery(Query query, ProductType type, int pageNum)
            throws CatalogException {
        try {
            ProductPage retPage = new ProductPage();
            retPage.setPageNum(pageNum);
            retPage.setPageSize(pageSize);
            retPage.setPageProducts(paginateQuery(query, type, pageNum, retPage));
            return retPage;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "CatalogException when doing paged product query: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        }

    }

    private synchronized void removeProductDocument(Product product)
            throws CatalogException {
        // This opened a DirectoryReader it never used, and closed it in a
        // finally: a reader opened, fsynced over and thrown away on every
        // delete. Deleting needs a writer and nothing else.
        try {
            LOG.log(Level.FINE,
                    "LuceneCatalog: remove document from index for product: ["
                            + product.getProductId() + "]");
            IndexWriter writer = writer();
            writer.deleteDocuments(new Term("product_id", product
                    .getProductId()));
            writer.commit();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Exception removing product: ["
                    + product.getProductName() + "] from index: Message: "
                    + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        }
    }

    private synchronized void addCompleteProductToIndex(CompleteProduct cp)
            throws CatalogException {
        Document doc = toDoc(cp.getProduct(), cp.getMetadata());
        try {
            // One writer for the catalog's life, rather than one per
            // document. Opening a writer reads the segment infos and takes
            // the directory's write lock, and closing it commits and fsyncs;
            // doing both around a single addDocument put all of that on the
            // ingest path, once per product.
            //
            // The commit stays. It is what makes the product durable and
            // visible to the next reader, and this catalog has no other
            // point at which to do it -- addProduct is expected to be
            // readable by the getProductById on the next line. What has gone
            // is the open and close either side of it.
            IndexWriter writer = writer();
            writer.addDocument(doc);
            writer.commit();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Unable to index product: ["
                    + cp.getProduct().getProductName() + "]: Message: "
                    + e.getMessage(), e);
            throw new CatalogException("Unable to index product: ["
                    + cp.getProduct().getProductName() + "]: Message: "
                    + e.getMessage(), e);
        }
    }

    /**
     * The writer this catalog holds, opened on first use.
     *
     * Lucene's write lock is held for as long as this is open, which makes
     * the catalog the single writer to its index directory -- the arrangement
     * Lucene is designed for. Two catalogs over one directory were already
     * mutually exclusive for the duration of each write; now the exclusion
     * lasts as long as the catalog does, which is why Catalog declares
     * close() and why FileManager closes the old catalog before replacing it.
     */
    private synchronized IndexWriter writer() throws CatalogException {
        if (writer == null) {
            IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            LogMergePolicy lmp = new LogDocMergePolicy();
            lmp.setMergeFactor(mergeFactor);
            config.setMergePolicy(lmp);
            try {
                writer = new IndexWriter(indexDir, config);
            } catch (IOException e) {
                // Used to be printStackTrace followed by a use of the null it
                // left behind, so a directory that could not be opened for
                // writing was reported as a NullPointerException.
                LOG.log(Level.WARNING, "Unable to open index directory for "
                        + "writing: [" + indexFilePath + "]: Message: "
                        + e.getMessage(), e);
                throw new CatalogException("Unable to open index directory for "
                        + "writing: [" + indexFilePath + "]: Message: "
                        + e.getMessage(), e);
            }
        }
        return writer;
    }

    /**
     * Releases the writer and the cache.
     *
     * Nothing released either before: the writer because there was never one
     * to release, the cache because it was static and lived as long as the
     * JVM.
     */
    @Override
    public synchronized void close() throws CatalogException {
        catalogCache.clear();
        IndexWriter toClose = writer;
        writer = null;
        if (toClose != null) {
            try {
                toClose.close();
            } catch (IOException e) {
                throw new CatalogException("Unable to close the index writer "
                        + "for: [" + indexFilePath + "]: Message: "
                        + e.getMessage(), e);
            }
        }
    }

    private CompleteProduct toCompleteProduct(Document doc) {
        return toCompleteProduct(doc, true, true);
    }

    private CompleteProduct toCompleteProduct(Document doc, boolean getRefs,
            boolean getMetadata) {
        Product product = new Product();
        Metadata metadata = new Metadata();
        CompleteProduct completeProduct = new CompleteProduct();

        product.setProductId(doc.get("product_id"));
        product.setProductName(doc.get("product_name"));
        product.setProductStructure(doc.get("product_structure"));
        product.setTransferStatus(doc.get("product_transfer_status"));

        ProductType type = new ProductType();
        type.setDescription(doc.get("product_type_desc"));
        type.setProductTypeId(doc.get("product_type_id"));
        type.setName(doc.get("product_type_name"));
        type.setProductRepositoryPath(doc.get("product_type_repoPath"));
        type.setVersioner(doc.get("product_type_versioner"));
        product.setProductType(type);

        if (getMetadata) {
            List<String> names = new ArrayList<String>();

            if (valLayer!=null) {
            	// only add metadata elements specified by validation layer
	            try {
	                for (Element element : valLayer.getElements(type)) {
	                	names.add(element.getElementName());
	                }
	            } catch (ValidationLayerException e) {
	                LOG.log(Level.WARNING,
	                        "Unable to obtain metadata for product: ["
	                                + product.getProductName() + "]: Message: "
	                                + e.getMessage());
	            }
            } else {
            	// add all metadata elements found in document
            	List<IndexableField> fields = doc.getFields();
                for(IndexableField field: fields){
                    if (!names.contains(field.name())) {
                        names.add(field.name());
                    }
                }
            	
            }

            // loop over field names to add to metadata
            for (String name : names) {
            		if (metadata.getAllMetadata(name)==null || metadata.getAllMetadata(name).size()==0) {
	                String[] elemValues = doc.getValues(name);
	                	
	                if (elemValues != null && elemValues.length > 0) {
                        for (String elemValue : elemValues) {
                            metadata.addMetadata(name, elemValue);
                        }
	                }
            		}
            }

            completeProduct.setMetadata(metadata);
        }

        if (getRefs) {
            // now add the references
            String[] origRefs = doc.getValues("reference_orig");
            String[] dataStoreRefs = doc.getValues("reference_data_store");
            String[] refLengths = doc.getValues("reference_fileSize");
            String[] refMimeTypes = doc.getValues("reference_mimeType");

            if ((origRefs.length == dataStoreRefs.length)
                    && (origRefs.length == refLengths.length)) {
                List<Reference> references = new Vector<Reference>();
                for (int i = 0; i < origRefs.length; i++) {
                    Reference r = new Reference();
                    r.setOrigReference(origRefs[i]);
                    r.setDataStoreReference(dataStoreRefs[i]);
                    r.setFileSize((Long.parseLong(refLengths[i])));
                    if (refMimeTypes != null) {
                        r.setMimeType(refMimeTypes[i]);
                    }
                    references.add(r);
                }

                product.setProductReferences(references);
            } else {
                LOG.log(Level.WARNING, "Number of original refs: ["
                        + origRefs.length + "] for product: ["
                        + product.getProductName()
                        + "] not equivalent to number of data store refs: ["
                        + dataStoreRefs.length
                        + "]: Skipping product references");
            }
        }

        completeProduct.setProduct(product);
        return completeProduct;
    }

    private Document toDoc(Product product, Metadata metadata) {
        Document doc = new Document();
//TODO CHECK STORED TYPES
        // add the product information
        doc.add(new Field("product_id", product.getProductId(),
                StringField.TYPE_STORED));
        doc.add(new Field("product_name", product.getProductName(),
                StringField.TYPE_STORED));
        doc.add(new Field("product_structure", product.getProductStructure(),
                StringField.TYPE_STORED));
        doc
                .add(new Field("product_transfer_status", product
                        .getTransferStatus(), StringField.TYPE_STORED));

        // product type
        doc
                .add(new Field("product_type_id", product.getProductType()
                        .getProductTypeId(), StringField.TYPE_STORED));
        doc.add(new Field("product_type_name", product.getProductType()
                .getName(), StringField.TYPE_STORED));
        doc.add(new Field("product_type_desc", product.getProductType()
                .getDescription() != null ? product.getProductType()
                .getDescription() : "", StringField.TYPE_STORED));
        doc.add(new Field("product_type_repoPath", product.getProductType()
                .getProductRepositoryPath() != null ? product.getProductType()
                .getProductRepositoryPath() : "", StringField.TYPE_STORED));
        doc.add(new Field("product_type_versioner", product.getProductType()
                .getVersioner() != null ? product.getProductType()
                .getVersioner() : "", StringField.TYPE_STORED));
        
        // write metadata fields to the Lucene document
        List<String> keys = new ArrayList<String>();
        // validation layer: add only specifically configured keys
        if (valLayer!=null) {
        	List<Element> elements = quietGetElements(product.getProductType());
            for (Element element : elements) {
                String key = element.getElementName();
                keys.add(key);
            }
        // no validation layer: add all keys that are NOT already in doc
        // (otherwise some keys such as the product_* keys are duplicated)
        } else {
        	for (String key : metadata.getAllKeys()) {
        		if (doc.getField(key)==null) {
        				keys.add(key);
        		}
        	}
        }


        for (String key : keys) {
          List<String> values = metadata.getAllMetadata(key);

            if (values == null) {
                LOG
                        .log(
                                Level.WARNING,
                                "No Metadata specified for product ["
                                        + product.getProductName()
                                        + "] for required field ["
                                        + key
                                        + "]: Attempting to continue processing metadata");
                continue;
            }

            for (String val : values) {
                doc.add(new Field(key, val, StringField.TYPE_STORED));
            }

            // Exactly one doc values entry per field per document, always.
            // This used to be written only when the field happened to have a
            // single value, so whether a field could be sorted on depended on
            // how many values one product happened to carry: ingest a product
            // with two ProductTypes and the field lost its doc values for
            // every product after it. Lucene tolerated the inconsistency once
            // and now refuses the document outright, which is the better
            // behaviour and is what surfaced this.
            //
            // A sorted doc values field holds one value, so a multi-valued
            // field sorts on its first. That is a choice, but it is a
            // consistent one, and it is what the single-valued case already
            // did.
            if (!values.isEmpty()) {
                doc.add(new SortedDocValuesField(key,
                        new BytesRef(values.get(0))));
            }
        }

        // add the product references
        for (Reference r : product.getProductReferences()) {
            doc.add(new Field("reference_orig", r.getOrigReference(),
                    StringField.TYPE_STORED));
            doc
                .add(new Field("reference_data_store", r
                    .getDataStoreReference(), StringField.TYPE_STORED));
            doc.add(new Field("reference_fileSize", String.valueOf(r
                .getFileSize()), StringField.TYPE_STORED));
            doc.add(new Field("reference_mimeType", r.getMimeType() != null ? r
                .getMimeType().getName() : "", StringField.TYPE_STORED));
        }

        // add special field for all products
        // then can use that field to retrieve back all products
        doc.add(new Field("myfield", "myvalue", StringField.TYPE_STORED));

        return doc;
    }

    private boolean hasMetadataAndRefs(CompleteProduct cp) {
        if (cp.getMetadata() != null && cp.getProduct() != null) {
            if (cp.getReferences() != null && cp.getReferences().size() > 0) {
                // make sure there is a data store ref for each of the refs
                for (Reference r : cp.getReferences()) {
                    if (r.getDataStoreReference() == null || (r.getDataStoreReference().equals(""))) {
                        return false;
                    }
                }

                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }

    }

    private int getNumHits(Query query, ProductType type)
            throws CatalogException {
        IndexSearcher searcher = null;
        DirectoryReader reader = null;

        int numHits = -1;
        reader = openReader();
        try {
            searcher = new IndexSearcher(reader);

            // construct a Boolean query here
            BooleanQuery.Builder booleanQuery =  new BooleanQuery.Builder();

            // add the product type as the first clause
            org.apache.lucene.search.Query prodTypeTermQuery = new TermQuery(new Term(
                    "product_type_id", type.getProductTypeId()));
            booleanQuery.add(prodTypeTermQuery, BooleanClause.Occur.MUST);

            //convert filemgr query into a lucene query
            for (QueryCriteria queryCriteria : query.getCriteria()) {
                booleanQuery.add(this.getQuery(queryCriteria), BooleanClause.Occur.MUST);
            }

            LOG.log(Level.FINE, "Querying LuceneCatalog: q: [" + booleanQuery
                    + "]");

            // count(), since the number of results is the answer this method
            // returns. Reading it off a search sized to one hit gave 1 for any
            // non-empty query once Lucene stopped counting past what it needed.
            numHits = searcher.count(booleanQuery.build());
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage());
        } finally {
            closeQuietly(reader);
        }

        return numHits;
    }

    private synchronized List<Product> paginateQuery(Query query, ProductType type, int pageNum, ProductPage page)
            throws CatalogException {
        List<Product> products = new Vector<Product>(pageSize);
        IndexSearcher searcher = null;
        DirectoryReader reader = null;

        boolean doSkip = true;

        if (pageNum == -1) {
            doSkip = false;
        }

        reader = openReader();

        try {
            searcher = new IndexSearcher(reader);

            // construct a Boolean query here
            BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();

            // add the product type as the first clause
            TermQuery prodTypeTermQuery = new TermQuery(new Term(
                    "product_type_id", type.getProductTypeId()));
            booleanQuery.add(prodTypeTermQuery, BooleanClause.Occur.MUST);

            //convert filemgr query into a lucene query
            for (QueryCriteria queryCriteria : query.getCriteria()) {
                booleanQuery.add(this.getQuery(queryCriteria), BooleanClause.Occur.MUST);
            }

            Sort sort = new Sort(new SortField("CAS.ProductReceivedTime",
                    SortField.Type.STRING, true));
            LOG.log(Level.FINE, "Querying LuceneCatalog: q: [" + booleanQuery
                    + "]");
            //TODO FIX NUMBER OF RECORDS
            // count() rather than reading totalHits off a one-hit search.
            // Until Lucene 8 that count was exact; now a search stops
            // counting once it has enough hits to answer and reports a
            // lower bound, so asking for one hit and trusting the count
            // would fetch one record where there are hundreds.
            int checkCount = searcher.count(booleanQuery.build());
            if(checkCount > 0) {
                TopDocs topDocs = searcher.search(booleanQuery.build(), checkCount, sort);

                // Calculate page size and set it while we have the results
                if (page != null) {
                    page.setTotalPages(PaginationUtils.getTotalPage(checkCount, pageSize));
                }

                ScoreDoc[] hits = topDocs.scoreDocs;

                if (hits.length > 0) {

                    int startNum = (pageNum - 1) * pageSize;
                    if (doSkip) {
                        if (startNum > hits.length) {
                            startNum = 0;
                        }

                        for (int i = startNum; i < Math.min(hits.length,
                            (startNum + pageSize)); i++) {
                            Document productDoc = searcher.storedFields().document(hits[i].doc);

                            CompleteProduct prod = toCompleteProduct(productDoc,
                                false, false);
                            products.add(prod.getProduct());
                        }
                    } else {
                        products = new Vector<Product>(hits.length);
                        for (int i = 0; i < hits.length; i++) {
                            Document productDoc = searcher.storedFields().document(hits[i].doc);

                            CompleteProduct prod = toCompleteProduct(productDoc,
                                false, false);
                            products.add(prod.getProduct());
                        }
                    }
                } else {
                    LOG.log(Level.WARNING, "Query: [" + query
                        + "] for Product Type: [" + type.getProductTypeId()
                        + "] returned no results");
                }
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage());
        } finally {
            closeQuietly(reader);
        }

        return products;

    }

    private org.apache.lucene.search.Query getQuery(QueryCriteria queryCriteria) throws CatalogException {
        if (queryCriteria instanceof BooleanQueryCriteria) {
            BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
            BooleanClause.Occur occur;
            switch (((BooleanQueryCriteria) queryCriteria).getOperator()) {
            case BooleanQueryCriteria.AND:
                occur = BooleanClause.Occur.MUST;
                break;
            case BooleanQueryCriteria.OR:
                occur = BooleanClause.Occur.SHOULD;
                break;
            case BooleanQueryCriteria.NOT:
                occur = BooleanClause.Occur.MUST_NOT;
                booleanQuery.add(new WildcardQuery(new Term(((BooleanQueryCriteria) queryCriteria)
                        .getTerms().get(0).getElementName(), "*")), BooleanClause.Occur.SHOULD);
                break;
            default:
                throw new CatalogException("Invalid BooleanQueryCriteria opertor [" 
                        + ((BooleanQueryCriteria) queryCriteria).getOperator() + "]");
            }
            for (QueryCriteria qc : ((BooleanQueryCriteria) queryCriteria).getTerms()) {
                booleanQuery.add(this.getQuery(qc), occur);
            }

            return booleanQuery.build();
        } else if (queryCriteria instanceof TermQueryCriteria) {
            String val = ((TermQueryCriteria) queryCriteria).getValue();
            return new TermQuery(new Term(queryCriteria.getElementName(), val));
        } else if (queryCriteria instanceof RangeQueryCriteria) {
            String startVal = ((RangeQueryCriteria) queryCriteria).getStartValue();
            String endVal = ((RangeQueryCriteria) queryCriteria).getEndValue();
            boolean inclusive = ((RangeQueryCriteria) queryCriteria).getInclusive();
            // This used to build a Term from startVal only to read .field()
            // back off it, which is queryCriteria.getElementName() either way,
            // so both branches produced the same string and neither Term was
            // used for anything else. The startVal.equals("") that chose
            // between them is what turned an open lower bound into a
            // NullPointerException; newStringRange takes null for an open end
            // directly.
            return TermRangeQuery.newStringRange(queryCriteria.getElementName(),
                    startVal, endVal, inclusive, inclusive);
        } else {
            throw new CatalogException("Invalid QueryCriteria ["
                    + queryCriteria.getClass().getCanonicalName() + "]");
        }
    }
    
    private List<Element> quietGetElements(ProductType type) {
        List<Element> elementList = new Vector<Element>();

        try {
            elementList = valLayer.getElements(type);
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    "Exception obtaining elements for product type: ["
                            + type.getName() + "]: Message: " + e.getMessage());
        }

        return elementList;
    }

    private class CompleteProduct {
        private Metadata metadata = null;

        private Product product = null;

        public CompleteProduct(Metadata met, List<Reference> refs, Product p) {
            this.metadata = met;
            this.product = p;
            this.product.setProductReferences(refs);
        }

        public CompleteProduct() {
        }

        /**
         * @return Returns the metadata.
         */
        public Metadata getMetadata() {
            return metadata;
        }

        /**
         * @param metadata
         *            The metadata to set.
         */
        public void setMetadata(Metadata metadata) {
            this.metadata = metadata;
        }

        /**
         * @return Returns the product.
         */
        public Product getProduct() {
            return product;
        }

        /**
         * @param product
         *            The product to set.
         */
        public void setProduct(Product product) {
            this.product = product;
        }

        /**
         * @return Returns the references.
         */
        public List<Reference> getReferences() {
            return product.getProductReferences();
        }

        /**
         * @param references
         *            The references to set.
         */
        public void setReferences(List<Reference> references) {
            this.product.setProductReferences(references);
        }

    }

}
