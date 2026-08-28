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

//OODT imports

import org.apache.oodt.cas.filemgr.structs.BooleanQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.QueryCriteria;
import org.apache.oodt.cas.filemgr.structs.RangeQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.exceptions.QueryFormulationException;
import org.apache.oodt.cas.filemgr.structs.query.ComplexQuery;
import org.apache.oodt.cas.filemgr.structs.query.QueryFilter;
import org.apache.oodt.cas.filemgr.structs.query.filter.FilterAlgor;

import java.util.LinkedList;
import java.util.List;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//JDK imports

/**
 * 
 * @author bfoster
 * @version $Revision$
 * 
 *          <p>
 *          A fairly robust SQL parser, based on the Shunting yard
 *          algorithm
 *          </p>
 *          
 *          <p>
 *          Evaluates SQL like string statements contained in a string. The SQL
 *          statement should be enclosed within: SQL ({@literal <sql_arguments>}
 *          ) { {@literal <sql_statement>} . the {@literal <sql_arguments>} can
 *          be either FORMAT, SORT_BY, or FILTER. Syntax: SQL (FORMAT='<metadata
 *          formated output>',SORT_BY='<metadata name>', FILTER='<start_time
 *          metadata element>, <end_time metadata element>, <priority metadata
 *          element>, <filter type>') { SELECT
 *          <list-of-comma-segregated-metadata-elements-to-query-on> FROM
 *          <productTypes-comma-segregated> WHERE <metadata-boolean-expressions>
 *          } Here is an example SQL statement: SQL (FORMAT='FileLocation/Filename',
 *          SORT_BY='FileSize',FILTER=<StartDateTime,EndDateTime,DataVersion,
 *          TakeHighestPriority) { SELECT FileLocation,Filename,FileSize FROM IASI_L1C 
 *          WHERE ProductionDateTime >= '2007-12-01T00:00:00.000000Z' } This example
 *          would query the cas-filemgr for metadata values:
 *          FileLocation,Filename,FileSize for any data file where the
 *          ProductType == IASI_L1C and the ProductionDateTime >=
 *          2007-12-01T00:00:00.000000Z. It would then combine the return data
 *          files metadata via the specified FORMAT. Each data file's metadata
 *          will be formated to a string representation of (with the actual
 *          values replaced in the location of the metadata keys):
 *          FileLocation/Filename. They will be concatenated together, in
 *          FileSize order.
 *          </p>
 */
public class SqlParser {

    private static Logger LOG = Logger.getLogger(SqlParser.class.getName());
    private SqlParser() {
    }
    
    public static final String EXAMPLE_QUERY = "SELECT Filename FROM EmploymentJob";

