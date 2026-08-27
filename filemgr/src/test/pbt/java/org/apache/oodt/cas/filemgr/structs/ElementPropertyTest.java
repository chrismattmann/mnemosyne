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

package org.apache.oodt.cas.filemgr.structs;

import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.HashSet;
import java.util.Set;

/**
 * Properties for {@link Element}, the description of one metadata field that
 * the validation layer hands out.
 *
 * <p>{@code Element} overrides {@code hashCode} and derives it from the element
 * id alone. Overriding one half of the pair is what makes these properties
 * worth stating: the class has declared that two elements are the same thing
 * when their ids match, and everything that hashes an element is entitled to
 * take that declaration at face value.
 */
class ElementPropertyTest {

  private static Generator<String> words() {
    return text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");
  }

  private static Element element(String id, String name, String dc, String description) {
    Element element = new Element();
    element.setElementId(id);
    element.setElementName(name);
    element.setDCElement(dc);
    element.setDescription(description);
    return element;
  }

  /** Every field set on an element must be readable back off it. */
  @HegelTest
  void fieldsAreReadableBack(TestCase tc) {
    String id = tc.draw(words(), "elementId");
    String name = tc.draw(words(), "elementName");
    String dc = tc.draw(words(), "dcElement");
    String description = tc.draw(words(), "description");

    Element element = element(id, name, dc, description);

    assertEquals(id, element.getElementId());
    assertEquals(name, element.getElementName());
    assertEquals(dc, element.getDCElement());
    assertEquals(description, element.getDescription());
  }

  /**
   * Hashing an element must not depend on fields the class says are incidental.
   *
   * <p>{@code hashCode} is the id's hash, so two elements with the same id hash
   * alike whatever else differs. That much the class gets right, and it is
   * pinned here so that a later change to the class does not quietly widen it.
   */
  @HegelTest
  void elementsWithTheSameIdHashAlike(TestCase tc) {
    String id = tc.draw(words(), "elementId");
    Element first =
        element(id, tc.draw(words(), "nameA"), tc.draw(words(), "dcA"), tc.draw(words(), "descA"));
    Element second =
        element(id, tc.draw(words(), "nameB"), tc.draw(words(), "dcB"), tc.draw(words(), "descB"));

    assertEquals(first.hashCode(), second.hashCode(), "same id, different hash");
  }

  /**
   * Two elements the class hashes as one must also be treated as one by a set.
   *
   * <p>A client that reads the element definitions for a product type and
   * compares them against a set it built earlier is doing exactly this. Because
   * only {@code hashCode} was overridden, the two land in the same bucket and
   * are then told apart by identity, so the set keeps both and the client sees
   * a metadata field it already knew about as new.
   */
  @HegelTest
  void aSetTreatsElementsWithTheSameIdAsOne(TestCase tc) {
    String id = tc.draw(words(), "elementId");
    String name = tc.draw(words(), "elementName");
    String dc = tc.draw(words(), "dcElement");
    String description = tc.draw(words(), "description");

    Set<Element> seen = new HashSet<>();
    seen.add(element(id, name, dc, description));
    seen.add(element(id, name, dc, description));

    assertEquals(1, seen.size(), "the same element definition was counted twice");
  }

  /**
   * Hashing an element whose id has not been set must not fail.
   *
   * <p>An id is assigned by whichever store the element came from, so an
   * element that has been built but not yet stored has none — that is the state
   * of every element a caller constructs itself before handing it to
   * {@code addElement}. Logging such an element, putting it in a collection, or
   * looking at it in a debugger all reach {@code hashCode}, and none of those
   * is a place a caller expects an exception from.
   */
  @HegelTest
  void hashingAnUnstoredElementDoesNotFail(TestCase tc) {
    String name = tc.draw(words(), "elementName");
    String description = tc.draw(words(), "description");

    Element element = new Element();
    element.setElementName(name);
    element.setDescription(description);

    assertDoesNotThrow(element::hashCode, "hashCode failed on an element with no id yet");
  }

  /** The blank element the class offers must be complete enough to hash and read. */
  @HegelTest
  void theBlankElementIsUsable(TestCase tc) {
    tc.note("blankElement carries no drawn input; it is a constant of the class");

    Element blank = Element.blankElement();

    assertNotNull(blank.getElementId());
    assertNotNull(blank.getElementName());
    assertNotNull(blank.getDCElement());
    assertNotNull(blank.getDescription());
    assertDoesNotThrow(blank::hashCode);
  }
}
