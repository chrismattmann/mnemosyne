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

package org.apache.oodt.cas.crawl.action;

import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Arrays;
import java.util.List;

/**
 * Properties of the phase names in {@link CrawlerActionPhases}.
 *
 * <p>The phase name is the string a user writes in a crawler's Spring bean
 * file, so the lookup from that string back to the enum constant is a parser
 * for user input and has to be total: any string at all, correct or not, must
 * produce an answer rather than an exception.
 */
class CrawlerActionPhasesPropertyTest {

  private static final List<CrawlerActionPhases> ALL =
      Arrays.asList(CrawlerActionPhases.values());

  /**
   * The name a phase advertises is the name it answers to. This is the link
   * between the enum and the bean file, and nothing else keeps the two in step.
   */
  @HegelTest
  void everyPhaseAnswersToItsOwnName(TestCase tc) {
    CrawlerActionPhases phase = tc.draw(sampledFrom(ALL), "phase");

    assertEquals(phase, CrawlerActionPhases.getPhaseByName(phase.getName()));
  }

  /**
   * Lookup is total. A misspelt phase in a bean file is ordinary user error,
   * and the lookup is the first thing to see it, so it must return rather than
   * throw — and it must return a phase exactly when some phase carries that
   * name.
   */
  @HegelTest
  void lookupAnswersForEveryString(TestCase tc) {
    String name = tc.draw(text().maxSize(24), "name");

    CrawlerActionPhases found = CrawlerActionPhases.getPhaseByName(name);

    boolean someoneOwnsTheName = ALL.stream().anyMatch(p -> p.getName().equals(name));
    if (someoneOwnsTheName) {
      assertNotNull(found, "no phase returned for a name a phase owns");
      assertEquals(name, found.getName());
    } else {
      assertNull(found, "a phase was returned for a name no phase owns: " + name);
    }
  }

  /**
   * Two phases sharing a name would make the bean file ambiguous, and a name
   * carrying leading or trailing whitespace could never be matched by a value
   * read out of XML.
   */
  @HegelTest
  void namesAreDistinctAndUsableInABeanFile(TestCase tc) {
    CrawlerActionPhases a = tc.draw(sampledFrom(ALL), "a");
    CrawlerActionPhases b = tc.draw(sampledFrom(ALL), "b");

    assertEquals(a == b, a.getName().equals(b.getName()), "phase names are not distinct");
    assertFalse(a.getName().isEmpty(), "phase name is empty");
    assertEquals(a.getName().trim(), a.getName(), "phase name carries whitespace");
    assertTrue(a.getName().indexOf(',') < 0, "phase name contains a comma");
  }
}
