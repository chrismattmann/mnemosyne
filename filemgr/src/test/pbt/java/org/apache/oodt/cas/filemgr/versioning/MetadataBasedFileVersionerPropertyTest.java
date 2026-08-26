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

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.exceptions.VersioningException;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties for {@link MetadataBasedFileVersioner}, which decides where in the
 * archive a product's file is written by substituting the product's metadata
 * into a path template.
 *
 * <p>The template is set by an administrator, but the values substituted into
 * it are not: they come from whatever the metadata extractors pulled out of the
 * file being ingested. The archive layout therefore depends on data the
 * archive's operator does not control, and the one thing that has to hold
 * however that data reads is that the file lands inside the product type's own
 * repository.
 *
 * <p>Nothing here writes to disk. The versioner only assembles the path string;
 * the move happens elsewhere.
 */
class MetadataBasedFileVersionerPropertyTest {

  private static final String REPO_PATH = "/tmp/oodt-versioner-repo";

  private static Generator<String> words() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  /**
   * File names of the kind a metadata extractor can produce: ordinary names,
   * and names carrying the path separators and parent references that a
   * producer's own directory layout leaks into a {@code Filename} field.
   */
  private static Generator<String> extractedFileNames() {
    return lists(sampledFrom(List.of("a", "b", "9", ".", "/", "..", "-")))
        .minSize(1)
        .maxSize(6)
        .map(parts -> String.join("", parts));
  }

  private static Product flatProduct(String repoUri) {
    Product product = new Product();
    product.setProductName("granule");
    product.setProductStructure(Product.STRUCTURE_FLAT);
    ProductType type = new ProductType();
    type.setProductTypeId("type-1");
    type.setName("GenericFile");
    type.setProductRepositoryPath(repoUri);
    product.setProductType(type);
    List<Reference> refs = new ArrayList<>();
    Reference r = new Reference();
    r.setOrigReference("file:/incoming/granule.dat");
    r.setFileSize(1L);
    refs.add(r);
    product.setProductReferences(refs);
    return product;
  }

  private static String versionedPath(String repoUri, String spec, Metadata metadata)
      throws Exception {
    Product product = flatProduct(repoUri);
    new MetadataBasedFileVersioner(spec).createDataStoreReferences(product, metadata);
    String dataStoreRef = product.getProductReferences().get(0).getDataStoreReference();
    return new File(new URI(dataStoreRef)).getPath();
  }

  /**
   * The versioned file must land inside the product type's repository.
   *
   * <p>The repository path is the archive's boundary: it is what the operator
   * configured, what disk quotas are set against, and what backups cover. A
   * metadata value that walks out of it puts an ingested file somewhere nobody
   * is looking after, chosen by whoever produced the file rather than by
   * whoever runs the archive.
   */
  @HegelTest(testCases = 2000)
  void theVersionedFileStaysInsideTheRepository(TestCase tc) throws Exception {
    String fileName = tc.draw(extractedFileNames(), "Filename");

    Metadata metadata = new Metadata();
    metadata.replaceMetadata("Filename", fileName);

    String path = versionedPath("file:" + REPO_PATH, "/[Filename]", metadata);
    String normalised = Paths.get(path).normalize().toString();
    tc.note(fileName + " -> " + normalised);

    assertTrue(
        normalised.startsWith(REPO_PATH),
        "'" + fileName + "' versioned to " + normalised + ", outside " + REPO_PATH);
  }

  /**
   * A repository path written with a trailing separator must give the same
   * result as one written without.
   *
   * <p>Both spellings are accepted in a product type's XML, and an archive that
   * lays a file out differently depending on which the operator happened to
   * type would split one product type's holdings across two directories.
   */
  @HegelTest
  void aTrailingSeparatorOnTheRepositoryMakesNoDifference(TestCase tc) throws Exception {
    String fileName = tc.draw(words(), "Filename");

    Metadata metadata = new Metadata();
    metadata.replaceMetadata("Filename", fileName);

    assertEquals(
        versionedPath("file:" + REPO_PATH, "/[Filename]", metadata),
        versionedPath("file:" + REPO_PATH + "/", "/[Filename]", metadata),
        "the trailing separator on the repository path changed the result");
  }

  /**
   * A template written with a leading separator must give the same result as
   * one written without.
   *
   * <p>The class documents its template as starting with a separator and then
   * repairs one that does not, so the two spellings have to agree.
   */
  @HegelTest
  void aLeadingSeparatorOnTheTemplateMakesNoDifference(TestCase tc) throws Exception {
    String fileName = tc.draw(words(), "Filename");
    String dir = tc.draw(words(), "dir");

    Metadata metadata = new Metadata();
    metadata.replaceMetadata("Filename", fileName);

    assertEquals(
        versionedPath("file:" + REPO_PATH, "/" + dir + "/[Filename]", metadata),
        versionedPath("file:" + REPO_PATH, dir + "/[Filename]", metadata),
        "the leading separator on the template changed the result");
  }

  /**
   * Every metadata field named in the template must appear in the versioned
   * path.
   *
   * <p>A field left unsubstituted, or substituted with the wrong value, means
   * two products that should be filed apart are filed on top of each other.
   */
  @HegelTest
  void everyFieldInTheTemplateIsSubstituted(TestCase tc) throws Exception {
    String year = tc.draw(words(), "year");
    String fileName = tc.draw(words(), "Filename");

    Metadata metadata = new Metadata();
    metadata.replaceMetadata("Year", year);
    metadata.replaceMetadata("Filename", fileName);

    String path = versionedPath("file:" + REPO_PATH, "/[Year]/[Filename]", metadata);
    tc.note(path);

    assertEquals(REPO_PATH + "/" + year + "/" + fileName, path);
  }

  /**
   * Versioning without metadata must be refused rather than guessed at.
   *
   * <p>The class cannot do its job without metadata and says so; the caller
   * needs the refusal, because the alternative is a file archived under a path
   * built from nothing.
   */
  @HegelTest
  void versioningWithoutMetadataIsRefused(TestCase tc) {
    String spec = "/" + tc.draw(words(), "dir") + "/[Filename]";

    Product product = flatProduct("file:" + REPO_PATH);

    assertThrows(
        VersioningException.class,
        () -> new MetadataBasedFileVersioner(spec).createDataStoreReferences(product, null),
        "a product with no metadata was versioned anyway");
  }

  /**
   * A product that is not flat must be refused by default.
   *
   * <p>The class handles a single reference only and defaults to saying so.
   * Silently versioning the first file of a directory tree and leaving the rest
   * unversioned would archive part of a product.
   */
  @HegelTest
  void aNonFlatProductIsRefusedByDefault(TestCase tc) {
    String structure =
        tc.draw(
            sampledFrom(List.of(Product.STRUCTURE_HIERARCHICAL, Product.STRUCTURE_STREAM)),
            "structure");
    String fileName = tc.draw(words(), "Filename");

    Product product = flatProduct("file:" + REPO_PATH);
    product.setProductStructure(structure);
    Metadata metadata = new Metadata();
    metadata.replaceMetadata("Filename", fileName);

    assertThrows(
        VersioningException.class,
        () ->
            new MetadataBasedFileVersioner("/[Filename]")
                .createDataStoreReferences(product, metadata),
        "a " + structure + " product was versioned as though it were flat");
  }
}
