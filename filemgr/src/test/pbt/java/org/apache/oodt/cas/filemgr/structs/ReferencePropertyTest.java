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

package org.apache.oodt.cas.filemgr.structs;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.apache.tika.mime.MimeType;

/**
 * Properties for {@link Reference}, the record of where one file of a product
 * came from and where the archive put it.
 *
 * <p>A reference is copied at several points on the ingest path — into the
 * versioner, out of the catalog, across the RPC boundary — and each copy is
 * expected to be indistinguishable from its original. These properties pin
 * that, and the handling of the mime type, which is the one field the class
 * does more than store.
 */
class ReferencePropertyTest {

  private static Generator<String> words() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  private static Generator<String> mimeTypeNames() {
    return sampledFrom(List.of("text/plain", "application/xml", "image/png", "text/html"));
  }

  private static Reference reference(TestCase tc) {
    Reference r = new Reference();
    r.setOrigReference("file:/data/" + tc.draw(words(), "orig"));
    r.setDataStoreReference("file:/archive/" + tc.draw(words(), "dataStore"));
    r.setFileSize(tc.draw(longs().min(0).max(1_000_000_000L), "fileSize"));
    r.setMimeType(tc.draw(mimeTypeNames(), "mimeType"));
    return r;
  }

  /**
   * A copied reference must carry everything the original carried.
   *
   * <p>A copy that loses the data store reference points at nothing; a copy
   * that loses the file size makes every transfer look complete.
   */
  @HegelTest
  void theCopyConstructorPreservesEveryField(TestCase tc) {
    Reference original = reference(tc);

    Reference copy = new Reference(original);

    assertEquals(original.getOrigReference(), copy.getOrigReference());
    assertEquals(original.getDataStoreReference(), copy.getDataStoreReference());
    assertEquals(original.getFileSize(), copy.getFileSize());
    assertSame(original.getMimeType(), copy.getMimeType());
  }

  /** Copying is idempotent: a copy of a copy is still the same reference. */
  @HegelTest
  void copyingTwiceChangesNothingFurther(TestCase tc) {
    Reference original = reference(tc);

    Reference twice = new Reference(new Reference(original));

    assertEquals(original.toString(), twice.toString(), "the reference changed on the second copy");
  }

  /**
   * A mime type set by name must be readable back under that name.
   *
   * <p>The catalog stores the name as a string and the class resolves it
   * against Tika's repository on the way back in, so the two spellings have to
   * agree or a reference read from the catalog claims a different type from the
   * one that was written.
   */
  @HegelTest
  void aMimeTypeSetByNameIsReadableBackUnderThatName(TestCase tc) {
    String name = tc.draw(mimeTypeNames(), "mimeType");

    Reference r = new Reference();
    r.setMimeType(name);

    MimeType resolved = r.getMimeType();
    assertNotNull(resolved, "the mime type '" + name + "' did not resolve");
    assertEquals(name, resolved.getName());
  }

  /**
   * Setting an empty mime type name must leave the reference as it was.
   *
   * <p>The class guards explicitly against a null or empty name, which is what
   * a catalog row with no recorded type yields. Clearing a known type on the
   * strength of a missing one would lose information that is already held.
   */
  @HegelTest
  void anEmptyMimeTypeNameLeavesTheExistingTypeAlone(TestCase tc) {
    String name = tc.draw(mimeTypeNames(), "mimeType");
    boolean useNull = tc.draw(booleans(), "useNull");
    String blank = useNull ? null : "";

    Reference r = new Reference();
    r.setMimeType(name);
    MimeType before = r.getMimeType();
    r.setMimeType(blank);

    assertSame(before, r.getMimeType(), "an absent mime type name cleared a known one");
  }

  /**
   * A reference built from its three-argument constructor must keep what it was
   * given and work out a type for itself.
   *
   * <p>This is the constructor the ingest path uses when it only knows the
   * paths and the size; the class promises to detect the type from the original
   * reference rather than leaving the caller with nothing.
   *
   * <p>Fewer cases than the default here: this constructor builds a fresh Tika
   * instance on every call, so each case costs a config parse.
   */
  @HegelTest(testCases = 25)
  void theThreeArgumentConstructorKeepsWhatItIsGiven(TestCase tc) {
    String orig = "file:/data/" + tc.draw(words(), "orig") + ".txt";
    String dataStore = "file:/archive/" + tc.draw(words(), "dataStore") + ".txt";
    long size = tc.draw(longs().min(0).max(1_000_000_000L), "fileSize");

    Reference r = new Reference(orig, dataStore, size);

    assertEquals(orig, r.getOrigReference());
    assertEquals(dataStore, r.getDataStoreReference());
    assertEquals(size, r.getFileSize());
    assertNotNull(r.getMimeType(), "no mime type was detected for " + orig);
  }
}
