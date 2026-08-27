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

package org.apache.oodt.xmlquery;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Grammar properties of the DIS keyword query parser inside {@link XMLQuery}.
 *
 * <p>A query string is the only thing a caller of a product server writes by
 * hand. Everything downstream — the {@code queryWhereSet} a catalogue turns
 * into SQL or a Lucene query, the {@code querySelectSet} that decides which
 * fields come back — is derived from what this parser makes of that string. A
 * term the parser drops is a filter the user asked for and did not get, and no
 * error is raised when it happens: the parse result is a list, and a shorter
 * list is still a valid query.
 *
 * <p>The parser records the parse in postfix (reverse Polish) order: the
 * operands first, then the operators that combine them. The properties below
 * state that order against an independent shunting-yard reference rather than
 * against a transcription of what the parser happens to do, so that a change in
 * precedence would be caught rather than blessed.
 *
 * <p>Element names and literals are drawn from an alphabet the tokeniser treats
 * as word characters, so nothing here is a quarrel about lexing; the one
 * property that deliberately steps outside that alphabet quotes its literal,
 * which is exactly how the query language says to write such a value.
 */
class XMLQueryParserPropertyTest {

  /** The keyword that diverts a term into the select set rather than the where set. */
  private static final String RETURN = "RETURN";

  /**
   * Operators written as words. The parser stores the operator exactly as it
   * was written, so these are both the input and the expected output.
   */
  private static final List<String> WORD_OPERATORS =
      Arrays.asList("EQ", "LT", "LE", "GT", "GE", "NE", "LIKE", "NOTLIKE", "IS", "ISNOT");

  /**
   * Operators written as symbols, paired with the word the parser normalises
   * them to. A caller may write either; the two spellings must mean the same
   * thing.
   */
  private static final List<String[]> SYMBOLIC_OPERATORS =
      Arrays.asList(
          new String[] {"=", "EQ"},
          new String[] {"<", "LT"},
          new String[] {"<=", "LE"},
          new String[] {">", "GT"},
          new String[] {">=", "GE"},
          new String[] {"!=", "NE"});

  /**
   * The characters the tokeniser is configured to read as part of a word.
   * Drawing from these and no others keeps every property below a statement
   * about the grammar rather than about lexing: a term that goes missing did
   * not go missing because the tokeniser could not read it.
   */
  private static final List<String> WORD_CHARACTERS =
      Arrays.asList(
          "a", "b", "c", "z", "A", "B", "M", "Z", "0", "1", "7", "9", "_", "-", ".", ":", "#");

  private static Generator<String> wordsOf(List<String> alphabet, int maxSize) {
    return lists(sampledFrom(alphabet))
        .minSize(1)
        .maxSize(maxSize)
        .map(parts -> String.join("", parts));
  }

  /** An identifier out of the characters the tokeniser reads as one word. */
  private static Generator<String> identifiers() {
    return wordsOf(WORD_CHARACTERS, 8).filter(s -> !RETURN.equals(s));
  }

  /** An unquoted literal: the same alphabet, which the tokeniser keeps whole. */
  private static Generator<String> literals() {
    return wordsOf(WORD_CHARACTERS, 8);
  }

  private static Generator<String> wordOperators() {
    return sampledFrom(WORD_OPERATORS);
  }

  /** Query text drawn from everything a user might plausibly type, and a lot they would not. */
  private static Generator<String> arbitraryQueryText() {
    return lists(
            sampledFrom(
                Arrays.asList(
                    "A", "b", "RETURN", "AND", "OR", "NOT", "and", "or", "not", "EQ", "LIKE",
                    "(", ")", "=", "<", ">", "<=", ">=", "!=", "&", "|", "!", "'", "\"", " ",
                    "%", ",", "*", "\\", "/", "3", "-", "[", "]", "#", ":", "\t")))
        .minSize(0)
        .maxSize(14)
        .map(parts -> String.join("", parts));
  }

  private static XMLQuery parse(String keywordQuery) {
    return new XMLQuery(
        keywordQuery, "id", "title", "description", "dd", null, null, null,
        XMLQuery.DEFAULT_MAX_RESULTS);
  }

  /** One written comparison, as the caller wrote it. */
  private static final class Comparison {
    final String name;
    final String operator;
    final String literal;

    Comparison(String name, String operator, String literal) {
      this.name = name;
      this.operator = operator;
      this.literal = literal;
    }

    String written() {
      return name + " " + operator + " " + literal;
    }
  }

  private static Comparison drawComparison(TestCase tc, String label) {
    return new Comparison(
        tc.draw(identifiers(), label + ".name"),
        tc.draw(wordOperators(), label + ".operator"),
        tc.draw(literals(), label + ".literal"));
  }

  /** The role/value pairs a parsed query holds, as plain strings for readable failures. */
  private static List<String> rolesAndValues(List<?> elements) {
    List<String> rc = new ArrayList<String>();
    for (Object element : elements) {
      QueryElement qe = (QueryElement) element;
      rc.add(qe.getRole() + "=" + qe.getValue());
    }
    return rc;
  }

