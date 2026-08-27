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

import java.util.ArrayList;
import java.util.List;

import org.apache.oodt.cas.filemgr.structs.BooleanQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.QueryCriteria;
import org.apache.oodt.cas.filemgr.structs.RangeQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SqlParser had no tests. These cover the four defects reported against it,
 * and the round trip that ties the two halves of the class together: whatever
 * getInfixCriteriaString writes, parseSqlWhereClause has to be able to read.
 */
@RunWith(JUnit4.class)
public class TestSqlParser {

    /** #108: the writer parenthesises every boolean, and the reader choked on it. */
    @Test
    public void testBooleanQuerySurvivesARoundTrip() throws Exception {
        List<QueryCriteria> terms = new ArrayList<QueryCriteria>();
        terms.add(new TermQueryCriteria("A", "x"));
        terms.add(new TermQueryCriteria("B", "y"));
        BooleanQueryCriteria and = new BooleanQueryCriteria(terms, BooleanQueryCriteria.AND);

        String sql = SqlParser.getInfixCriteriaString(and);
        QueryCriteria parsed = SqlParser.parseSqlWhereClause(sql);

        assertNotNull("the parser could not read what the writer produced: " + sql, parsed);
        assertTrue(parsed instanceof BooleanQueryCriteria);
    }

    /** #108: a redundantly parenthesised clause is legal input a user can type. */
    @Test
    public void testRedundantParenthesesAreAccepted() throws Exception {
        QueryCriteria parsed = SqlParser.parseSqlWhereClause("(A == 'x')");
        assertNotNull(parsed);
        assertTrue(parsed instanceof TermQueryCriteria);
        assertEquals("A", ((TermQueryCriteria) parsed).getElementName());
        assertEquals("x", ((TermQueryCriteria) parsed).getValue());
    }

    @Test
    public void testNestedParenthesesAreAccepted() throws Exception {
        assertNotNull(SqlParser.parseSqlWhereClause("((A == 'x') AND (B == 'y'))"));
    }

    /** #109: both bounds have to survive being written out. */
    @Test
    public void testTwoSidedRangeKeepsBothBounds() throws Exception {
        RangeQueryCriteria range = new RangeQueryCriteria("A", "0", "1", true);
        String sql = SqlParser.getInfixCriteriaString(range);

        assertTrue("the upper bound was dropped: " + sql, sql.contains("1"));
        assertTrue("the lower bound was dropped: " + sql, sql.contains("0"));
    }

    /** and the written form has to mean the same thing when read back. */
    @Test
    public void testTwoSidedRangeRoundTripsToBothBounds() throws Exception {
        RangeQueryCriteria range = new RangeQueryCriteria("A", "0", "1", true);
        QueryCriteria parsed = SqlParser.parseSqlWhereClause(
                SqlParser.getInfixCriteriaString(range));

        assertNotNull(parsed);
        String rendered = SqlParser.getInfixCriteriaString(parsed);
        assertTrue("lower bound lost on the round trip: " + rendered, rendered.contains("0"));
        assertTrue("upper bound lost on the round trip: " + rendered, rendered.contains("1"));
    }

    /** a one-sided range must keep behaving as it did. */
    @Test
    public void testOneSidedRangeIsUnchanged() throws Exception {
        assertEquals("A >= '0'",
                SqlParser.getInfixCriteriaString(new RangeQueryCriteria("A", "0", null, true)));
        assertEquals("A <= '1'",
                SqlParser.getInfixCriteriaString(new RangeQueryCriteria("A", null, "1", true)));
    }

    /**
     * #110: an unquoted value spun allocating until the heap was gone. The
     * timeout is the assertion; a wrong answer here is a hung build.
     */
    @Test(timeout = 10000)
    public void testUnquotedValueFailsInsteadOfSpinning() {
        try {
            SqlParser.parseSqlWhereClause("FileSize > 1000");
            // accepting it is fine, as long as it terminates
        } catch (Exception expected) {
            // failing is fine too, as long as it terminates
        }
    }

    @Test(timeout = 10000)
    public void testUnterminatedQuoteFailsInsteadOfSpinning() {
        try {
            SqlParser.parseSqlWhereClause("Filename == 'unclosed");
        } catch (Exception expected) {
        }
    }

    /** #111: an element name that merely starts with an operator is not that operator. */
    @Test
    public void testElementNameBeginningWithNotIsNotAnOperator() throws Exception {
        QueryCriteria parsed = SqlParser.parseSqlWhereClause("NOTES == 'x'");
        assertTrue("NOTES was read as a NOT operator: " + parsed,
                parsed instanceof TermQueryCriteria);
        assertEquals("NOTES", ((TermQueryCriteria) parsed).getElementName());
    }

    @Test
    public void testElementNameBeginningWithOrIsNotAnOperator() throws Exception {
        QueryCriteria parsed = SqlParser.parseSqlWhereClause("ORBIT == '0'");
        assertTrue(parsed instanceof TermQueryCriteria);
        assertEquals("ORBIT", ((TermQueryCriteria) parsed).getElementName());
    }

    @Test
    public void testElementNameBeginningWithAndIsNotAnOperator() throws Exception {
        QueryCriteria parsed = SqlParser.parseSqlWhereClause("ANDES == 'x'");
        assertTrue(parsed instanceof TermQueryCriteria);
        assertEquals("ANDES", ((TermQueryCriteria) parsed).getElementName());
    }

    /** the real operators must still work as operators. */
    @Test
    public void testRealOperatorsStillParse() throws Exception {
        assertTrue(SqlParser.parseSqlWhereClause("A == 'x' AND B == 'y'")
                instanceof BooleanQueryCriteria);
        assertTrue(SqlParser.parseSqlWhereClause("A == 'x' OR B == 'y'")
                instanceof BooleanQueryCriteria);
        assertTrue(SqlParser.parseSqlWhereClause("NOT(A == 'x')")
                instanceof BooleanQueryCriteria);
    }

    @Test
    public void testPlainTermStillParses() throws Exception {
        QueryCriteria parsed = SqlParser.parseSqlWhereClause("Filename == 'x'");
        assertTrue(parsed instanceof TermQueryCriteria);
        assertEquals("Filename", ((TermQueryCriteria) parsed).getElementName());
    }
}
