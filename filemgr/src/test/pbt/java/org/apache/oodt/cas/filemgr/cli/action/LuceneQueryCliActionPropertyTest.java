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

package org.apache.oodt.cas.filemgr.cli.action;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;
import org.apache.oodt.cas.filemgr.structs.exceptions.QueryFormulationException;
import org.apache.oodt.cas.filemgr.structs.query.ComplexQuery;
import org.apache.lucene.queryparser.classic.ParseException;

/**
 * Properties of the Lucene-to-CAS query translation in
 * {@link LuceneQueryCliAction}, reached through its public {@code getQuery()}.
 *
 * <p>The query text here comes straight off the command line, so every string
 * Lucene's own {@code QueryParser} accepts is a string a user can type. The
 * translation is allowed to refuse a query — {@link CatalogException} exists
 * for exactly that, and the CLI reports it — but a refusal has to be a thrown
 * {@code CatalogException}, not an unchecked crash out of the middle of the
 * translation.
 */
class LuceneQueryCliActionPropertyTest {

  /** Element names the Lucene parser reads as a field, with no escaping needed. */
  private static Generator<String> fieldNames() {
    return sampledFrom(List.of("ProductName", "DataVersion", "ProductionDateTime"));
  }

  private static Generator<String> terms() {
    return text().minSize(1).maxSize(6).categories("Ll", "Nd");
  }

  /**
   * A range with one end left open. Lucene spells this {@code field:[* TO b]}
   * and it is the ordinary way to ask for "anything up to b" — a half-open
   * range is the most common thing a user wants from a date field.
   *
   * <p>The translation must either produce criteria for it or say it cannot.
   */
  @HegelTest
  void openEndedRangesAreTranslatedOrRefused(TestCase tc) {
    String field = tc.draw(fieldNames(), "field");
    boolean openLower = tc.draw(booleans(), "openLower");
    boolean openUpper = tc.draw(booleans(), "openUpper");
    String lower = openLower ? "*" : tc.draw(terms(), "lower");
    String upper = openUpper ? "*" : tc.draw(terms(), "upper");
    boolean inclusive = tc.draw(booleans(), "inclusive");

    String queryString =
        field
            + ":"
            + (inclusive ? "[" : "{")
            + lower
            + " TO "
            + upper
            + (inclusive ? "]" : "}");
    tc.note("query = " + queryString);

    LuceneQueryCliAction action = new LuceneQueryCliAction();
    action.setQuery(queryString);

    try {
      ComplexQuery query = action.getQuery();
      assertNotNull(query.getCriteria(), "no criteria for " + queryString);
    } catch (ParseException | CatalogException | QueryFormulationException e) {
      // A refusal the CLI knows how to report is an acceptable outcome.
    } catch (RuntimeException e) {
      fail(
          "translating ["
              + queryString
              + "] threw "
              + e.getClass().getName()
              + ": "
              + e.getMessage());
    }
  }
}
