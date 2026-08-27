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

package org.apache.oodt.cas.filemgr.util;

import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.time.Duration;
import java.util.List;
import org.apache.oodt.cas.filemgr.structs.BooleanQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.QueryCriteria;
import org.apache.oodt.cas.filemgr.structs.RangeQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;

/**
 * Round-trip properties for {@link SqlParser}, which turns catalog query text
 * into a criteria tree and back again.
 *
 * <p>The class has no unit tests. It sits on the path a user's query takes to
 * the catalog, so a criteria tree that does not survive being written out and
 * read back is a query that silently returns the wrong products.
 *
 * <p>Element names and values are drawn from a conservative alphabet: no
 * quotes, no parentheses, no whitespace, and nothing that could be mistaken for
 * an operator. Anything these properties catch is therefore a failure on input
 * the parser is unambiguously meant to handle.
 */
class SqlParserPropertyTest {

  private static Generator<String> names() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll");
  }

  private static Generator<String> values() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  /** A single equality term written out and read back must be unchanged. */
  @HegelTest
  void termCriteriaRoundTrips(TestCase tc) throws Exception {
    String name = tc.draw(names(), "name");
    String value = tc.draw(values(), "value");

    TermQueryCriteria original = new TermQueryCriteria(name, value);
    String sql = SqlParser.getInfixCriteriaString(original);
    tc.note("sql = " + sql);

    QueryCriteria parsed = SqlParser.parseSqlWhereClause(sql);

    assertTrue(parsed instanceof TermQueryCriteria, "term came back as " + parsed.getClass());
    TermQueryCriteria back = (TermQueryCriteria) parsed;
    assertEquals(name, back.getElementName());
    assertEquals(value, back.getValue());
  }

  /**
   * A range with both a lower and an upper bound must keep both.
   *
   * <p>Dropping one bound does not fail loudly: it widens the query, and the
   * caller is handed products outside the range they asked for.
   */
  @HegelTest
  void twoSidedRangeKeepsBothBounds(TestCase tc) throws Exception {
    String name = tc.draw(names(), "name");
    String start = tc.draw(values(), "start");
    String end = tc.draw(values(), "end");

    RangeQueryCriteria original = new RangeQueryCriteria(name, start, end);
    String sql = SqlParser.getInfixCriteriaString(original);
    tc.note("sql = " + sql);

    assertTrue(
        sql.contains(start), "lower bound '" + start + "' missing from: " + sql);
    assertTrue(
        sql.contains(end), "upper bound '" + end + "' missing from: " + sql);
  }

  /**
   * AND and OR are order-significant to a reader of the query, so the operands
   * must come back in the order they were written.
   */
  @HegelTest
  void booleanOperandOrderIsPreserved(TestCase tc) throws Exception {
    String leftName = tc.draw(names(), "leftName");
    String rightName = tc.draw(names(), "rightName");
    String value = tc.draw(values(), "value");
    int op = tc.draw(sampledFrom(List.of(0, 1)), "op"); // 0 = AND, 1 = OR

    TermQueryCriteria left = new TermQueryCriteria(leftName, value);
    TermQueryCriteria right = new TermQueryCriteria(rightName, value);
    BooleanQueryCriteria original =
        new BooleanQueryCriteria(List.of((QueryCriteria) left, (QueryCriteria) right), op);

    String sql = SqlParser.getInfixCriteriaString(original);
    tc.note("sql = " + sql);

    QueryCriteria parsed = SqlParser.parseSqlWhereClause(sql);
    assertTrue(parsed instanceof BooleanQueryCriteria, "came back as " + parsed.getClass());

    List<QueryCriteria> terms = ((BooleanQueryCriteria) parsed).getTerms();
    assertEquals(2, terms.size());
    assertEquals(
        leftName,
        ((TermQueryCriteria) terms.get(0)).getElementName(),
        "operands came back reversed: " + sql);
  }

  /**
   * Writing a criteria tree out and reading it back must reach a fixed point.
   * If one round trip changes the tree, the query the catalog runs is not the
   * query that was asked.
   */
  @HegelTest
  void roundTripIsStable(TestCase tc) throws Exception {
    String name = tc.draw(names(), "name");
    String value = tc.draw(values(), "value");

    QueryCriteria original = new TermQueryCriteria(name, value);
    String once = SqlParser.getInfixCriteriaString(original);
    String twice = SqlParser.getInfixCriteriaString(SqlParser.parseSqlWhereClause(once));

    assertEquals(once, twice, "the criteria string changed on the second pass");
  }

  /**
   * Metadata element names that happen to begin with a boolean keyword.
   * {@code ORBIT}, {@code ANDES} and {@code NOTES} are all names a product type
   * could reasonably declare, and upper-case element names are the house style
   * across OODT's own product types.
   */
  private static Generator<String> keywordLikeNames() {
    return sampledFrom(
        List.of(
            "ORBIT", "ORB", "OR", "ANDES", "AND", "NOTES", "NOT", "PRODUCT", "GRANULE"));
  }

  /**
   * A single term must round-trip whatever its element name is spelled like.
   *
   * <p>This is the same contract as {@code termCriteriaRoundTrips} above, but
   * over element names drawn from realistic upper-case metadata keys rather
   * than from arbitrary letters, which is where the boolean-keyword scan in
   * {@code toPostFix} can be reached. The clause has no parentheses, so
   * issue #108 is not in play here.
   *
   * <p>Wrapped in a timeout because the scanner's fallback branch advances
   * {@code i} from an index it computed itself, and a token it cannot make
   * sense of can leave it not advancing at all.
   */
  @HegelTest
  void termRoundTripsForKeywordLikeElementNames(TestCase tc) {
    String name = tc.draw(keywordLikeNames(), "name");
    String value = tc.draw(values(), "value");

    TermQueryCriteria original = new TermQueryCriteria(name, value);
    String sql = SqlParser.getInfixCriteriaString(original);
    tc.note("sql = " + sql);

    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          QueryCriteria parsed = SqlParser.parseSqlWhereClause(sql);
          assertTrue(parsed instanceof TermQueryCriteria, "term came back as " + parsed.getClass());
          TermQueryCriteria back = (TermQueryCriteria) parsed;
          assertEquals(name, back.getElementName(), "element name changed: " + sql);
          assertEquals(value, back.getValue(), "value changed: " + sql);
        });
  }

  /**
   * A metadata value containing an apostrophe must survive the round trip.
   *
   * <p>Apostrophes turn up in real metadata — a producer name, a place name, a
   * free-text title — and nothing in {@code TermQueryCriteria} forbids one.
   * The criteria string is the parser's own output, so if the pair of methods
   * cannot agree on where a value ends, the fault is theirs and not the
   * caller's. No parentheses are involved, so this is independent of #108.
   */
  @HegelTest
  void termRoundTripsWhenTheValueContainsAnApostrophe(TestCase tc) {
    String name = tc.draw(names(), "name");
    String value = tc.draw(text().minSize(1).maxSize(6).categories("Ll").includeCharacters("'"), "value");
    tc.assume(value.contains("'"));

    TermQueryCriteria original = new TermQueryCriteria(name, value);
    String sql = SqlParser.getInfixCriteriaString(original);
    tc.note("sql = " + sql);

    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          QueryCriteria parsed = SqlParser.parseSqlWhereClause(sql);
          assertTrue(parsed instanceof TermQueryCriteria, "term came back as " + parsed.getClass());
          assertEquals(value, ((TermQueryCriteria) parsed).getValue(), "value changed: " + sql);
        });
  }
}
