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

import java.net.URL;
import java.util.Properties;
//OODT imports
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.exceptions.VersioningException;
import org.apache.oodt.cas.metadata.Metadata;

//Junit imports
import junit.framework.TestCase;

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * A Test Case for the MetadataBasedFileVersioner.
 * </p>.
 */
public class TestMetadataBasedFileVersioner extends TestCase {

	private String productTypePath = "file:/foo/bar";

  private Properties initialProperties = new Properties(System.getProperties());

  public void setUp() throws Exception {
    Properties properties = new Properties(System.getProperties());
    URL url = this.getClass().getResource("/mime-types.xml");
    properties.setProperty("org.apache.oodt.cas.filemgr.mime.type.repository",
        new File(url.getFile()).getAbsolutePath());
    System.setProperties(properties);
  }

  public void tearDown() throws Exception {
    System.setProperties(initialProperties);
  }

	public void testVersionerNoStatic() {
		String filePathSpec = "/[ProductType]/[ProductionDate]/[Filename]";
		Product product = new Product();
		product.setProductStructure(Product.STRUCTURE_FLAT);
		ProductType type = new ProductType();
		type.setProductRepositoryPath(productTypePath);
		product.setProductType(type);

		Metadata metadata = new Metadata();
		metadata.addMetadata("ProductType", "FooFile");
		metadata.addMetadata("ProductionDate", "060804");
		metadata.addMetadata("Filename", "foo.txt");

		Reference r = new Reference();
		product.getProductReferences().add(r);

		MetadataBasedFileVersioner versioner = new MetadataBasedFileVersioner(
				filePathSpec);
		try {
			versioner.createDataStoreReferences(product, metadata);
		} catch (VersioningException e) {
			fail(e.getMessage());
		}

		String expected = "file:/foo/bar/FooFile/060804/foo.txt";
		assertEquals("The reference: [" + r.getDataStoreReference()
				+ "] is not equal to: [" + expected + "]", expected, r
				.getDataStoreReference());
	}

	public void testVersionerWithStatic() {
		String filePathSpec = "/[ProductType]/some/other/path[ProductionDate]/[Filename]";
		Product product = new Product();
		product.setProductStructure(Product.STRUCTURE_FLAT);
		ProductType type = new ProductType();
		type.setProductRepositoryPath(productTypePath);
		product.setProductType(type);

		Metadata metadata = new Metadata();
		metadata.addMetadata("ProductType", "FooFile");
		metadata.addMetadata("ProductionDate", "060804");
		metadata.addMetadata("Filename", "foo.txt");

		Reference r = new Reference();
		product.getProductReferences().add(r);

		MetadataBasedFileVersioner versioner = new MetadataBasedFileVersioner(
				filePathSpec);
		try {
			versioner.createDataStoreReferences(product, metadata);
		} catch (VersioningException e) {
			fail(e.getMessage());
		}

		String expected = "file:/foo/bar/FooFile/some/other/path060804/foo.txt";
		assertEquals("The reference: [" + r.getDataStoreReference()
				+ "] is not equal to: [" + expected + "]", expected, r
				.getDataStoreReference());
	}

  private static Product flatProductIn(String repoPath) {
    Product product = new Product();
    product.setProductStructure(Product.STRUCTURE_FLAT);
    ProductType type = new ProductType();
    type.setProductRepositoryPath(repoPath);
    product.setProductType(type);
    product.getProductReferences().add(new Reference());
    return product;
  }

  private static Metadata metadataWithFilename(String filename) {
    Metadata metadata = new Metadata();
    metadata.addMetadata("Filename", filename);
    return metadata;
  }

  /**
   * The substituted values are product metadata, extracted from the file being
   * ingested, so whoever produces the data has influence over where the
   * archive writes it. A crawler watching a drop directory is the ordinary
   * deployment.
   */
  public void testTraversalOutOfTheRepositoryIsRejected() {
    Product product = flatProductIn(productTypePath);
    Metadata metadata = metadataWithFilename("../../../../../../tmp/pwned");

    try {
      new MetadataBasedFileVersioner("/[Filename]/out.dat")
          .createDataStoreReferences(product, metadata);
      fail("a metadata value walked out of the repository and was accepted: "
          + ((Reference) product.getProductReferences().get(0)).getDataStoreReference());
    } catch (VersioningException expected) {
      // the point
    }
  }

  /** A single .. is enough; it does not need to be a long climb. */
  public void testSingleDotDotIsRejected() {
    Product product = flatProductIn(productTypePath);

    try {
      new MetadataBasedFileVersioner("/[Filename]/out.dat")
          .createDataStoreReferences(product, metadataWithFilename(".."));
      fail("a .. segment was accepted: "
          + ((Reference) product.getProductReferences().get(0)).getDataStoreReference());
    } catch (VersioningException expected) {
      // the point
    }
  }

  /** A traversal written into the spec itself is no more acceptable. */
  public void testTraversalInTheSpecIsRejected() {
    Product product = flatProductIn(productTypePath);

    try {
      new MetadataBasedFileVersioner("/../../etc/[Filename]")
          .createDataStoreReferences(product, metadataWithFilename("passwd"));
      fail("a traversal in the path spec was accepted: "
          + ((Reference) product.getProductReferences().get(0)).getDataStoreReference());
    } catch (VersioningException expected) {
      // the point
    }
  }

  /** Ordinary values must keep working, dots and all. */
  public void testOrdinaryFilenameIsUnaffected() throws Exception {
    Product product = flatProductIn(productTypePath);
    new MetadataBasedFileVersioner("/[Filename]")
        .createDataStoreReferences(product, metadataWithFilename("foo.txt"));

    assertEquals("file:/foo/bar/foo.txt",
        ((Reference) product.getProductReferences().get(0)).getDataStoreReference());
  }

  /** A dot inside a name is not a traversal and must survive. */
  public void testDottedFilenameIsUnaffected() throws Exception {
    Product product = flatProductIn(productTypePath);
    new MetadataBasedFileVersioner("/[Filename]")
        .createDataStoreReferences(product, metadataWithFilename("v1.2.3.tar.gz"));

    assertEquals("file:/foo/bar/v1.2.3.tar.gz",
        ((Reference) product.getProductReferences().get(0)).getDataStoreReference());
  }

  /** A .. that cancels out stays inside, so it is allowed. */
  public void testTraversalThatReturnsInsideIsAllowed() throws Exception {
    Product product = flatProductIn(productTypePath);
    new MetadataBasedFileVersioner("/sub/../[Filename]")
        .createDataStoreReferences(product, metadataWithFilename("foo.txt"));

    assertEquals("file:/foo/bar/foo.txt",
        ((Reference) product.getProductReferences().get(0)).getDataStoreReference());
  }
}