  /** What one comparison is expected to contribute to the where set. */
  private static List<String> expectedTriple(Comparison comparison) {
    return Arrays.asList(
        "elemName=" + comparison.name,
        "LITERAL=" + comparison.literal,
        "RELOP=" + comparison.operator);
  }

  /**
   * Postfix order for a flat chain of comparisons joined by AND and OR, with
   * AND binding tighter and both associating to the left. This is an ordinary
   * shunting yard, written out so that the expected order comes from the
   * grammar rather than from the parser under test.
   */
  private static List<String> expectedPostfix(List<Comparison> terms, List<String> operators) {
    List<String> output = new ArrayList<String>();
    List<String> stack = new ArrayList<String>();

    output.addAll(expectedTriple(terms.get(0)));
    for (int i = 0; i < operators.size(); ++i) {
      String operator = operators.get(i);
      while (!stack.isEmpty() && precedence(stack.get(stack.size() - 1)) >= precedence(operator)) {
        output.add("LOGOP=" + stack.remove(stack.size() - 1));
      }
      stack.add(operator);
      output.addAll(expectedTriple(terms.get(i + 1)));
    }
    while (!stack.isEmpty()) {
      output.add("LOGOP=" + stack.remove(stack.size() - 1));
    }
    return output;
  }

  private static int precedence(String operator) {
    return "AND".equals(operator) ? 2 : 1;
  }

  /**
   * The parser answers for any string at all. A product server hands it
   * whatever arrived over the wire, so a string it cannot make sense of has to
   * come back as a query marked in error, not as an exception thrown out of a
   * constructor and not as a parse that never returns.
   *
   * <p>The text is short by construction. Nesting is expressed by recursive
   * descent, so an arbitrarily deep run of open parentheses would exhaust the
   * stack; that is a depth no query written by a person reaches, and it is not
   * what this property is about.
   */
  @HegelTest(testCases = 3000)
  void anyStringIsEitherParsedOrMarkedInError(TestCase tc) {
    String queryText = tc.draw(arbitraryQueryText(), "queryText");
    tc.note("query = [" + queryText + "]");

    XMLQuery query = parse(queryText);

    assertEquals(queryText, query.getKwdQueryString(), "the query text was not kept as written");
    String status = query.getQueryHeader().getStatusID();
    assertTrue(
        "ACTIVE".equals(status) || "ERROR".equals(status),
        "a parse ended in the unnamed status [" + status + "]");
  }

  /**
   * Every comparison a caller writes reaches the where clause, in the order
   * written, as the name/literal/operator triple the catalogue reads. This is
   * the whole of what the parser is for: a triple that goes missing is a filter
   * the user asked for and silently did not get.
   *
   * <p>The logical operators are set aside here — they are interleaved with the
   * comparisons rather than following them, and where each one lands is the
   * subject of the next property. This one asks only whether the comparisons
   * themselves all arrived, and in order.
   */
  @HegelTest
  void everyComparisonWrittenReachesTheWhereClause(TestCase tc) {
    int count = tc.draw(sampledFrom(Arrays.asList(1, 2, 3, 4)), "termCount");
    List<Comparison> terms = new ArrayList<Comparison>();
    for (int i = 0; i < count; ++i) {
      terms.add(drawComparison(tc, "term" + i));
    }
    List<String> joins = new ArrayList<String>();
    for (int i = 0; i < count - 1; ++i) {
      joins.add(tc.draw(sampledFrom(Arrays.asList("AND", "OR")), "join" + i));
    }

    StringBuilder written = new StringBuilder(terms.get(0).written());
    for (int i = 0; i < joins.size(); ++i) {
      written.append(' ').append(joins.get(i)).append(' ').append(terms.get(i + 1).written());
    }
    tc.note("query = [" + written + "]");

    List<String> parsed = new ArrayList<String>();
    for (String element : rolesAndValues(parse(written.toString()).getWhereElementSet())) {
      if (!element.startsWith("LOGOP=")) {
        parsed.add(element);
      }
    }

    List<String> expectedTriples = new ArrayList<String>();
    for (Comparison term : terms) {
      expectedTriples.addAll(expectedTriple(term));
    }
    assertEquals(
        expectedTriples,
        parsed,
        "the comparisons did not come back in the order they were written");
  }

