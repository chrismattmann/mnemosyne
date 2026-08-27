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

package org.apache.oodt.cas.pge.metadata;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HealthCheck;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.pge.metadata.PgeMetadata.Type;

/**
 * Properties of the key-link and metadata-layering logic in {@link PgeMetadata}.
 *
 * <p>{@code TestPgeMetadata} covers linking, unlinking and committing along one
 * hand-written happy path. What it never does is build a link graph it did not
 * choose in advance, and that is where the interesting behaviour is: {@code
 * linkKey} accepts any pair of names, so the map of links is an arbitrary
 * directed graph that a caller assembles one edge at a time, often from several
 * {@link PgeMetadata} instances merged together.
 *
 * <p>Every property below is stated over the input a caller can actually reach
 * through the public API: names {@code linkKey} accepts, values the metadata
 * layers accept, and {@link Type} lists the query methods document.
 *
 * <p>Anything that walks the link map is given a hard time budget, because the
 * walk is a bare {@code while} loop over the map with nothing to stop it.
 */
class PgeMetadataPropertyTest {

  /** How long a single walk of the link map is given before it is a hang. */
  private static final Duration WALK_BUDGET = Duration.ofSeconds(5);

  /** The key every layering property stores its competing values under. */
  private static final String SHARED_KEY = "sharedKey";

  /** A key that exists only in STATIC metadata, as a link target would. */
  private static final String STATIC_ONLY_KEY = "staticOnlyKey";

  private static final Map<Type, String> VALUE_BY_TYPE = new EnumMap<>(Type.class);

  static {
    VALUE_BY_TYPE.put(Type.STATIC, "staticValue");
    VALUE_BY_TYPE.put(Type.DYNAMIC, "dynamicValue");
    VALUE_BY_TYPE.put(Type.LOCAL, "localValue");
  }

  /** Keys are drawn from a small alphabet so that links collide and cycles form. */
  private static String key(int i) {
    return "key" + i;
  }

  /**
   * A link graph over {@code key(0)..key(keyCount - 1)}. Each key either links
   * to one of the keys or to nothing at all. When {@code acyclic} is set the
   * target is always a higher-numbered key, which rules out cycles by
   * construction; otherwise any target is allowed, which is what a caller
   * making unrelated {@code linkKey} calls ends up with.
   */
  private static PgeMetadata linkGraph(TestCase tc, int keyCount, boolean acyclic) {
    PgeMetadata pgeMetadata = new PgeMetadata();
    for (int i = 0; i < keyCount; i++) {
      int lowestTarget = acyclic ? i + 1 : 0;
      int target = tc.draw(integers().min(lowestTarget).max(keyCount), "linkOf" + key(i));
      if (target < keyCount) {
        pgeMetadata.linkKey(key(i), key(target));
      }
    }
    return pgeMetadata;
  }

  private static int countLinks(PgeMetadata pgeMetadata, int keyCount) {
    int links = 0;
    for (int i = 0; i < keyCount; i++) {
      if (pgeMetadata.isLink(key(i))) {
        links++;
      }
    }
    return links;
  }

  /**
   * Resolving a key finishes. Two keys linked to each other are a nonsensical
   * mapping, but {@link PgeMetadata#linkKey} accepts them without complaint and
   * nothing else rejects them later, so every read of either key — and
   * {@code resolveKey} is on the path of every read and every write — has to
   * come back with an answer rather than spin.
   *
   * <p>A case that hangs costs the whole time budget, and a hung walk cannot be
   * called back, so this property is run over few cases and the slow-generation
   * health check is suppressed: the slowness is the finding, not a problem with
   * the generator.
   */
  @HegelTest(testCases = 20, suppressHealthCheck = HealthCheck.TOO_SLOW)
  void resolveKeyAlwaysTerminates(TestCase tc) {
    int keyCount = tc.draw(integers().min(1).max(4), "keyCount");
    PgeMetadata pgeMetadata = linkGraph(tc, keyCount, false);
    String start = key(tc.draw(integers().min(0).max(keyCount - 1), "start"));

    assertTimeoutPreemptively(WALK_BUDGET, () -> pgeMetadata.resolveKey(start));
  }

