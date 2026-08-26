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

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Properties of {@link PGEGroup}, the named bag of scalars, vectors and
 * matrices that a PGE configuration file is built out of.
 *
 * <p>A group is a plain in-memory container, so every property here is decided
 * without touching a file. The one rule that is not simply map behaviour is
 * that {@code addX} refuses to overwrite a name that is already taken, which
 * is what makes the file reader's first-definition-wins behaviour possible.
 */
class PGEGroupPropertyTest {

  /** A configuration key: a name a PGE author would write in the XML. */
  private static final Generator<String> NAME =
      text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");

  private static final Generator<String> VALUE =
      text().minSize(0).maxSize(16).categories("Lu", "Ll", "Nd");

  /**
   * Every scalar added to a group can be looked up again by its name, and the
   * group's scalar count is the number of distinct names it holds.
   */
  @HegelTest
  void scalarsCanBeFoundAgainByName(TestCase tc) {
    List<String> names = tc.draw(lists(NAME).minSize(0).maxSize(20), "names");
    List<String> values = tc.draw(lists(VALUE).minSize(0).maxSize(20), "values");

    PGEGroup group = new PGEGroup("Group");
    Set<String> distinct = new LinkedHashSet<>();
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      group.addScalar(new PGEScalar(name, i < values.size() ? values.get(i) : ""));
      distinct.add(name);
    }

    assertEquals(distinct.size(), group.getNumScalars());
    for (String name : distinct) {
      assertEquals(name, group.getScalar(name).getName());
    }
  }

  /**
   * The first definition of a name wins. The configuration file reader adds
   * scalars in document order, so this is what makes an earlier entry in the
   * file authoritative over a later duplicate rather than the other way
   * around.
   */
  @HegelTest
  void theFirstDefinitionOfANameWins(TestCase tc) {
    String name = tc.draw(NAME, "name");
    List<String> values = tc.draw(lists(VALUE).minSize(2).maxSize(6), "values");

    PGEGroup group = new PGEGroup("Group");
    for (String value : values) {
      group.addScalar(new PGEScalar(name, value));
    }

    assertEquals(1, group.getNumScalars(), "duplicate names created more than one entry");
    assertEquals(values.get(0), group.getScalar(name).getValue());
  }

  /**
   * Vectors behave the same way: added once, retrievable by name, counted
   * once per distinct name.
   */
  @HegelTest
  void vectorsCanBeFoundAgainByName(TestCase tc) {
    List<String> names = tc.draw(lists(NAME).minSize(0).maxSize(15), "names");
    List<String> elements = tc.draw(lists(VALUE).minSize(1).maxSize(5), "elements");

    PGEGroup group = new PGEGroup("Group");
    Set<String> distinct = new LinkedHashSet<>();
    for (String name : names) {
      group.addVector(new PGEVector(name, new ArrayList<Object>(elements)));
      distinct.add(name);
    }

    assertEquals(distinct.size(), group.getNumVectors());
    for (String name : distinct) {
      assertEquals(elements, group.getVector(name).getElements());
    }
  }

  /** Matrices behave the same way. */
  @HegelTest
  void matricesCanBeFoundAgainByName(TestCase tc) {
    List<String> names = tc.draw(lists(NAME).minSize(0).maxSize(15), "names");

    PGEGroup group = new PGEGroup("Group");
    Set<String> distinct = new LinkedHashSet<>();
    for (String name : names) {
      group.addMatrix(new PGEMatrix(name, 1, 1));
      distinct.add(name);
    }

    assertEquals(distinct.size(), group.getNumMatrixs());
    for (String name : distinct) {
      assertEquals(name, group.getMatrix(name).getName());
    }
  }

  /**
   * Looking up a name that was never defined is answered with null, not with
   * a failure: {@code ListingConf} and the PGE writers both probe optional
   * keys this way.
   */
  @HegelTest
  void lookingUpAnUndefinedNameGivesNull(TestCase tc) {
    List<String> names = tc.draw(lists(NAME).minSize(0).maxSize(10), "names");
    String missing = tc.draw(NAME, "missing");
    tc.assume(!names.contains(missing));

    PGEGroup group = new PGEGroup("Group");
    for (String name : names) {
      group.addScalar(new PGEScalar(name, "v"));
      group.addVector(new PGEVector(name, new ArrayList<Object>(Arrays.asList("e"))));
      group.addMatrix(new PGEMatrix(name, 1, 1));
    }

    assertNull(group.getScalar(missing));
    assertNull(group.getVector(missing));
    assertNull(group.getMatrix(missing));
    assertNull(group.getGroup(missing));
  }

  /**
   * A group hands back the very object it was given, not a copy. The writer
   * reads values straight off the retrieved object, so a copy taken at add
   * time would silently freeze later edits.
   */
  @HegelTest
  void aGroupStoresTheObjectItWasGiven(TestCase tc) {
    String name = tc.draw(NAME, "name");
    String value = tc.draw(VALUE, "value");

    PGEGroup group = new PGEGroup("Group");
    PGEScalar scalar = new PGEScalar(name, value);
    group.addScalar(scalar);

    assertSame(scalar, group.getScalar(name));
  }
}
