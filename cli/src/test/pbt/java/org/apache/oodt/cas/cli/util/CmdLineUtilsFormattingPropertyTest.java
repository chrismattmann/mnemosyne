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

package org.apache.oodt.cas.cli.util;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Properties of the word wrapping in
 * {@link CmdLineUtils#getFormattedString(String, int, int)}.
 *
 * <p>The method is the layout engine behind the generated help text: it is
 * asked to place a description into the terminal column that runs from
 * {@code startIndex} to {@code endIndex}, and {@code StdCmdLinePrinter} calls
 * it with a fixed column of 62..113 while writing other text to the left of
 * it. Two things follow that a caller relies on. The text must come out the
 * far side unchanged - same words, same order - and every line must fit in
 * the column it was given, because a line that overruns wraps in the terminal
 * and drags the rest of the layout with it.
 *
 * <p>The class had no unit tests at all.
 */
class CmdLineUtilsFormattingPropertyTest {

   /** Words are drawn from letters and digits only, so no word contains a space. */
   private static Generator<String> word(int maxSize) {
      return text().minSize(1).maxSize(maxSize).categories("Lu", "Ll", "Nd");
   }

   private static Generator<List<String>> wordLists(int maxWordSize) {
      return lists(word(maxWordSize)).maxSize(8);
   }

   /** The whitespace-separated words of a string, in order. */
   private static List<String> wordsOf(String string) {
      String trimmed = string.trim();
      if (trimmed.isEmpty()) {
         return new ArrayList<String>();
      }
      return Arrays.asList(trimmed.split("\\s+"));
   }

   /** The non-empty lines of the formatted output. */
   private static List<String> linesOf(String formatted) {
      List<String> lines = new ArrayList<String>();
      for (String line : formatted.split("\n")) {
         if (!line.trim().isEmpty()) {
            lines.add(line);
         }
      }
      return lines;
   }

   private static String spaces(int count) {
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < count; i++) {
         builder.append(' ');
      }
      return builder.toString();
   }

   /**
    * Wrapping is a layout change and nothing else: every word of the input
    * comes back, once, in the order it went in. Help text that quietly loses
    * or reorders a word is worse than no help text.
    */
   @HegelTest
   void wrappingPreservesTheWords(TestCase tc) {
      List<String> words = tc.draw(wordLists(12), "words");
      int startIndex = tc.draw(integers().min(0).max(20), "startIndex");
      int width = tc.draw(integers().min(1).max(40), "width");

      String formatted =
            CmdLineUtils.getFormattedString(
                  String.join(" ", words), startIndex, startIndex + width);

      assertEquals(words, wordsOf(formatted));
   }

   /**
    * Every line sits inside the requested column: it is indented to
    * {@code startIndex} and, ignoring the trailing separator space, ends at
    * or before {@code endIndex}. Stated here over descriptions whose words
    * each fit the column on their own, which is the easy case.
    */
   @HegelTest
   void linesFitTheColumnWhenEveryWordFits(TestCase tc) {
      int startIndex = tc.draw(integers().min(0).max(20), "startIndex");
      int width = tc.draw(integers().min(1).max(40), "width");
      List<String> words = tc.draw(wordLists(width), "words");
      int endIndex = startIndex + width;

      String formatted =
            CmdLineUtils.getFormattedString(String.join(" ", words), startIndex, endIndex);

      for (String line : linesOf(formatted)) {
         assertTrue(
               line.startsWith(spaces(startIndex)),
               "line is not indented to the start of the column: [" + line + "]");
         assertTrue(
               stripTrailing(line).length() <= endIndex,
               "line runs to column "
                     + stripTrailing(line).length()
                     + ", past the requested "
                     + endIndex
                     + ": ["
                     + line
                     + "]");
      }
   }

   /**
    * The same column invariant, over the descriptions a caller can actually
    * pass. Nothing filters the input, so a single word longer than the column
    * - a class name in a requirement rule's {@code toString}, a URL, a long
    * option name - is an ordinary thing to be asked to lay out.
    */
   @HegelTest
   void everyLineFitsTheColumn(TestCase tc) {
      int startIndex = tc.draw(integers().min(0).max(20), "startIndex");
      int width = tc.draw(integers().min(4).max(20), "width");
      List<String> words = tc.draw(wordLists(width + 10), "words");
      int endIndex = startIndex + width;

      String formatted =
            CmdLineUtils.getFormattedString(String.join(" ", words), startIndex, endIndex);
      tc.note("formatted:\n" + formatted);

      for (String line : linesOf(formatted)) {
         assertTrue(
               stripTrailing(line).length() <= endIndex,
               "line runs to column "
                     + stripTrailing(line).length()
                     + ", past the requested "
                     + endIndex
                     + ": ["
                     + line
                     + "]");
      }
   }

   /**
    * Only the words matter. Runs of spaces, and leading or trailing
    * whitespace, are separators in the input and must not change the layout -
    * otherwise help text picks up its shape from how the description happened
    * to be typed.
    */
   @HegelTest
   void layoutDependsOnlyOnTheWords(TestCase tc) {
      List<String> words = tc.draw(wordLists(12), "words");
      int startIndex = tc.draw(integers().min(0).max(20), "startIndex");
      int width = tc.draw(integers().min(1).max(40), "width");

      StringBuilder raggedly = new StringBuilder();
      raggedly.append(spaces(tc.draw(integers().min(0).max(3), "leadingSpaces")));
      for (int i = 0; i < words.size(); i++) {
         if (i > 0) {
            raggedly.append(spaces(tc.draw(integers().min(1).max(3), "gap[" + i + "]")));
         }
         raggedly.append(words.get(i));
      }
      raggedly.append(spaces(tc.draw(integers().min(0).max(3), "trailingSpaces")));

      assertEquals(
            CmdLineUtils.getFormattedString(
                  String.join(" ", words), startIndex, startIndex + width),
            CmdLineUtils.getFormattedString(
                  raggedly.toString(), startIndex, startIndex + width));
   }

   private static String stripTrailing(String line) {
      int end = line.length();
      while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
         end--;
      }
      return line.substring(0, end);
   }
}
