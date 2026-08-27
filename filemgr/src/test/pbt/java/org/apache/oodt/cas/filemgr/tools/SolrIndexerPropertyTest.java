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

package org.apache.oodt.cas.filemgr.tools;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 * Properties of the two parts of {@link SolrIndexer} that decide what it will
 * do before it does anything: the command-line grammar and the indexer
 * configuration file.
 *
 * <p>Everything else in the class talks to a live Solr instance or a live File
 * Manager over HTTP, and is deliberately left alone — a property that needed a
 * server standing up would be testing the server. What is left is still the
 * part a person interacts with: the arguments they type and the properties file
 * they edit. A misread option runs the wrong operation against a production
 * index, and a misread configuration line silently drops the mapping for a
 * metadata field, so the field is simply absent from every document indexed
 * from then on.
 *
 * <p>{@code IndexerConfig} is an inner class, and its constructor reads nothing
 * from the indexer that encloses it, so it is built here with a null enclosing
 * instance. Building a real {@link SolrIndexer} would mean naming a Solr
 * address and a File Manager address, and this way no property below can reach
 * a network at all.
 */
class SolrIndexerPropertyTest {

  private static final Constructor<?> CONFIG_CONSTRUCTOR = configConstructor();

  private static Constructor<?> configConstructor() {
    try {
      Constructor<?> constructor =
          SolrIndexer.IndexerConfig.class.getDeclaredConstructor(
              SolrIndexer.class, InputStream.class);
      constructor.setAccessible(true);
      return constructor;
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("the indexer configuration constructor moved", e);
    }
  }

