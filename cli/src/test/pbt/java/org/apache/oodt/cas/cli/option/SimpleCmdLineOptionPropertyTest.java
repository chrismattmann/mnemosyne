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

package org.apache.oodt.cas.cli.option;

import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.HashSet;
import java.util.Set;

/**
 * The {@code equals}/{@code hashCode} contract for {@link SimpleCmdLineOption},
 * the base class every command line option in this module extends.
 *
 * <p>Options are put into {@link java.util.HashSet}s throughout the module -
 * {@code CmdLineUtility} holds the supported options in one, and
 * {@code StdCmdLineConstructor} returns one - so the JDK's rules for these two
 * methods are not academic here. A set can only deduplicate, and a lookup can
 * only find an option, if equality is an equivalence relation and equal objects
 * agree on their hash code.
 *
 * <p>Names are drawn from a small pool so that two options sharing exactly one
 * of their two names - the case the implementation treats specially - actually
 * comes up. Both names are always populated, which is what every caller in the
 * module does; an option with a null name cannot be compared at all.
 */
class SimpleCmdLineOptionPropertyTest {

  private static Generator<String> shortNames() {
    return integers().min(0).max(2).map(i -> "s" + i);
  }

  private static Generator<String> longNames() {
    return integers().min(0).max(2).map(i -> "long" + i);
  }

  private static SimpleCmdLineOption option(String shortName, String longName) {
    return new SimpleCmdLineOption(shortName, longName, "description", false);
  }

  private static SimpleCmdLineOption draw(TestCase tc, String label) {
    return option(
        tc.draw(shortNames(), label + ".short"), tc.draw(longNames(), label + ".long"));
  }

  /** An option is itself. */
  @HegelTest
  void equalsIsReflexive(TestCase tc) {
    SimpleCmdLineOption a = draw(tc, "a");

    assertTrue(a.equals(a), a + " is not equal to itself");
  }

  /** Equality does not depend on which side of the call an option is on. */
  @HegelTest
  void equalsIsSymmetric(TestCase tc) {
    SimpleCmdLineOption a = draw(tc, "a");
    SimpleCmdLineOption b = draw(tc, "b");

    assertEquals(a.equals(b), b.equals(a), "a=" + a + " b=" + b);
  }

  /**
   * Equality is transitive. Without this there is no consistent notion of "the
   * same option": a lookup for an option can succeed or fail depending on which
   * equal-looking option it is compared against first.
   */
  @HegelTest
  void equalsIsTransitive(TestCase tc) {
    SimpleCmdLineOption a = draw(tc, "a");
    SimpleCmdLineOption b = draw(tc, "b");
    SimpleCmdLineOption c = draw(tc, "c");
    tc.assume(a.equals(b) && b.equals(c));

    assertTrue(a.equals(c), "a=" + a + " equals b=" + b + " equals c=" + c + " but a != c");
  }

  /**
   * Equal options hash the same. This is the precondition for a
   * {@link java.util.HashSet} of options to behave like a set at all: if two
   * equal options hash differently, the set holds both of them and every
   * membership test becomes a coin toss on bucket layout.
   */
  @HegelTest
  void equalOptionsShareAHashCode(TestCase tc) {
    SimpleCmdLineOption a = draw(tc, "a");
    SimpleCmdLineOption b = draw(tc, "b");
    tc.assume(a.equals(b));

    assertEquals(a.hashCode(), b.hashCode(), "a=" + a + " equals b=" + b);
  }

  /**
   * The consequence a caller actually sees: a set never holds two options that
   * are equal to one another.
   */
  @HegelTest
  void aSetNeverHoldsTwoEqualOptions(TestCase tc) {
    SimpleCmdLineOption a = draw(tc, "a");
    SimpleCmdLineOption b = draw(tc, "b");
    tc.assume(a.equals(b));

    Set<CmdLineOption> options = new HashSet<CmdLineOption>();
    options.add(a);
    options.add(b);

    assertEquals(1, options.size(), "set kept both a=" + a + " and b=" + b);
  }
}
