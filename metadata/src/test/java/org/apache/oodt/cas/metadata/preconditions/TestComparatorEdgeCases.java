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

package org.apache.oodt.cas.metadata.preconditions;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Edge cases in the pre-condition comparators, from #134.
 */
public class TestComparatorEdgeCases {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /**
   * lastIndexOf(".") returns -1 with no dot, and -1 + 1 == 0 compared the whole
   * file name, so a precondition configured for ".nc" also fired on a file
   * named exactly "nc".
   */
  @Test
  public void aFileWithNoExtensionDoesNotMatchAnExtension() throws Exception {
    assertFalse(endsWith("nc").passes(folder.newFile("nc")));
  }

  @Test
  public void aFileWithTheExtensionStillMatches() throws Exception {
    assertTrue(endsWith("nc").passes(folder.newFile("data.nc")));
  }

  @Test
  public void aFileWithADifferentExtensionStillDoesNotMatch() throws Exception {
    assertFalse(endsWith("nc").passes(folder.newFile("data.txt")));
  }

  /**
   * toLowerCase on the pattern rewrote \D to \d, inverting it. The path here
   * ends in a letter, so \D matches and \d does not.
   */
  @Test
  public void aCharacterClassIsNotInvertedByCaseFolding() throws Exception {
    assertTrue(regEx(".*\\D").passes(folder.newFile("archive.dat")));
  }

  @Test
  public void aPatternStillMatchesCaseInsensitively() throws Exception {
    assertTrue(regEx(".*ARCHIVE.*").passes(folder.newFile("archive.dat")));
  }

  @Test
  public void anUnrelatedPatternStillDoesNotMatch() throws Exception {
    assertFalse(regEx(".*nowhere.*").passes(folder.newFile("archive.dat")));
  }

  private EndsWithComparator endsWith(String ext) {
    EndsWithComparator comparator = new EndsWithComparator();
    comparator.setType("EQUAL_TO");
    comparator.setCompareItem(ext);
    return comparator;
  }

  private RegExExcludeComparator regEx(String pattern) {
    RegExExcludeComparator comparator = new RegExExcludeComparator();
    comparator.setType("EQUAL_TO");
    comparator.setCompareItem(pattern);
    return comparator;
  }
}
