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

package org.apache.oodt.pcs.input;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;

/**
 * Properties of {@link PGEMatrix}, the rows-and-columns value a PGE
 * configuration file can carry.
 *
 * <p>The class states its own contract in code: {@code addValue} and
 * {@code getValue} both begin with a bounds check and give up quietly — return
 * with nothing stored, or return null — when the caller names a cell outside
 * the matrix. The properties below hold it to that, and to storing what it was
 * handed.
 */
class PGEMatrixPropertyTest {

  /** Fills the matrix cell by cell, row-major, the order the file reader uses. */
  private static PGEMatrix filled(String name, int rows, int cols) {
    PGEMatrix matrix = new PGEMatrix(name, rows, cols);
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        matrix.addValue(row + "," + col, row, col);
      }
    }
    return matrix;
  }

  /** A new matrix has the shape it was asked for. */
  @HegelTest
  void aNewMatrixHasTheRequestedShape(TestCase tc) {
    int rows = tc.draw(integers().min(0).max(12), "rows");
    int cols = tc.draw(integers().min(0).max(12), "cols");

    PGEMatrix matrix = new PGEMatrix("M", rows, cols);

    assertEquals(rows, matrix.getRows().size());
    assertEquals(cols, matrix.getNumCols());
  }

  /**
   * Every cell written comes back from the cell it was written to. A matrix in
   * a PGE configuration file is read row by row and column by column, so a
   * value landing in the wrong cell would feed the PGE the wrong number.
   */
  @HegelTest
  void everyCellReadsBackWhatWasWrittenToIt(TestCase tc) {
    int rows = tc.draw(integers().min(1).max(10), "rows");
    int cols = tc.draw(integers().min(1).max(10), "cols");

    PGEMatrix matrix = filled("M", rows, cols);

    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        assertEquals(row + "," + col, matrix.getValue(row, col),
            "cell (" + row + "," + col + ") of a " + rows + "x" + cols + " matrix");
      }
    }
  }

  /**
   * Naming a cell outside the matrix is refused rather than allowed to blow
   * up. The bounds check at the top of {@code addValue} exists precisely so a
   * caller working from a row or column count can be off by one without taking
   * the process down; the check has to cover the first index past the end,
   * which is exactly the value such a caller reaches.
   */
  @HegelTest
  void writingOutsideTheMatrixIsRefusedQuietly(TestCase tc) {
    int rows = tc.draw(integers().min(1).max(8), "rows");
    int cols = tc.draw(integers().min(1).max(8), "cols");
    String which = tc.draw(sampledFrom("rowPastEnd", "colPastEnd", "negativeRow", "negativeCol"),
        "which");

    int row;
    int col;
    switch (which) {
      case "rowPastEnd":
        row = rows;
        col = 0;
        break;
      case "colPastEnd":
        row = 0;
        col = cols;
        break;
      case "negativeRow":
        row = -1;
        col = 0;
        break;
      default:
        row = 0;
        col = -1;
        break;
    }

    PGEMatrix matrix = filled("M", rows, cols);

    try {
      matrix.addValue("out of range", row, col);
    } catch (RuntimeException e) {
      fail("addValue(" + row + "," + col + ") on a " + rows + "x" + cols + " matrix threw "
          + e.getClass().getName() + ": " + e.getMessage());
    }

    assertEquals(rows, matrix.getRows().size(), "an out-of-range write changed the shape");
    for (int r = 0; r < rows; r++) {
      assertEquals(cols, matrix.getRows().get(r).size(),
          "an out-of-range write changed the width of row " + r);
    }
  }

  /**
   * The same rule on the reading side: a cell outside the matrix is reported
   * as absent, not by throwing.
   */
  @HegelTest
  void readingOutsideTheMatrixIsRefusedQuietly(TestCase tc) {
    int rows = tc.draw(integers().min(1).max(8), "rows");
    int cols = tc.draw(integers().min(1).max(8), "cols");
    String which = tc.draw(sampledFrom("rowPastEnd", "colPastEnd", "negativeRow", "negativeCol"),
        "which");

    int row;
    int col;
    switch (which) {
      case "rowPastEnd":
        row = rows;
        col = 0;
        break;
      case "colPastEnd":
        row = 0;
        col = cols;
        break;
      case "negativeRow":
        row = -1;
        col = 0;
        break;
      default:
        row = 0;
        col = -1;
        break;
    }

    PGEMatrix matrix = filled("M", rows, cols);

    try {
      matrix.getValue(row, col);
    } catch (RuntimeException e) {
      fail("getValue(" + row + "," + col + ") on a " + rows + "x" + cols + " matrix threw "
          + e.getClass().getName() + ": " + e.getMessage());
    }
  }
}
