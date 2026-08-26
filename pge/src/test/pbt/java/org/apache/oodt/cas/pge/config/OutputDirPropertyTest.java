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

package org.apache.oodt.cas.pge.config;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Properties of {@link OutputDir}, one directory a PGE writes into together with
 * the expressions that say which of the files it finds there are products.
 *
 * <p>An output directory is built two ways: by {@code XmlHelper} while reading a
 * config file, and by a bean container from a no-argument constructor and
 * setters. Both are entry points the class publishes, so both have to produce
 * something a caller can use.
 */
class OutputDirPropertyTest {

  private static Generator<String> paths() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  /** The path and the create-first flag come back as they went in. */
  @HegelTest
  void pathAndCreateFlagRoundTrip(TestCase tc) {
    String path = tc.draw(paths(), "path");
    boolean createBeforeExe = tc.draw(booleans(), "createBeforeExe");

    OutputDir dir = new OutputDir(path, createBeforeExe);

    assertEquals(path, dir.getPath());
    assertEquals(createBeforeExe, dir.isCreateBeforeExe());
  }

  /**
   * The path can be replaced afterwards but never with null. The path is joined
   * onto file names to locate products, so a null one would fail much later and
   * far from the config that caused it - which is exactly why the setter checks.
   */
  @HegelTest
  void thePathCanBeReplacedButNeverWithNull(TestCase tc) {
    String first = tc.draw(paths(), "first");
    String second = tc.draw(paths(), "second");

    OutputDir dir = new OutputDir(first, false);
    dir.setPath(second);
    assertEquals(second, dir.getPath());

    assertThrows(IllegalArgumentException.class, () -> dir.setPath(null));
    assertEquals(second, dir.getPath(), "a rejected path was applied anyway");
  }

  /**
   * Output file expressions come back in the order they were added. They are
   * tried in turn against each file found in the directory, so their order
   * decides which writer claims a file when more than one expression matches.
   */
  @HegelTest
  void outputFileExpressionsComeBackInOrder(TestCase tc) {
    List<String> expressions =
        tc.draw(
            lists(text().minSize(1).maxSize(6).categories("Lu", "Ll", "Nd")).maxSize(5),
            "expressions");

    OutputDir dir = new OutputDir("output", false);
    for (String expression : expressions) {
      dir.addRegExprOutputFiles(new RegExprOutputFiles(expression, "writer", null, new Object[0]));
    }

    List<String> actual = new ArrayList<String>();
    for (RegExprOutputFiles files : dir.getRegExprOutputFiles()) {
      actual.add(files.getRegExp());
    }
    assertEquals(expressions, actual);
  }

  /**
   * An output directory built the bean way - no-argument constructor, then
   * setters - is usable. The class publishes that constructor for exactly this
   * purpose, and a caller has no other way to build one before it knows the
   * path.
   */
  @HegelTest
  void theNoArgumentConstructorProducesAUsableOutputDir(TestCase tc) {
    String path = tc.draw(paths(), "path");
    boolean createBeforeExe = tc.draw(booleans(), "createBeforeExe");

    OutputDir dir = new OutputDir();
    dir.setPath(path);
    dir.setCreateBeforeExe(createBeforeExe);

    assertEquals(path, dir.getPath());
    assertEquals(createBeforeExe, dir.isCreateBeforeExe());
    assertNotNull(dir.getRegExprOutputFiles());
    assertTrue(dir.getRegExprOutputFiles().isEmpty());
  }

  /** An output file expression gives back the four things it was built from. */
  @HegelTest
  void anOutputFileExpressionRoundTrips(TestCase tc) {
    String expression = tc.draw(paths(), "expression");
    String converter = tc.draw(paths(), "converter");
    List<String> args =
        tc.draw(
            lists(text().minSize(1).maxSize(6).categories("Lu", "Ll", "Nd")).maxSize(4), "args");

    RegExprOutputFiles files =
        new RegExprOutputFiles(expression, converter, null, args.toArray());

    assertEquals(expression, files.getRegExp());
    assertEquals(converter, files.getConverterClass());
    assertEquals(args, new ArrayList<Object>(java.util.Arrays.asList(files.getArgs())));
  }
}
