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

package org.apache.oodt.cas.resource.util;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.apache.oodt.cas.resource.util.Ulimit;

/**
 * Properties of the limit-value reading in {@link UlimitProperty}.
 *
 * <p>{@link Ulimit} parses the output of {@code ulimit -a} into these, and
 * callers ask two questions of the result: is this limit unlimited, and if not,
 * what is the number? The properties below are stated over exactly the two
 * shapes of value that command emits: a decimal count, or the word
 * {@code unlimited}.
 */
class UlimitPropertyPropertyTest {

  /**
   * A numeric limit reads back as the number it was given, and is not reported
   * as unlimited. A caller comparing a job's requirement against the limit is
   * relying on this.
   */
  @HegelTest
  void aNumericLimitReadsBackAsItsNumber(TestCase tc) {
    int value = tc.draw(integers().min(0).max(Integer.MAX_VALUE), "value");
    String name = tc.draw(text().minSize(1).maxSize(20), "name");

    UlimitProperty prop = new UlimitProperty(name, String.valueOf(value));

    assertFalse(prop.isUnlimited(), "a plain number reported as unlimited");
    assertEquals(value, prop.getIntValue());
    assertEquals(name, prop.getName());
  }

  /** The literal the shell prints for no limit is recognised as no limit. */
  @HegelTest
  void theWordUnlimitedMeansUnlimited(TestCase tc) {
    String name = tc.draw(text().minSize(1).maxSize(20), "name");

    UlimitProperty prop = new UlimitProperty(name, "unlimited");

    assertTrue(prop.isUnlimited());
    assertEquals(-1, prop.getIntValue(), "an unlimited property must read as -1");
  }

  /**
   * The unlimited flag and the numeric reading agree, and neither throws
   * whatever the shell printed. {@link Ulimit} builds one of these for every
   * line of {@code ulimit -a}, so a throw here would take down the caller
   * rather than degrade a single limit.
   */
  @HegelTest
  void unlimitedAndNumericAreTheOnlyTwoOutcomes(TestCase tc) {
    String value = tc.draw(text().minSize(0).maxSize(24), "value");
    UlimitProperty prop = new UlimitProperty("someLimit", value);

    if (!prop.isUnlimited()) {
      assertEquals(Integer.parseInt(value), prop.getIntValue());
    } else {
      assertEquals(-1, prop.getIntValue());
    }
  }

  /** Setting the value replaces it; the reading follows the new value. */
  @HegelTest
  void settersReplaceTheReading(TestCase tc) {
    int first = tc.draw(integers().min(0).max(100000), "first");
    int second = tc.draw(integers().min(0).max(100000), "second");

    UlimitProperty prop = new UlimitProperty();
    prop.setName("core file size");
    prop.setValue(String.valueOf(first));
    assertEquals(first, prop.getIntValue());

    prop.setValue(String.valueOf(second));
    assertEquals(second, prop.getIntValue());
    assertEquals("core file size", prop.getName());
  }
}
