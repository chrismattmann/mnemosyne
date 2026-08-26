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

package org.apache.oodt.cas.filemgr.structs.type.examples;

import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.QueryCriteria;
import org.apache.oodt.cas.filemgr.structs.RangeQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.type.TypeHandler;
import org.apache.oodt.cas.metadata.Metadata;

/**
 * Properties for {@link MajorMinorVersionTypeHandler}, the worked example of a
 * {@code ValueReplaceTypeHandler} shipped with the File Manager.
 *
 * <p>A type handler exists so a catalog that can only compare strings can
 * answer range queries correctly: it rewrites a value on the way in so that
 * lexicographic order over the stored form matches the intended order over the
 * real form, and rewrites it back on the way out so the user never sees the
 * padding. Two things follow, and both are what the class is for rather than
 * incidental to it — the stored form must sort in the same order as the real
 * values, and the value a user gets back must mean what they put in.
 *
 * <p>The class documents its accepted input as {@code \d{1,2}.\d{0,2}}, so the
 * generators below produce exactly that and nothing wider.
 */
class MajorMinorVersionTypeHandlerPropertyTest {

  private static final String ELEMENT_NAME = "ProductVersion";

  private static MajorMinorVersionTypeHandler handler() {
    MajorMinorVersionTypeHandler handler = new MajorMinorVersionTypeHandler();
    handler.setElementName(ELEMENT_NAME);
    return handler;
  }

  /** Versions of the form {@code \d{1,2}.\d{1,2}}, the class's documented input. */
  private static Generator<String> versions() {
    return integers()
        .min(0)
        .max(99)
        .flatMap(
            major ->
                integers().min(0).max(99).map(minor -> major + "." + minor));
  }

  /** The numeric value of a {@code major.minor} string, read as the class reads it. */
  private static double asNumber(String version) {
    return Double.parseDouble(version);
  }

  /**
   * The catalog form must sort in the same order as the values it stands for.
   *
   * <p>This is the whole reason the handler exists. A range query is answered
   * by string comparison inside the catalog, so if two versions compare one way
   * as numbers and the other way as stored strings, the range returns the wrong
   * products and reports no error.
   */
  @HegelTest(testCases = 2000)
  void theCatalogFormSortsLikeTheRealValues(TestCase tc) {
    String left = tc.draw(versions(), "left");
    String right = tc.draw(versions(), "right");

    Metadata leftMet = new Metadata();
    leftMet.replaceMetadata(ELEMENT_NAME, left);
    Metadata rightMet = new Metadata();
    rightMet.replaceMetadata(ELEMENT_NAME, right);
    handler().preAddMetadataHandle(leftMet);
    handler().preAddMetadataHandle(rightMet);

    String leftStored = leftMet.getMetadata(ELEMENT_NAME);
    String rightStored = rightMet.getMetadata(ELEMENT_NAME);
    tc.note(left + " -> " + leftStored + " ; " + right + " -> " + rightStored);

    int numericOrder = Double.compare(asNumber(left), asNumber(right));
    int storedOrder = Integer.signum(leftStored.compareTo(rightStored));

    assertEquals(
        Integer.signum(numericOrder),
        storedOrder,
        left + " and " + right + " sort differently once stored as "
            + leftStored + " and " + rightStored);
  }

  /**
   * A version written to the catalog and read back must mean the same version.
   *
   * <p>The class documents its output format as {@code \d{1,2}.\d{1,2}} rather
   * than promising the exact string back, so the comparison here is on the
   * value and not on the spelling.
   */
  @HegelTest
  void aVersionReadBackMeansTheSameThing(TestCase tc) {
    String version = tc.draw(versions(), "version");

    Metadata metadata = new Metadata();
    metadata.replaceMetadata(ELEMENT_NAME, version);
    handler().preAddMetadataHandle(metadata);
    String stored = metadata.getMetadata(ELEMENT_NAME);
    tc.note("stored = " + stored);
    handler().postGetMetadataHandle(metadata);
    String returned = metadata.getMetadata(ELEMENT_NAME);

    assertEquals(
        asNumber(version),
        asNumber(returned),
        version + " came back as " + returned + " (stored as " + stored + ")");
  }

