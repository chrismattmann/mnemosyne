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

package org.apache.oodt.cas.filemgr.structs.query.conv;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;

/**
 * Properties of {@link AsciiSortableVersionConverter}, which turns a version
 * string into the {@code double} priority that the query filters sort on.
 *
 * <p>The class has no unit tests at all. Its one job, per its own name and
 * class comment, is to convert "an ascii sortable String version into a
 * priority number": the priority is only ever used to decide which of two
 * versions of the same product wins, so the conversion has to carry the ascii
 * ordering across intact. A version scheme that is ascii-sortable is
 * fixed-width by construction, so every property here is stated over versions
 * of equal length — comparing versions of different lengths is something the
 * converter was never asked to do.
 *
 * <p>Characters are drawn from printable ASCII, which is the alphabet the
 * class is named for.
 */
class AsciiSortableVersionConverterPropertyTest {

  /** Printable ASCII, the alphabet an "ascii sortable" version is written in. */
  private static Generator<String> asciiOfLength(int length) {
    return text().minSize(length).maxSize(length).codepoints(0x20, 0x7e);
  }

  /**
   * The ordering promise: for two versions of the same width, whichever sorts
   * first as ascii must also get the smaller priority.
   *
   * <p>If it does not, a filter handed two versions of a product keeps the
   * wrong one, and nothing anywhere reports an error.
   *
   * <p>Around one pair in thirty-five is misordered, which the default run of
   * a hundred cases misses often enough to be flaky, so this one is given more
   * cases. The work per case is a few multiplications.
   */
  @HegelTest(testCases = 2000)
  void equalLengthVersionsKeepTheirAsciiOrder(TestCase tc) {
    int length = tc.draw(integers().min(1).max(8), "length");
    String a = tc.draw(asciiOfLength(length), "a");
    String b = tc.draw(asciiOfLength(length), "b");
    tc.assume(!a.equals(b));

    AsciiSortableVersionConverter converter = new AsciiSortableVersionConverter();
    double priorityA = converter.convertToPriority(a);
    double priorityB = converter.convertToPriority(b);
    tc.note("priority(a) = " + priorityA + ", priority(b) = " + priorityB);

    assertEquals(
        Integer.signum(a.compareTo(b)),
        Integer.signum(Double.compare(priorityA, priorityB)),
        "'" + a + "' vs '" + b + "': ascii order and priority order disagree");
  }

  /**
   * A priority has to be a real number to be of any use as a sort key. Once
   * the conversion saturates, every version from that length upwards is handed
   * the same value and the filter can no longer tell any two of them apart.
   */
  @HegelTest
  void everyVersionGetsAFinitePriority(TestCase tc) {
    int length = tc.draw(integers().min(1).max(400), "length");
    String version = tc.draw(asciiOfLength(length), "version");

    double priority = new AsciiSortableVersionConverter().convertToPriority(version);
    tc.note("length = " + length + ", priority = " + priority);

    assertTrue(
        Double.isFinite(priority),
        "a version of length " + length + " converted to " + priority);
  }
}