  /**
   * A reference path names each key it passes through once, so it can be no
   * longer than the number of links that exist, and it ends on the key the
   * value will actually come from.
   *
   * <p>The graph here is acyclic by construction. That is deliberate:
   * {@code getReferenceKeyPath} appends to a list on every step, so a cyclic
   * graph does not merely hang, it allocates until the heap is gone, and a
   * timeout cannot call the thread back once it has started. Termination on a
   * cyclic graph is covered by {@link #resolveKeyAlwaysTerminates}.
   */
  @HegelTest
  void referenceKeyPathVisitsEachKeyAtMostOnce(TestCase tc) {
    int keyCount = tc.draw(integers().min(1).max(6), "keyCount");
    PgeMetadata pgeMetadata = linkGraph(tc, keyCount, true);
    String start = key(tc.draw(integers().min(0).max(keyCount - 1), "start"));
    int links = countLinks(pgeMetadata, keyCount);

    assertTimeoutPreemptively(
        WALK_BUDGET,
        () -> {
          List<String> path = pgeMetadata.getReferenceKeyPath(start);

          assertEquals(new HashSet<>(path).size(), path.size(), "path revisits a key: " + path);
          assertTrue(path.size() <= links, "path " + path + " is longer than the " + links
              + " links that exist");
          if (pgeMetadata.isLink(start)) {
            assertEquals(pgeMetadata.resolveKey(start), path.get(path.size() - 1),
                "path does not end on the key the value comes from");
          } else {
            assertTrue(path.isEmpty(), "a key that is not a link has an empty path");
          }
        });
  }

  /**
   * The point of a link: reading it gives you whatever the key at the end of
   * the chain currently holds. A caller who links a name and then reads it must
   * not be able to tell the difference between the link and the real key.
   */
  @HegelTest
  void aLinkReadsThroughToTheKeyItResolvesTo(TestCase tc) {
    int keyCount = tc.draw(integers().min(1).max(6), "keyCount");
    PgeMetadata pgeMetadata = linkGraph(tc, keyCount, true);
    for (int i = 0; i < keyCount; i++) {
      if (!pgeMetadata.isLink(key(i))) {
        pgeMetadata.replaceMetadata(key(i), "value" + i);
      }
    }

    assertTimeoutPreemptively(
        WALK_BUDGET,
        () -> {
          for (int i = 0; i < keyCount; i++) {
            String resolved = pgeMetadata.resolveKey(key(i));
            assertEquals(pgeMetadata.getMetadata(resolved), pgeMetadata.getMetadata(key(i)),
                key(i) + " does not read through to " + resolved);
          }
        });
  }

  /**
   * The documented meaning of the {@link Type} arguments to
   * {@link PgeMetadata#getAllMetadata(String, Type...)}: the layers are
   * consulted in the order given and the first one holding the key wins. A
   * caller who asks for STATIC before LOCAL is asking for the configured value
   * even after the PGE has written one of its own.
   */
  @HegelTest
  void getAllMetadataReturnsTheFirstTypeThatHoldsTheKey(TestCase tc) {
    Map<Type, Boolean> present = drawPresence(tc);
    List<Type> queryOrder =
        tc.draw(lists(sampledFrom(Type.values())).minSize(1).maxSize(3), "queryOrder");
    PgeMetadata pgeMetadata = populate(present);

    String expected = null;
    for (Type type : queryOrder) {
      if (present.get(type)) {
        expected = VALUE_BY_TYPE.get(type);
        break;
      }
    }

    assertEquals(expected, pgeMetadata.getMetadata(SHARED_KEY, queryOrder.toArray(new Type[0])));
  }