  /**
   * The logical operators come back in the order the grammar implies: postfix,
   * with AND binding tighter than OR and both associating to the left. A
   * catalogue evaluating the where set as a stack machine gets a different set
   * of products if this order is wrong, and it has no way to tell.
   */
  @HegelTest
  void logicalOperatorsFollowTheWrittenPrecedence(TestCase tc) {
    int count = tc.draw(sampledFrom(Arrays.asList(2, 3, 4)), "termCount");
    List<Comparison> terms = new ArrayList<Comparison>();
    for (int i = 0; i < count; ++i) {
      terms.add(drawComparison(tc, "term" + i));
    }
    List<String> joins = new ArrayList<String>();
    for (int i = 0; i < count - 1; ++i) {
      joins.add(tc.draw(sampledFrom(Arrays.asList("AND", "OR")), "join" + i));
    }

    StringBuilder written = new StringBuilder(terms.get(0).written());
    for (int i = 0; i < joins.size(); ++i) {
      written.append(' ').append(joins.get(i)).append(' ').append(terms.get(i + 1).written());
    }
    tc.note("query = [" + written + "]");

    assertEquals(
        expectedPostfix(terms, joins),
        rolesAndValues(parse(written.toString()).getWhereElementSet()),
        "the parsed where clause is not the postfix form of what was written");
  }

  /**
   * The two spellings of a comparison operator mean the same thing. A caller
   * who writes {@code A &gt;= 3} and one who writes {@code A GE 3} have written
   * the same query and must get the same where clause.
   */
  @HegelTest
  void symbolicAndWordOperatorsAgree(TestCase tc) {
    String name = tc.draw(identifiers(), "name");
    String literal = tc.draw(literals(), "literal");
    String[] pair = tc.draw(sampledFrom(SYMBOLIC_OPERATORS), "operator");

    List<String> symbolic =
        rolesAndValues(parse(name + " " + pair[0] + " " + literal).getWhereElementSet());
    List<String> word =
        rolesAndValues(parse(name + " " + pair[1] + " " + literal).getWhereElementSet());

    assertEquals(
        word, symbolic, "[" + pair[0] + "] and [" + pair[1] + "] parsed to different queries");
  }

  /**
   * A RETURN clause names a field to bring back, and lands in the select set
   * rather than the where set. A field named there and lost is a column missing
   * from the answer.
   */
  @HegelTest
  void aReturnClauseSelectsTheNamedField(TestCase tc) {
    Comparison filter = drawComparison(tc, "filter");
    String field = tc.draw(identifiers(), "field");

    String written = filter.written() + " AND " + RETURN + " EQ " + field;
    tc.note("query = [" + written + "]");
    XMLQuery query = parse(written);

    assertEquals(
        Collections.singletonList("elemName=" + field),
        rolesAndValues(query.getSelectElementSet()),
        "the RETURN clause did not select the named field");
    assertEquals(
        expectedTriple(filter),
        rolesAndValues(query.getWhereElementSet()),
        "the RETURN clause leaked into the where clause");
  }

  /**
   * Negation survives being written. {@code NOT A EQ 1} and {@code A EQ 1} ask
   * for opposite sets of products, so the parsed query has to be able to tell
   * them apart — otherwise a user who excludes something is quietly given
   * exactly the thing they excluded.
   */
  @HegelTest
  void aNegatedComparisonRecordsItsNegation(TestCase tc) {
    Comparison term = drawComparison(tc, "term");

    List<String> negated = rolesAndValues(parse("NOT " + term.written()).getWhereElementSet());
    tc.note("parsed = " + negated);

    assertTrue(
        negated.contains("LOGOP=NOT"),
        "the negation in [NOT " + term.written() + "] is not in the parsed query: " + negated);
  }

  /**
   * A quoted literal reaches the query exactly as it was quoted. Quoting is the
   * query language's own escape hatch — it is how a caller says "this value
   * contains characters that are not identifier characters" — so a value that
   * changes on the way through is a filter matching something the user did not
   * write. File paths and patterns are the ordinary case: they are the reason
   * quoting exists.
   */
  @HegelTest
  void aQuotedLiteralReachesTheQueryUnchanged(TestCase tc) {
    String name = tc.draw(identifiers(), "name");
    String before = tc.draw(literals(), "before");
    String awkward =
        tc.draw(
            sampledFrom(Arrays.asList("\\", "/", "%", "*", " ", "(", ")", "&", "<", "-", "?")),
            "awkward");
    String after = tc.draw(literals(), "after");
    String literal = before + awkward + after;

    String written = name + " EQ '" + literal + "'";
    tc.note("query = [" + written + "]");

    assertEquals(
        expectedTriple(new Comparison(name, "EQ", literal)),
        rolesAndValues(parse(written).getWhereElementSet()),
        "the quoted literal [" + literal + "] did not survive the parse");
  }

  /**
   * Parentheses group without changing what is inside them. A caller who
   * brackets a single comparison has written the same query, and the parse must
   * agree.
   */
  @HegelTest
  void bracketingASingleComparisonChangesNothing(TestCase tc) {
    Comparison term = drawComparison(tc, "term");

    assertEquals(
        rolesAndValues(parse(term.written()).getWhereElementSet()),
        rolesAndValues(parse("( " + term.written() + " )").getWhereElementSet()),
        "bracketing changed the parse of [" + term.written() + "]");
  }
}
