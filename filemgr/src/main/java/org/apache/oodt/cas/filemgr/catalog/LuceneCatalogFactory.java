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

//JDK imports
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.logging.Logger;

//OODT imports
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.oodt.cas.filemgr.util.GenericFileManagerObjectFactory;
import org.apache.oodt.cas.metadata.util.PathUtils;
import org.apache.oodt.cas.filemgr.validation.ValidationLayer;

//Lucene imports
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * A Factory for creating {@link Lucene}-based {@link Catalog}s.
 * </p>
 * 
 */
public class LuceneCatalogFactory implements CatalogFactory {

  public static final int VAL = 20;
  public static final int VAL1 = 60;
  public static final int VAL2 = 60;
  public static final int VAL3 = 20;
  /* path to the index directory for lucene catalogs */
	private String indexFilePath = null;
	private IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());

	/* our validation layer */
	private ValidationLayer validationLayer = null;
	
	/* the page size for pagination */
	private int pageSize = -1;
	
	/* the write lock timeout */
	private long writeLockTimeOut = -1L;
	
	/* the commit lock timeout */
	private long commitLockTimeOut = -1L;
	
	/* the merge factor */
	private int mergeFactor = -1;
	
	/* Whether or not to enforce strict definition of metadata fields:
	 * 'lenient=false' means that all metadata fields need to be explicitly defined in the XML configuration file */
	private boolean lenientFields = false;
	
	/* our log stream */
    private static final Logger LOG = Logger.getLogger(LuceneCatalogFactory.class.getName());
    
	/**
	 * 
	 */
	public LuceneCatalogFactory() throws IllegalArgumentException {
		indexFilePath = System
				.getProperty("org.apache.oodt.cas.filemgr.catalog.lucene.idxPath");
		if (indexFilePath == null) {
			throw new IllegalArgumentException(
					"error initializing lucene catalog: "
							+ "[org.apache.oodt.cas.filemgr.catalog.lucene.idxPath="
							+ null);
		}

		//do env var replacement
		indexFilePath = PathUtils.replaceEnvVariables(indexFilePath);
		
		// instantiate validation layer, unless catalog is explicitly configured for lenient fields
		lenientFields = Boolean.parseBoolean( System.getProperty("org.apache.oodt.cas.filemgr.catalog.lucene.lenientFields", "false") );
		if (!lenientFields) {
			String validationLayerFactoryClass = System
				.getProperty("filemgr.validationLayer.factory",
						"org.apache.oodt.cas.filemgr.validation.XMLValidationLayerFactory");
			validationLayer = GenericFileManagerObjectFactory
				.getValidationLayerFromFactory(validationLayerFactoryClass);
		}
		
		pageSize = Integer.getInteger("org.apache.oodt.cas.filemgr.catalog.lucene.pageSize", VAL);
		
		commitLockTimeOut = Long
			.getLong(
				"org.apache.oodt.cas.filemgr.catalog.lucene.commitLockTimeout.seconds",
				VAL1);
		writeLockTimeOut = Long
			.getLong(
				"org.apache.oodt.cas.filemgr.catalog.lucene.writeLockTimeout.seconds",
				VAL2);
		mergeFactor = Integer.getInteger(
			"org.apache.oodt.cas.filemgr.catalog.lucene.mergeFactor", VAL3);
	}

	/**
	 * Whether this directory already holds a Lucene index.
	 *
	 * <p>
	 * A missing directory and an empty one both mean the same thing here:
	 * there is no index yet, so one has to be made. Asking Lucene rather
	 * than asking the filesystem also covers a directory holding files that
	 * are not an index.
	 * </p>
	 */
	private boolean hasIndex(File indexDir) {
	    if (!indexDir.exists()) {
	        return false;
	    }
	    Directory dir = null;
	    try {
	        dir = FSDirectory.open(indexDir.toPath());
	        return DirectoryReader.indexExists(dir);
	    } catch (IOException e) {
	        LOG.warning("Unable to read the index at [" + indexDir + "]: "
	            + e.getMessage() + "; treating it as one to create");
	        return false;
	    } finally {
	        if (dir != null) {
	            try {
	                dir.close();
	            } catch (IOException ignored) {
	            }
	        }
	    }
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.oodt.cas.filemgr.catalog.CatalogFactory#createCatalog()
	 */
	public Catalog createCatalog() {
	    File indexDir = new File(indexFilePath);
	    // Create the index if there is not one there already.
	    //
	    // The test used to be whether the directory exists, so a directory
	    // that exists and holds no index was left exactly as it was found.
	    // That is an ordinary state -- a fresh volume, a catalog someone has
	    // just emptied -- and the file manager came up on it, listened, and
	    // then failed every call with "no segments* file found in
	    // MMapDirectory". A process that answers nothing while holding its
	    // port open reads as a service that is up, and the crawl that
	    // follows reports "Exception connecting to filemgr" once per file.
	    IndexWriter writer = null;
		config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
	    if (!hasIndex(indexDir)) {
	        try {
				try {
					Directory indexDir2 = FSDirectory.open(new File( indexFilePath ).toPath());
					writer = new IndexWriter(indexDir2, config);
				} catch (IOException e) {
					e.printStackTrace();
				}

	        } catch (Exception e) {
	            LOG.severe("Unable to create index: " + e.getMessage());
	        } finally {
	            if (writer != null) {
	                try {
	                    writer.close();
	                } catch (Exception e) {
	                    LOG.severe("Unable to close index: " + e.getMessage());
	                }
	            }
	        }
	    }
		return new LuceneCatalog(indexFilePath, validationLayer, pageSize,
				commitLockTimeOut, writeLockTimeOut, mergeFactor);
	}

}