  /**
   * Storing an already-stored value must not change it again.
   *
   * <p>Metadata is re-written on every catalog update, and nothing in the
   * File Manager tracks whether a given {@link Metadata} has already been
   * through {@code preAddMetadataHandle}. If the conversion is not a fixed
   * point, a product's version drifts each time it is touched.
   */
  @HegelTest
  void storingIsAFixedPoint(TestCase tc) {
    String version = tc.draw(versions(), "version");

    Metadata metadata = new Metadata();
    metadata.replaceMetadata(ELEMENT_NAME, version);
    handler().preAddMetadataHandle(metadata);
    String once = metadata.getMetadata(ELEMENT_NAME);
    handler().preAddMetadataHandle(metadata);
    String twice = metadata.getMetadata(ELEMENT_NAME);

    assertEquals(once, twice, version + " changed on the second write");
  }

  /**
   * A handler must leave metadata it is not responsible for untouched.
   *
   * <p>Handlers are registered per element and run over the whole metadata of
   * every product of the type. One that reached beyond its own element would
   * corrupt every other field.
   */
  @HegelTest
  void otherElementsAreLeftAlone(TestCase tc) {
    String version = tc.draw(versions(), "version");
    String otherValue = tc.draw(versions(), "otherValue");

    Metadata metadata = new Metadata();
    metadata.replaceMetadata(ELEMENT_NAME, version);
    metadata.replaceMetadata("Filename", otherValue);
    handler().preAddMetadataHandle(metadata);

    assertEquals(otherValue, metadata.getMetadata("Filename"), "an unrelated element was rewritten");
  }

  /**
   * The handler must accept every version in the format it documents.
   *
   * <p>{@code \d{1,2}.\d{0,2}} admits a zero-length minor part — the version
   * {@code "5."}, and the same string is what a caller gets from formatting a
   * whole-numbered version with a trailing separator. Whatever the handler
   * does with it, refusing to handle its own documented input by throwing out
   * of the ingest path is not among the options.
   */
  @HegelTest
  void anEmptyMinorPartIsAccepted(TestCase tc) {
    int major = tc.draw(integers().min(0).max(99), "major");
    String version = major + ".";

    Metadata metadata = new Metadata();
    metadata.replaceMetadata(ELEMENT_NAME, version);

    assertDoesNotThrow(
        () -> handler().preAddMetadataHandle(metadata),
        "the handler rejected '" + version + "', which its own javadoc admits");
  }

  /**
   * A term query on the handled element must be rewritten to the stored form.
   *
   * <p>If it is not, the catalog compares an unpadded query value against
   * padded stored values and matches nothing.
   */
  @HegelTest
  void aTermQueryIsRewrittenToTheCatalogForm(TestCase tc) {
    String version = tc.draw(versions(), "version");

    Metadata metadata = new Metadata();
    metadata.replaceMetadata(ELEMENT_NAME, version);
    handler().preAddMetadataHandle(metadata);
    String stored = metadata.getMetadata(ELEMENT_NAME);

    org.apache.oodt.cas.filemgr.structs.Query query =
        new org.apache.oodt.cas.filemgr.structs.Query();
    query.addCriterion(new TermQueryCriteria(ELEMENT_NAME, version));
    TypeHandler handler = handler();
    assertDoesNotThrow(() -> handler.preQueryHandle(query));

    List<QueryCriteria> criteria = query.getCriteria();
    assertEquals(1, criteria.size());
    assertEquals(
        stored,
        ((TermQueryCriteria) criteria.get(0)).getValue(),
        "the query value was not converted the same way the stored value was");
  }

  /**
   * Both bounds of a range query on the handled element must be rewritten.
   *
   * <p>A bound left in the user's form is compared against padded stored
   * values, so the range silently starts or ends in the wrong place.
   */
  @HegelTest
  void bothRangeBoundsAreRewritten(TestCase tc) throws Exception {
    String low = tc.draw(versions(), "low");
    String high = tc.draw(versions(), "high");
    tc.assume(asNumber(low) <= asNumber(high));

    org.apache.oodt.cas.filemgr.structs.Query query =
        new org.apache.oodt.cas.filemgr.structs.Query();
    query.addCriterion(new RangeQueryCriteria(ELEMENT_NAME, low, high));
    handler().preQueryHandle(query);

    RangeQueryCriteria rewritten = (RangeQueryCriteria) query.getCriteria().get(0);
    tc.note(rewritten.getStartValue() + " TO " + rewritten.getEndValue());
    assertTrue(
        rewritten.getStartValue().compareTo(rewritten.getEndValue()) <= 0,
        "the rewritten range runs backwards: "
            + rewritten.getStartValue()
            + " TO "
            + rewritten.getEndValue());
    assertEquals(5, rewritten.getStartValue().length(), "lower bound was not padded");
    assertEquals(5, rewritten.getEndValue().length(), "upper bound was not padded");
  }
}