    public static ComplexQuery parseSqlQueryMethod(String sqlStringQueryMethod)
            throws QueryFormulationException {
        if (!Pattern.compile(
                "(?i)SQL\\s*(.*)\\s*\\{\\s*SELECT\\b.*\\bFROM\\b.*(?:\\bWHERE\\b.*){0,1}\\}")
                .matcher(sqlStringQueryMethod).matches()) {
            throw new QueryFormulationException("Malformed SQL method");
        }
        
        try {
            ComplexQuery complexQuery = parseSqlQuery(stripOutSqlDefinition(sqlStringQueryMethod));
            
            for (Expression expr : getSqlStatementArgs(sqlStringQueryMethod)) {
                if (expr.getKey().toUpperCase().equals("FORMAT")) {
                    complexQuery.setToStringResultFormat(expr.getValue());
                } else if (expr.getKey().toUpperCase().equals("SORT_BY")) {
                    complexQuery.setSortByMetKey(expr.getValue());
                } else if (expr.getKey().toUpperCase().equals("FILTER")) {
                    complexQuery.setQueryFilter(createFilter(expr));
                }
            }
            
            return complexQuery;
        }catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            throw new QueryFormulationException("Failed to parse SQL method : " + e.getMessage());
        }
    }
    
    public static ComplexQuery parseSqlQuery(String sqlStringQuery)
            throws QueryFormulationException {
        if (sqlStringQuery == null || sqlStringQuery.trim().isEmpty()) {
            throw new QueryFormulationException(
                    "Enter a SQL query, for example: " + EXAMPLE_QUERY);
        }
        String sql = sqlStringQuery.trim();
        int selectAt = indexOfClauseKeyword(sql, "SELECT", 0);
        if (selectAt < 0 || !sql.substring(0, selectAt).trim().isEmpty()) {
            throw new QueryFormulationException(
                    "Query must start with SELECT, for example: " + EXAMPLE_QUERY);
        }
        int selectEnd = selectAt + "SELECT".length();
        int fromAt = indexOfClauseKeyword(sql, "FROM", selectEnd);
        if (fromAt < 0) {
            throw new QueryFormulationException(
                    "Query needs a FROM clause, for example: " + EXAMPLE_QUERY);
        }
        int fromEnd = fromAt + "FROM".length();
        int whereAt = indexOfClauseKeyword(sql, "WHERE", fromEnd);

        String selectList = sql.substring(selectEnd, fromAt).trim();
        String fromList = sql.substring(fromEnd,
                whereAt >= 0 ? whereAt : sql.length()).trim();
        if (selectList.isEmpty()) {
            throw new QueryFormulationException(
                    "SELECT list is empty, for example: " + EXAMPLE_QUERY);
        }
        if (fromList.isEmpty()) {
            throw new QueryFormulationException(
                    "FROM clause is empty, for example: " + EXAMPLE_QUERY);
        }

        ComplexQuery sq = new ComplexQuery();
        List<String> selectValues = csvValues(selectList);
        if (!selectValues.contains("*")) {
            sq.setReducedMetadata(selectValues);
        }
        List<String> fromValues = csvValues(fromList);
        if (!fromValues.contains("*")) {
            sq.setReducedProductTypeNames(fromValues);
        }
        if (whereAt >= 0) {
            String whereList = sql.substring(whereAt + "WHERE".length()).trim();
            if (whereList.isEmpty()) {
                throw new QueryFormulationException(
                        "WHERE clause is empty, for example: " + EXAMPLE_QUERY
                                + " WHERE Filename == 'job-1001.json'");
            }
            sq.addCriterion(parseStatement(toPostFix(whereList)));
        }
        return sq;
    }
    
    public static QueryCriteria parseSqlWhereClause(String sqlWhereClause) 
            throws QueryFormulationException {
        return parseStatement(toPostFix(sqlWhereClause.trim()));
    }
    
    public static String unparseSqlQuery(ComplexQuery complexQuery) throws QueryFormulationException {
        LinkedList<String> outputArgs = new LinkedList<String>();
        if (complexQuery.getToStringResultFormat() != null) {
            outputArgs.add("FORMAT = '" + complexQuery.getToStringResultFormat() + "'");
        }
        if (complexQuery.getSortByMetKey() != null) {
            outputArgs.add("SORT_BY = '" + complexQuery.getSortByMetKey() + "'");
        }
        if (complexQuery.getQueryFilter() != null) {
            String filterString = "FILTER = '"
                    + complexQuery.getQueryFilter().getStartDateTimeMetKey() + ","
                    + complexQuery.getQueryFilter().getEndDateTimeMetKey() + ","
                    + complexQuery.getQueryFilter().getPriorityMetKey() + ","
                    + complexQuery.getQueryFilter().getFilterAlgor().getClass().getCanonicalName() + ","
                    + complexQuery.getQueryFilter().getFilterAlgor().getEpsilon();
            outputArgs.add(filterString + "'");
        }
        String sqlQueryString = getInfixCriteriaString(complexQuery.getCriteria());
        if (sqlQueryString != null && sqlQueryString.startsWith("(") && sqlQueryString.endsWith(")")) {
            sqlQueryString = sqlQueryString.substring(1, sqlQueryString.length() - 1);
        }
        return "SQL ("
                + listToString(outputArgs)
                + ") { SELECT " + listToString(complexQuery.getReducedMetadata())
                + " FROM " + (complexQuery.getReducedProductTypeNames() != null ? listToString(complexQuery.getReducedProductTypeNames()) : "*")
                + (sqlQueryString != null ? " WHERE " + sqlQueryString : "") + " }";
    }

    public static String getInfixCriteriaString(List<QueryCriteria> criteriaList) throws QueryFormulationException {
        if (criteriaList.size() > 1) {
            return getInfixCriteriaString(new BooleanQueryCriteria(criteriaList, BooleanQueryCriteria.AND));
        } else if (criteriaList.size() == 1) {
            return getInfixCriteriaString(criteriaList.get(0));
        } else {
            return null;
        }
    }
    
    public static String getInfixCriteriaString(QueryCriteria criteria) {
        StringBuilder returnString = new StringBuilder();
        if (criteria instanceof BooleanQueryCriteria) {
            BooleanQueryCriteria bqc = (BooleanQueryCriteria) criteria;
            List<QueryCriteria> terms = bqc.getTerms();
            switch(bqc.getOperator()){
            case 0:
                returnString.append("(").append(getInfixCriteriaString(terms.get(0)));
                for (int i = 1; i < terms.size(); i++) {
                    returnString.append(" AND ").append(getInfixCriteriaString(terms.get(i)));
                }
                returnString.append(")");
                break;
            case 1:
                returnString.append("(").append(getInfixCriteriaString(terms.get(0)));
                for (int i = 1; i < terms.size(); i++) {
                    returnString.append(" OR ").append(getInfixCriteriaString(terms.get(i)));
                }
                returnString.append(")");
                break;
            case 2:
                QueryCriteria qc = bqc.getTerms().get(0);
                if (qc instanceof TermQueryCriteria) {
                    TermQueryCriteria tqc = (TermQueryCriteria) qc;
                    returnString.append(tqc.getElementName()).append(" != '").append(tqc.getValue()).append("'");
                }else {
                    returnString.append("NOT(").append(getInfixCriteriaString(qc)).append(")");
                }
                break;
            }
        }else if (criteria instanceof RangeQueryCriteria) {
            RangeQueryCriteria rqc = (RangeQueryCriteria) criteria;
            String eq = rqc.getInclusive() ? "=" : "";
            String name = rqc.getElementName();
            // The branches used to be exclusive, so getEndValue() was
            // unreachable whenever a start was present and a two-sided range
            // was written out as its lower bound alone. That widens the query
            // silently: the caller gets products from outside the range they
            // asked for. There is no BETWEEN in this grammar, so both bounds
            // are written as a conjunction, which reads back as the same
            // question.
            if (rqc.getStartValue() != null && rqc.getEndValue() != null) {
                returnString.append("(")
                        .append(name).append(" >").append(eq).append(" '").append(rqc.getStartValue()).append("'")
                        .append(" AND ")
                        .append(name).append(" <").append(eq).append(" '").append(rqc.getEndValue()).append("'")
                        .append(")");
            } else if (rqc.getStartValue() != null) {
                returnString.append(name).append(" >").append(eq).append(" '").append(rqc.getStartValue()).append("'");
            } else {
                returnString.append(name).append(" <").append(eq).append(" '").append(rqc.getEndValue()).append("'");
            }
        }else if (criteria instanceof TermQueryCriteria) {
            TermQueryCriteria tqc = (TermQueryCriteria) criteria;
            returnString.append(tqc.getElementName()).append(" == '").append(tqc.getValue()).append("'");
        }
        return returnString.toString();
    }
    
    private static String stripOutSqlDefinition(String sqlStringQueryMethod) {
        return sqlStringQueryMethod.trim().replaceAll("(?i)SQL\\s*(.*)\\s*\\{", "")
                .replaceAll("}$", "").trim();
    }
    
    private static List<Expression> getSqlStatementArgs(String sqlStringQueryMethod) throws QueryFormulationException {
        boolean inExpr = false;
        int startArgs = 0;
        for (int i = 0; i < sqlStringQueryMethod.length(); i++) {
            char curChar = sqlStringQueryMethod.charAt(i);
            switch (curChar) {
            case '(':
                startArgs = i + 1;
                break;
            case ')':
                if (!inExpr) {
                    String[] args = sqlStringQueryMethod.substring(startArgs, i).trim().split("'\\s*,");
                    LinkedList<Expression> argsList = new LinkedList<Expression>();
                    for (String arg : args) {
                        argsList.add(new Expression((arg = arg.trim()).endsWith("'") ? arg : (arg + "'")));
                    }
                    return argsList;
                } else {
                    break;
                }
            case '\'':
                inExpr = !inExpr;
                break;
            }
        }
        throw new QueryFormulationException("Failed to read in args");
    }
    
    private static QueryFilter createFilter(Expression expr) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        String[] filterArgs = expr.getValue().split(",");
        FilterAlgor filterAlgor = (FilterAlgor) Class.forName(filterArgs[3]).newInstance();
        QueryFilter qf = new QueryFilter(filterArgs[0], filterArgs[1], filterArgs[2], filterAlgor);
        filterAlgor.setEpsilon(Integer.parseInt(filterArgs[4]));
        return qf;
    }

    /**
     * Uses "Shunting yard algorithm" (see:
     * http://en.wikipedia.org/wiki/Shunting_yard_algorithm)
     */
    private static LinkedList<String> toPostFix(String statement)
            throws QueryFormulationException {
        LinkedList<String> postFix = new LinkedList<String>();
        Stack<String> stack = new Stack<String>();

        for (int i = 0; i < statement.length(); i++) {
            char curChar = statement.charAt(i);
            switch (curChar) {
            case '(':
                stack.push("(");
                break;
            case ')':
                String value;
                while (!(value = stack.pop()).equals("(")) {
                    postFix.add(value);
                }
                // peek() on an empty stack throws, and popping the '(' is
                // exactly what empties it. Every boolean this class writes is
                // parenthesised, so its own output could not be read back.
                if (!stack.isEmpty() && stack.peek().equals("NOT")) {
                    postFix.add(stack.pop());
                }
                break;
            case ' ':
                break;
            default:
                if (operatorAt(statement, i, "AND")) {
                    while (!stack.isEmpty()
                            && (stack.peek().equals("AND"))) {
                        postFix.add(stack.pop());
                    }
                    stack.push("AND");
                    i += 2;
                } else if (operatorAt(statement, i, "OR")) {
                    while (!stack.isEmpty()
                            && (stack.peek().equals("AND") || stack.peek()
                                    .equals("OR"))) {
                        postFix.add(stack.pop());
                    }
                    stack.push("OR");
                    i += 1;
                } else if (operatorAt(statement, i, "NOT")) {
                    stack.push("NOT");
                    i += 2;
                } else {
                    // The end of the value is the second quote. indexOf
                    // returns -1 when there is none, which made endIndex 0:
                    // substring(0, 0) is empty, i went to -1, and the loop
                    // added nothing forever until the heap was gone. An
                    // unquoted or unterminated value is a malformed clause,
                    // so say so instead of spinning.
                    int openQuote = statement.indexOf('\'', i);
                    int closeQuote = openQuote < 0 ? -1 : statement.indexOf('\'', openQuote + 1);
                    if (closeQuote < 0) {
                        throw new QueryFormulationException(
                                "Unterminated or unquoted value in clause: [" + statement + "]");
                    }
                    int endIndex = closeQuote + 1;
                    postFix.add(statement.substring(i, endIndex));
                    i = endIndex - 1;
                }
            }
        }

        while (!stack.isEmpty()) {
            postFix.add(stack.pop());
        }

        return postFix;
    }

    private static QueryCriteria parseStatement(LinkedList<String> postFixStatement)
            throws QueryFormulationException {
        Stack<QueryCriteria> stack = new Stack<QueryCriteria>();
        for (String item : postFixStatement) {
            if (item.equals("AND")) {
                BooleanQueryCriteria bQC = new BooleanQueryCriteria();
                bQC.addTerm(stack.pop());
                bQC.addTerm(stack.pop());
                stack.push(bQC);
            } else if (item.equals("OR")) {
                BooleanQueryCriteria bQC = new BooleanQueryCriteria();
                bQC.setOperator(BooleanQueryCriteria.OR);
                bQC.addTerm(stack.pop());
                bQC.addTerm(stack.pop());
                stack.push(bQC);
            } else if (item.equals("NOT")) {
                BooleanQueryCriteria bQC = new BooleanQueryCriteria();
                bQC.setOperator(BooleanQueryCriteria.NOT);
                bQC.addTerm(stack.pop());
                stack.push(bQC);
            } else {
                stack.push(new Expression(item).convertToQueryCriteria());
            }
        }
        return stack.pop();
    }

    private static String listToString(List<String> list) {
        StringBuilder arrayString = new StringBuilder();
        if (list.size() > 0) {
            arrayString.append(list.get(0));
            for (int i = 1; i < list.size(); i++) {
                arrayString.append(",").append(list.get(i));
            }
        }
        return arrayString.toString();
    }


    private static class Expression {

        public static final short GREATER_THAN = 12;

        public static final short LESS_THAN = 3;

        public static final short EQUAL_TO = 9;

        public static final short NOT_EQUAL_TO = 15;

        public static final short GREATER_THAN_OR_EQUAL_TO = 13;

        public static final short LESS_THAN_OR_EQUAL_TO = 11;

        public static final short NOT = 6;

        private String[] stringValues = new String[] { "`", "`", "`", "<", "`",
                "`", "!", "`", "`", "=", "`", "<=", ">", ">=", "`", "!=" };

        private String expression;

        private String key;

        private String val;

        private int op;

        public Expression(String expression) {
            this.parseExpression(this.expression = expression);
        }

        public Expression(String key, int op, String val) {
            this.key = key.trim();
            this.op = op;
            this.val = this.removeTickBounds(val.trim());
        }

        private void parseExpression(String expression) {
            Matcher matcher = Pattern.compile("((?:>=)|(?:<=)|(?:==)|(?:!=)|(?:=)|(?:>)|(?:<))").matcher(expression);
            matcher.find();
            this.key = expression.substring(0, matcher.start()).trim();
            this.val = this.removeTickBounds(expression.substring(matcher.end()).trim());
            String opString = matcher.group();
            for (char c : opString.toCharArray()) {
                this.op = this.op | this.getShortValueForOp(c);
            }
        }

        private String removeTickBounds(String value) {
            if (value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }

        private int getShortValueForOp(char op) {
            switch (op) {
            case '>':
                return GREATER_THAN;
            case '<':
                return LESS_THAN;
            case '=':
                return EQUAL_TO;
            case '!':
                return NOT;
            default:
                return 0;
            }
        }

        public QueryCriteria convertToQueryCriteria()
                throws QueryFormulationException {
            switch (this.op) {
            case GREATER_THAN:
                return new RangeQueryCriteria(this.key, this.val, null, false);
            case LESS_THAN:
                return new RangeQueryCriteria(this.key, null, this.val, false);
            case EQUAL_TO:
                return new TermQueryCriteria(this.key, this.val);
            case NOT_EQUAL_TO:
                BooleanQueryCriteria notEqBQC = new BooleanQueryCriteria();
                notEqBQC.setOperator(BooleanQueryCriteria.NOT);
                notEqBQC.addTerm(new TermQueryCriteria(this.key, this.val));
                return notEqBQC;
            case GREATER_THAN_OR_EQUAL_TO:
                return new RangeQueryCriteria(this.key, this.val, null, true);
            case LESS_THAN_OR_EQUAL_TO:
                return new RangeQueryCriteria(this.key, null, this.val, true);
            }
            throw new QueryFormulationException(
                    "Was not able to form query . . . probably an invalid operator -- "
                            + this.toString());
        }

        public String getKey() {
            return this.key;
        }

        public String getValue() {
            return this.val;
        }

        public int getOp() {
            return this.op;
        }

        public String getExpression() {
            return this.expression;
        }

        public String toString() {
            return this.key + " " + this.stringValues[this.op] + " " + this.val;
        }

    }
    
    public static void main(String[] args) throws QueryFormulationException {
        String query = "SELECT * FROM IASI_L1C WHERE one == '1' AND two == '2' OR NOT(five == '5') OR three == '3' AND four == '4'";
        System.out.println("query: " + query);
        System.out.println("query after : " + unparseSqlQuery(parseSqlQuery(query)));
        query = "SELECT * FROM IASI_L1C";
        System.out.println("query: " + query);
        System.out.println("query after : " + unparseSqlQuery(parseSqlQuery(query)));
        query = "SELECT * FROM *";
        System.out.println("query: " + query);
        System.out.println("query after : " + unparseSqlQuery(parseSqlQuery(query)));
    }

    /**
     * Whether an operator appears at this position as a word of its own.
     *
     * The scan used to be an unanchored prefix test, so an element name
     * beginning AND, OR or NOT was read as the operator: NOTES == 'x' parsed
     * as NOT applied to ES, silently returning the complement of the query
     * asked for, and ORBIT and ANDES threw. It also read past the end of the
     * string near the last few characters.
     */
    private static List<String> csvValues(String list) {
        List<String> values = new LinkedList<String>();
        for (String part : list.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * First SELECT, FROM, or WHERE at or after {@code fromIndex}, ignoring
     * text inside single quotes. Case-insensitive; a match cannot sit inside a
     * larger name.
     */
    private static int indexOfClauseKeyword(String sql, String keyword, int fromIndex) {
        int lastStart = sql.length() - keyword.length();
        boolean inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote || i < fromIndex || i > lastStart) {
                continue;
            }
            if (keywordRegionAt(sql, i, keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean keywordRegionAt(String sql, int i, String keyword) {
        if (i > 0 && isNameChar(sql.charAt(i - 1))) {
            return false;
        }
        if (!sql.regionMatches(true, i, keyword, 0, keyword.length())) {
            return false;
        }
        int after = i + keyword.length();
        return after >= sql.length() || !isNameChar(sql.charAt(after));
    }

    private static boolean operatorAt(String statement, int i, String operator) {
        if (!statement.startsWith(operator, i)) {
            return false;
        }
        int end = i + operator.length();
        return end >= statement.length() || !isNameChar(statement.charAt(end));
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }
}