  /**
   * The two ways of reading the same store must not disagree. Both
   * {@link PgeMetadata#asMetadata(Type...)} and
   * {@link PgeMetadata#getAllMetadata(String, Type...)} take the same list of
   * layers and both document it as a precedence order — {@code asMetadata}
   * spells this out with a worked example, that {@code asMetadata(LOCAL,
   * STATIC)} lets LOCAL trump STATIC — so a key read out of the combined
   * metadata has to carry the value a direct query for that key would return.
   */
  @HegelTest
  void asMetadataAgreesWithGetAllMetadataOnPrecedence(TestCase tc) {
    Map<Type, Boolean> present = drawPresence(tc);
    List<Type> order =
        tc.draw(lists(sampledFrom(Type.values())).minSize(1).maxSize(3), "order");
    PgeMetadata pgeMetadata = populate(present);
    Type[] types = order.toArray(new Type[0]);

    Metadata combined = pgeMetadata.asMetadata(types);

    assertEquals(pgeMetadata.getMetadata(SHARED_KEY, types), combined.getMetadata(SHARED_KEY),
        "combined metadata disagrees with a direct query for " + order);
  }

  /**
   * Committing marked keys is the step that hands a PGE's results back to the
   * workflow, and it runs over whatever the caller marked earlier. Marking
   * every LOCAL key, then linking one of those names at a key that lives in
   * STATIC metadata, is three documented calls in a row; the commit that
   * follows has to survive them and leave nothing marked behind.
   */
  @HegelTest
  void committingMarkedKeysMovesThemIntoDynamicMetadata(TestCase tc) {
    int keyCount = tc.draw(integers().min(1).max(4), "keyCount");
    int relinked = tc.draw(integers().min(0).max(keyCount), "relinked");

    Metadata staticMetadata = new Metadata();
    staticMetadata.replaceMetadata(STATIC_ONLY_KEY, VALUE_BY_TYPE.get(Type.STATIC));
    PgeMetadata pgeMetadata = new PgeMetadata(staticMetadata, new Metadata());
    for (int i = 0; i < keyCount; i++) {
      pgeMetadata.replaceMetadata(key(i), "value" + i);
    }
    pgeMetadata.markAsDynamicMetadataKey();
    for (int i = 0; i < relinked; i++) {
      pgeMetadata.linkKey(key(i), STATIC_ONLY_KEY);
    }

    pgeMetadata.commitMarkedDynamicMetadataKeys();

    assertTrue(pgeMetadata.getMarkedAsDynamicMetadataKeys().isEmpty(),
        "keys left marked after a commit: " + pgeMetadata.getMarkedAsDynamicMetadataKeys());
    for (int i = relinked; i < keyCount; i++) {
      assertEquals("value" + i, pgeMetadata.getMetadata(key(i)),
          key(i) + " lost its value when it was committed");
    }
  }

  /** Which of the three layers hold {@link #SHARED_KEY} for this test case. */
  private static Map<Type, Boolean> drawPresence(TestCase tc) {
    Map<Type, Boolean> present = new EnumMap<>(Type.class);
    present.put(Type.STATIC, tc.draw(booleans(), "inStatic"));
    present.put(Type.DYNAMIC, tc.draw(booleans(), "inDynamic"));
    present.put(Type.LOCAL, tc.draw(booleans(), "inLocal"));
    return present;
  }

  /**
   * A {@link PgeMetadata} holding a distinguishable value for
   * {@link #SHARED_KEY} in each layer that is meant to hold it.
   */
  private static PgeMetadata populate(Map<Type, Boolean> present) {
    Metadata staticMetadata = new Metadata();
    if (present.get(Type.STATIC)) {
      staticMetadata.replaceMetadata(SHARED_KEY, VALUE_BY_TYPE.get(Type.STATIC));
    }
    Metadata dynamicMetadata = new Metadata();
    if (present.get(Type.DYNAMIC)) {
      dynamicMetadata.replaceMetadata(SHARED_KEY, VALUE_BY_TYPE.get(Type.DYNAMIC));
    }
    PgeMetadata pgeMetadata = new PgeMetadata(staticMetadata, dynamicMetadata);
    if (present.get(Type.LOCAL)) {
      pgeMetadata.replaceMetadata(SHARED_KEY, VALUE_BY_TYPE.get(Type.LOCAL));
    }
    return pgeMetadata;
  }
}
