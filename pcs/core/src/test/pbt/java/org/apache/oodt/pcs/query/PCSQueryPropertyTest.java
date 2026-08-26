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

package org.apache.oodt.pcs.query;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.QueryCriteria;
import org.apache.oodt.cas.filemgr.structs.RangeQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;

/**
 * Properties of the query builders in {@code org.apache.oodt.pcs.query}.
 *
 * <p>These classes exist so that callers do not have to remember which
 * metadata field backs which lookup. Each one has to translate its single
 * argument into exactly one criterion naming the right field, or the File
 * Manager quietly answers a different question from the one asked. None of
 * them touches the File Manager handle while building, so a null handle is
 * enough to drive them.
 */
class PCSQueryPropertyTest {

  /** A metadata value: product names, job ids and filenames all look like this. */
  private static final Generator<String> VALUE =
      text().minSize(0).maxSize(24).categories("Lu", "Ll", "Nd");

  private static TermQueryCriteria soleTerm(Query query) {
    List<QueryCriteria> criteria = query.getCriteria();
    assertEquals(1, criteria.size(), "expected exactly one criterion");
    assertTrue(criteria.get(0) instanceof TermQueryCriteria,
        "expected a term criterion, got " + criteria.get(0).getClass().getSimpleName());
    return (TermQueryCriteria) criteria.get(0);
  }

  /** A filename lookup asks the File Manager about {@code Filename}. */
  @HegelTest
  void filenameQueryAsksAboutTheFilenameField(TestCase tc) {
    String fileName = tc.draw(VALUE, "fileName");

    TermQueryCriteria crit = soleTerm(new FilenameQuery(fileName, null).buildQuery());

    assertEquals("Filename", crit.getElementName());
    assertEquals(fileName, crit.getValue());
  }

  /** A product name lookup asks about {@code ProductName}. */
  @HegelTest
  void productNameQueryAsksAboutTheProductNameField(TestCase tc) {
    String prodName = tc.draw(VALUE, "prodName");

    TermQueryCriteria crit = soleTerm(new ProductNameQuery(prodName, null).buildQuery());

    assertEquals("ProductName", crit.getElementName());
    assertEquals(prodName, crit.getValue());
  }

  /** A job lookup asks about {@code JobId}. */
  @HegelTest
  void jobIdQueryAsksAboutTheJobIdField(TestCase tc) {
    String jobId = tc.draw(VALUE, "jobId");

    TermQueryCriteria crit = soleTerm(new JobIdQuery(jobId, null).buildQuery());

    assertEquals("JobId", crit.getElementName());
    assertEquals(jobId, crit.getValue());
  }

  /**
   * An upstream pedigree step asks about {@code InputFiles}, and a downstream
   * step about {@code OutputFiles}. Swapping the two would silently reverse
   * the direction of every trace.
   */
  @HegelTest
  void pedigreeQueriesAskAboutOppositeEndsOfTheProcessing(TestCase tc) {
    String file = tc.draw(VALUE, "file");

    TermQueryCriteria in = soleTerm(new InputFilesQuery(file, null).buildQuery());
    TermQueryCriteria out = soleTerm(new OutputFilesQuery(file, null).buildQuery());

    assertEquals("InputFiles", in.getElementName());
    assertEquals(file, in.getValue());
    assertEquals("OutputFiles", out.getElementName());
    assertEquals(file, out.getValue());
  }

  /**
   * A temporal query is an inclusive range on the caller's chosen date field,
   * and a bound the caller did not supply is left unset rather than being
   * filled in with something. An unset bound is how the File Manager is told
   * "open ended", so inventing one would drop matching products.
   */
  @HegelTest
  void temporalQueryIsAnInclusiveRangeOverTheGivenField(TestCase tc) {
    String field = tc.draw(VALUE.filter(s -> !s.isEmpty()), "field");
    boolean hasStart = tc.draw(booleans(), "hasStart");
    boolean hasEnd = tc.draw(booleans(), "hasEnd");
    String start = hasStart ? tc.draw(VALUE, "start") : null;
    String end = hasEnd ? tc.draw(VALUE, "end") : null;

    Query query = new TemporalQuery(null, start, end, field).buildQuery();

    List<QueryCriteria> criteria = query.getCriteria();
    assertEquals(1, criteria.size(), "expected exactly one criterion");
    assertTrue(criteria.get(0) instanceof RangeQueryCriteria,
        "a temporal query must be a range criterion");
    RangeQueryCriteria crit = (RangeQueryCriteria) criteria.get(0);

    assertEquals(field, crit.getElementName());
    assertTrue(crit.getInclusive(), "a temporal range must include its endpoints");

    if (hasStart) {
      assertEquals(start, crit.getStartValue());
    } else {
      assertNull(crit.getStartValue(), "an unsupplied start bound was filled in");
    }
    if (hasEnd) {
      assertEquals(end, crit.getEndValue());
    } else {
      assertNull(crit.getEndValue(), "an unsupplied end bound was filled in");
    }
  }
}
