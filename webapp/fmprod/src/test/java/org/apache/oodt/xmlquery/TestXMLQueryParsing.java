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

import java.util.List;

import junit.framework.TestCase;

/**
 * Two ways this parser returned something other than what was asked for: a
 * dropped negation, and quoted literals altered by escape processing.
 */
public class TestXMLQueryParsing extends TestCase {

    private static XMLQuery query(String keywords) {
        return new XMLQuery(keywords, "id", "title", "description",
                "dataDictID", "resultModeID", "propType", "propLevels", 45);
    }

    private static boolean whereContains(XMLQuery q, String role, String value) {
        List where = q.getWhereElementSet();
        for (Object o : where) {
            QueryElement e = (QueryElement) o;
            if (role.equals(e.getRole()) && value.equals(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static String renderWhere(XMLQuery q) {
        StringBuilder sb = new StringBuilder();
        for (Object o : q.getWhereElementSet()) {
            QueryElement e = (QueryElement) o;
            sb.append('[').append(e.getRole()).append('=').append(e.getValue()).append(']');
        }
        return sb.toString();
    }

    /**
     * #152: NOT is unary, but it went through the same guard as AND and OR,
     * which waits for a second operand. With one comparison in the clause the
     * negation was dropped and the query meant the opposite.
     */
    public void testNotSurvivesASingleComparison() {
        XMLQuery q = query("NOT a EQ a");
        assertTrue("the NOT was dropped: " + renderWhere(q),
                whereContains(q, "LOGOP", "NOT"));
    }

    public void testNotStillWorksWithTwoComparisons() {
        XMLQuery q = query("NOT a EQ a AND b EQ b");
        assertTrue("the NOT was dropped: " + renderWhere(q),
                whereContains(q, "LOGOP", "NOT"));
    }

    /** AND and OR are binary and must keep needing two operands. */
    public void testBinaryOperatorsAreUnchanged() {
        assertTrue(whereContains(query("a EQ a AND b EQ b"), "LOGOP", "AND"));
        assertTrue(whereContains(query("a EQ a OR b EQ b"), "LOGOP", "OR"));
    }

    /**
     * #153: quoting is the only way to protect a literal, and escape
     * processing inside the quotes corrupted exactly the values it exists for.
     */
    public void testWindowsPathSurvivesQuoting() {
        XMLQuery q = query("a EQ 'C:\\temp'");
        assertTrue("the tab escape ate the path: " + renderWhere(q),
                whereContains(q, "LITERAL", "C:\\temp"));
    }

    public void testBackslashSequenceSurvivesQuoting() {
        XMLQuery q = query("a EQ 'a\\a'");
        assertTrue("\\a became BEL: " + renderWhere(q),
                whereContains(q, "LITERAL", "a\\a"));
    }

    public void testRegexLikeLiteralSurvivesQuoting() {
        XMLQuery q = query("a EQ '\\d+\\s*'");
        assertTrue("the pattern was altered: " + renderWhere(q),
                whereContains(q, "LITERAL", "\\d+\\s*"));
    }

    /** An ordinary quoted literal must still come through untouched. */
    public void testPlainQuotedLiteralIsUnchanged() {
        assertTrue(whereContains(query("a EQ 'hello world'"), "LITERAL", "hello world"));
    }

    /** A deliberately escaped quote stays an escaped quote. */
    public void testEscapedQuoteStillCloses() {
        XMLQuery q = query("a EQ 'it\\'s'");
        assertTrue("the escaped quote was mishandled: " + renderWhere(q),
                whereContains(q, "LITERAL", "it's"));
    }
}