  private static SolrIndexer.IndexerConfig configFrom(String properties) {
    try {
      return (SolrIndexer.IndexerConfig)
          CONFIG_CONSTRUCTOR.newInstance(
              null,
              new ByteArrayInputStream(properties.getBytes(StandardCharsets.ISO_8859_1)));
    } catch (InvocationTargetException e) {
      throw new AssertionError(
          "reading the configuration threw " + e.getCause() + " for:\n" + properties, e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A key or value out of the alphabet a properties file safely holds
   * unescaped. {@link java.util.Properties#load(InputStream)} reads
   * ISO-8859-1 and treats {@code =}, {@code :} and whitespace as separators, so
   * a generator that strayed outside this alphabet would be testing the
   * properties format rather than the indexer's use of it.
   */
  private static Generator<String> words() {
    return lists(
            sampledFrom(
                Arrays.asList(
                    "a", "b", "c", "m", "z", "A", "B", "N", "Z", "0", "1", "8", "9", "_", "-")))
        .minSize(1)
        .maxSize(8)
        .map(parts -> String.join("", parts));
  }

  /** A metadata key as the file manager spells one. */
  private static Generator<String> metadataKeys() {
    return words().map(word -> "CAS." + word);
  }

  /**
   * A value for an option that takes one. It never begins with a hyphen: a
   * leading hyphen makes the token another option rather than a value, which is
   * how every POSIX command line works and is not what these properties are
   * about.
   */
  private static Generator<String> optionValues() {
    return words().map(word -> "v" + word);
  }

  /** Every option the tool advertises, short name first. */
  private static final List<String> FLAGS = Arrays.asList("h", "o", "d");

  private static final List<String> ACTIONS =
      Arrays.asList("a", "p", "mf", "r", "t", "da");

  private static final List<String> ARGUMENT_OPTIONS = Arrays.asList("su", "fmu", "p", "mf");

  private static CommandLine parse(String... args) throws ParseException {
    return new GnuParser().parse(SolrIndexer.buildCommandLine(), args);
  }

  /** Arbitrary argument text, most of it nothing like an option. */
  private static Generator<String> arbitraryArguments() {
    return sampledFrom(
        Arrays.asList(
            "-h", "--help", "-a", "--all", "-p", "--product", "-mf", "--metFile", "-r", "-t",
            "-da", "-o", "-d", "-su", "--solrUrl", "-fmu", "--fmUrl", "--", "-", "", "x",
            "-x", "--nope", "http://localhost:8983/solr", "value", "-p=1", "--product=1"));
  }

  /**
   * The command-line grammar answers for any arguments at all: either a parsed
   * command line, or a {@link ParseException} naming what was wrong with them.
   * A person typing a command gets one of those two; anything else is a stack
   * trace where a usage message belongs.
   */
  @HegelTest(testCases = 2000)
  void anyArgumentsAreEitherParsedOrRejectedByName(TestCase tc) {
    List<String> args = tc.draw(lists(arbitraryArguments()).minSize(0).maxSize(6), "args");
    tc.note("args = " + args);

    try {
      CommandLine line = parse(args.toArray(new String[0]));
      assertNotNull(line, "the parser returned no command line and raised nothing");
    } catch (ParseException expected) {
      assertNotNull(
          expected.getMessage(), "the arguments were rejected without saying why: " + args);
    }
  }

  /**
   * Every option can be given by either of its names. The long names are what
   * the help text prints and what scripts are written with, so a long name that
   * does not reach the same option is a documented invocation that does not
   * work.
   */
  @HegelTest
  void anOptionIsReachableByEitherOfItsNames(TestCase tc) throws Exception {
    Options options = SolrIndexer.buildCommandLine();
    List<String> shortNames = new ArrayList<String>();
    for (Object each : options.getOptions()) {
      shortNames.add(((Option) each).getOpt());
    }
    String shortName = tc.draw(sampledFrom(shortNames), "option");
    Option option = options.getOption(shortName);
    String longName = option.getLongOpt();
    assertNotNull(longName, "option [-" + shortName + "] has no long name to document");

    List<String> shortForm = new ArrayList<String>(Arrays.asList("-" + shortName));
    List<String> longForm = new ArrayList<String>(Arrays.asList("--" + longName));
    if (option.hasArg()) {
      String value = tc.draw(optionValues(), "value");
      shortForm.add(value);
      longForm.add(value);
    }

    CommandLine viaShort = parse(shortForm.toArray(new String[0]));
    CommandLine viaLong = parse(longForm.toArray(new String[0]));

    assertTrue(viaShort.hasOption(shortName), "the short name did not set the option");
    assertTrue(viaLong.hasOption(shortName), "the long name did not set the option");
    assertEquals(
        viaShort.getOptionValue(shortName),
        viaLong.getOptionValue(shortName),
        "the two names carried different values");
  }

  /**
   * The actions are mutually exclusive. Indexing everything and deleting
   * everything are opposite operations against the same index; asking for two
   * of them in one command has to be refused rather than resolved by whichever
   * the code happens to test for first.
   */
  @HegelTest
  void twoActionsInOneCommandAreRefused(TestCase tc) {
    String first = tc.draw(sampledFrom(ACTIONS), "first");
    int step = tc.draw(dev.hegel.Generators.integers().min(1).max(ACTIONS.size() - 1), "step");
    String second = ACTIONS.get((ACTIONS.indexOf(first) + step) % ACTIONS.size());

    List<String> args = new ArrayList<String>();
    args.add("-" + first);
    if (Arrays.asList("p", "mf").contains(first)) {
      args.add("value1");
    }
    args.add("-" + second);
    if (Arrays.asList("p", "mf").contains(second)) {
      args.add("value2");
    }
    tc.note("args = " + args);

    try {
      CommandLine line = parse(args.toArray(new String[0]));
      throw new AssertionError(
          "[-" + first + "] and [-" + second + "] were both accepted: " + line.getOptions().length
              + " options");
    } catch (ParseException expected) {
      assertTrue(
          expected.getMessage().contains(first) || expected.getMessage().contains(second),
          "the refusal does not name either action: " + expected.getMessage());
    }
  }

  /**
   * An option that takes a value keeps the value it was given, whatever it
   * looks like. A product identifier or a file path is arbitrary text, and one
   * that is truncated or reinterpreted indexes the wrong product.
   */
  @HegelTest
  void anOptionValueIsKeptAsGiven(TestCase tc) throws Exception {
    String option = tc.draw(sampledFrom(ARGUMENT_OPTIONS), "option");
    String value =
        tc.draw(optionValues(), "head")
            + tc.draw(sampledFrom(Arrays.asList("/", ":", "-", "_", ".", "=", " ")), "middle")
            + tc.draw(words(), "tail");

    CommandLine line = parse("-" + option, value);

    assertEquals(value, line.getOptionValue(option), "the option value was changed");
  }

  /**
   * A command that names no action does nothing to any index. Running the tool
   * with only a flag, or with no arguments at all, has to be the harmless case:
   * it is what a person types to find out what the tool does.
   */
  @HegelTest(testCases = 20)
  void aCommandWithNoActionAsksForNothing(TestCase tc) throws Exception {
    List<String> args = tc.draw(lists(sampledFrom(FLAGS)).minSize(0).maxSize(3), "flags");
    List<String> argv = new ArrayList<String>();
    for (String flag : args) {
      if (!argv.contains("-" + flag)) {
        argv.add("-" + flag);
      }
    }
    tc.note("args = " + argv);

    CommandLine line = parse(argv.toArray(new String[0]));

    for (String action : ACTIONS) {
      assertFalse(
          line.hasOption(action),
          "a command of flags alone selected the action [-" + action + "]");
    }
    // main() takes the help branch for exactly these commands, and so touches
    // neither Solr nor the File Manager.
    SolrIndexer.main(argv.toArray(new String[0]));
  }

  /**
   * A metadata-to-Solr mapping written in the configuration file is the
   * mapping the indexer uses, under the key with its {@code map.} prefix
   * removed. Every field that reaches the index goes through this table; a key
   * that is misfiled is a field that is simply never indexed, with nothing said
   * about it.
   */
  @HegelTest
  void aFieldMappingIsReadUnderItsUnprefixedKey(TestCase tc) {
    String key = tc.draw(metadataKeys(), "key");
    String solrField = tc.draw(words(), "solrField");
    String configKey = tc.draw(words(), "configKey");
    String configValue = tc.draw(words(), "configValue");
    String dateKey = tc.draw(metadataKeys(), "dateKey");

    String properties =
        "map." + key + "=" + solrField + "\n"
            + "config." + configKey + "=" + configValue + "\n"
            + "format." + dateKey + "=yyyy-MM-dd\n";
    tc.note(properties);

    SolrIndexer.IndexerConfig config = configFrom(properties);

    assertEquals(
        solrField, config.getMapProperties().getProperty(key), "the field mapping was lost");
    assertEquals(configValue, config.getProperty(configKey), "the setting was lost");
    assertEquals(
        "yyyy-MM-dd",
        config.getFormatProperties().getProperty(dateKey),
        "the date format was lost");
  }

  /**
   * Each prefix files its lines into its own table and no other. The three
   * prefixes mean three different things — a setting, a field mapping, a date
   * format — and a line read into the wrong table is both a setting that does
   * not take effect and a mapping that was never asked for.
   */
  @HegelTest
  void eachPrefixFilesItsLinesSeparately(TestCase tc) {
    String name = tc.draw(words(), "name");

    String properties =
        "map." + name + "=mapped\n"
            + "config." + name + "=configured\n"
            + "format." + name + "=formatted\n"
            + name + "=unprefixed\n";
    tc.note(properties);

    SolrIndexer.IndexerConfig config = configFrom(properties);

    assertEquals("mapped", config.getMapProperties().getProperty(name));
    assertEquals("configured", config.getProperty(name));
    assertEquals("formatted", config.getFormatProperties().getProperty(name));
    assertEquals(
        null,
        config.getMapProperties().getProperty("map." + name),
        "the prefix was left on the key");
  }

  /**
   * The three comma-separated lists are read as lists, in the order written.
   * They decide which product types are skipped and which values are treated as
   * absent, so an entry lost from one of them is a type that gets indexed when
   * the operator said not to.
   */
  @HegelTest
  void theCommaSeparatedListsAreReadInOrder(TestCase tc) {
    List<String> types = tc.draw(lists(words()).minSize(1).maxSize(4), "ignoreTypes");
    List<String> values = tc.draw(lists(words()).minSize(1).maxSize(4), "ignoreValues");
    List<String> keys = tc.draw(lists(metadataKeys()).minSize(1).maxSize(4), "replacementKeys");

    String properties =
        "config.ignore.types=" + String.join(",", types) + "\n"
            + "config.ignore.values=" + String.join(",", values) + "\n"
            + "config.replacement.keys=" + String.join(",", keys) + "\n";
    tc.note(properties);

    SolrIndexer.IndexerConfig config = configFrom(properties);

    assertEquals(types, config.getIgnoreTypes(), "the ignored product types changed");
    assertEquals(values, config.getIgnoreValues(), "the ignored values changed");
    assertEquals(keys, config.getReplacementKeys(), "the substitution keys changed");
  }

  /**
   * A configuration that says nothing about the lists leaves them empty rather
   * than absent. Every use of them iterates or asks whether they contain
   * something, so an unset list has to be a list.
   */
  @HegelTest
  void anUnsetListIsEmptyRatherThanMissing(TestCase tc) {
    String key = tc.draw(metadataKeys(), "key");

    SolrIndexer.IndexerConfig config = configFrom("map." + key + "=field\n");

    assertTrue(config.getIgnoreTypes().isEmpty(), "the ignored types list was not empty");
    assertTrue(config.getIgnoreValues().isEmpty(), "the ignored values list was not empty");
    assertTrue(config.getReplacementKeys().isEmpty(), "the substitution key list was not empty");
    assertEquals(
        "fallback",
        config.getProperty("nothing.is.set.here", "fallback"),
        "the default was not returned for an unset setting");
  }
}
