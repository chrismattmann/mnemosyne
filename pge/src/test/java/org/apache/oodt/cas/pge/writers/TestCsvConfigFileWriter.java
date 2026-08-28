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

package org.apache.oodt.cas.pge.writers;

import org.apache.oodt.cas.metadata.Metadata;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * generateRows could not produce a single row for any input -- the class was
 * dead on arrival, four ways over in eleven lines. There was no test for it,
 * and DynamicConfigFileWriter implementations are selected by class name in
 * PGE configuration, so nothing exercised it unless a deployment named it,
 * and any deployment that named it would have failed immediately.
 */
public class TestCsvConfigFileWriter {

    private static List<List<String>> rowsFor(Metadata met, String... columns) {
        return new CsvConfigFileWriter().generateRows(Arrays.asList(columns), met);
    }

    /** The counterexample: columns=["0"], values=["0"]. */
    @Test
    public void testASingleColumnWithOneValueGivesOneRow() {
        Metadata met = new Metadata();
        met.addMetadata("0", "0");

        List<List<String>> rows = rowsFor(met, "0");

        assertEquals(1, rows.size());
        assertEquals(Arrays.asList("0"), rows.get(0));
    }

    /**
     * The other counterexample: columns=["0"] with empty metadata.
     * Metadata.getAllMetadata returns null, not an empty list, for a key that
     * is not present.
     */
    @Test
    public void testAnAbsentColumnGivesNoRows() {
        List<List<String>> rows = rowsFor(new Metadata(), "0");

        assertNotNull(rows);
        assertTrue(rows.isEmpty());
    }

    /** index was never incremented, so more than one row was unreachable. */
    @Test
    public void testEveryValueBecomesItsOwnRow() {
        Metadata met = new Metadata();
        met.addMetadata("Filename", "a.txt");
        met.addMetadata("Filename", "b.txt");
        met.addMetadata("Filename", "c.txt");

        List<List<String>> rows = rowsFor(met, "Filename");

        assertEquals(3, rows.size());
        assertEquals(Arrays.asList("a.txt"), rows.get(0));
        assertEquals(Arrays.asList("b.txt"), rows.get(1));
        assertEquals(Arrays.asList("c.txt"), rows.get(2));
    }

    /** Several columns line up across each row. */
    @Test
    public void testSeveralColumnsLineUpAcrossRows() {
        Metadata met = new Metadata();
        met.addMetadata("Filename", "a.txt");
        met.addMetadata("Filename", "b.txt");
        met.addMetadata("Valid", "true");
        met.addMetadata("Valid", "false");

        List<List<String>> rows = rowsFor(met, "Filename", "Valid");

        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("a.txt", "true"), rows.get(0));
        assertEquals(Arrays.asList("b.txt", "false"), rows.get(1));
    }

    /** A ragged column stops the table at the shortest one. */
    @Test
    public void testARaggedColumnStopsTheTable() {
        Metadata met = new Metadata();
        met.addMetadata("Filename", "a.txt");
        met.addMetadata("Filename", "b.txt");
        met.addMetadata("Valid", "true");

        List<List<String>> rows = rowsFor(met, "Filename", "Valid");

        assertEquals(1, rows.size());
        assertEquals(Arrays.asList("a.txt", "true"), rows.get(0));
    }
}
