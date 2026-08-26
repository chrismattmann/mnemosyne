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

package org.apache.oodt.cas.resource.monitor.ganglia.loadcalc;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.doubles;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.apache.oodt.cas.resource.monitor.ganglia.GangliaMetKeys.CPU_NUM;
import static org.apache.oodt.cas.resource.monitor.ganglia.GangliaMetKeys.LOAD_FIFTEEN;
import static org.apache.oodt.cas.resource.monitor.ganglia.GangliaMetKeys.LOAD_FIVE;
import static org.apache.oodt.cas.resource.monitor.ganglia.GangliaMetKeys.LOAD_ONE;
import static org.apache.oodt.cas.resource.monitor.ganglia.GangliaMetKeys.TMAX;
import static org.apache.oodt.cas.resource.monitor.ganglia.GangliaMetKeys.TN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Properties of the load figure {@link WeightedAverageLoadCalc} reports for a
 * Ganglia node.
 *
 * <p>The class had no unit tests. The number it returns is subtracted from a
 * node's CPU count to decide how much room that node has, so it has to stay
 * inside the range a CPU count can be, and it has to be the same number
 * wherever the resource manager happens to be running.
 *
 * <p>Metrics are drawn in the shape gmond reports them: whole CPU counts, and
 * one-, five- and fifteen-minute load averages that are non-negative and can
 * exceed the CPU count on a busy machine.
 */
class WeightedAverageLoadCalcPropertyTest {

  private static Map<String, String> metrics(
      TestCase tc, double numCpus, boolean offline) {
    double tmax = 60;
    double tn = offline ? tmax * 4 + 1 : tc.draw(doubles().min(0).max(tmax * 4), "tn");

    Map<String, String> met = new HashMap<>();
    met.put(TN, String.valueOf(tn));
    met.put(TMAX, String.valueOf(tmax));
    met.put(CPU_NUM, String.valueOf(numCpus));
    met.put(LOAD_ONE, String.valueOf(tc.draw(doubles().min(0).max(64), "loadOne")));
    met.put(LOAD_FIVE, String.valueOf(tc.draw(doubles().min(0).max(64), "loadFive")));
    met.put(LOAD_FIFTEEN, String.valueOf(tc.draw(doubles().min(0).max(64), "loadFifteen")));
    return met;
  }

  private static WeightedAverageLoadCalc calc(TestCase tc) {
    return new WeightedAverageLoadCalc(
        tc.draw(doubles().min(0.1).max(10), "weightOne"),
        tc.draw(doubles().min(0.1).max(10), "weightFive"),
        tc.draw(doubles().min(0.1).max(10), "weightFifteen"));
  }

  /**
   * A node's load never exceeds the number of CPUs it has and is never
   * negative. The Ganglia monitor reports {@code numCpus - load} as the room
   * on the node; outside this range that figure is either negative or larger
   * than the machine.
   */
  @HegelTest
  void loadStaysBetweenZeroAndTheCpuCount(TestCase tc) {
    double numCpus = tc.draw(integers().min(1).max(64), "numCpus");
    Map<String, String> met = metrics(tc, numCpus, tc.draw(booleans(), "offline"));

    double load = calc(tc).calculateLoad(met);

    assertTrue(load >= 0, "negative load: " + load);
    assertTrue(load <= numCpus, "load " + load + " exceeds " + numCpus + " CPUs");
  }

  /** A node Ganglia has not heard from is treated as fully occupied. */
  @HegelTest
  void anOfflineNodeIsFullyLoaded(TestCase tc) {
    double numCpus = tc.draw(integers().min(1).max(64), "numCpus");

    assertEquals(numCpus, calc(tc).calculateLoad(metrics(tc, numCpus, true)));
  }

  /**
   * The load is the same figure whatever locale the resource manager happens
   * to run under. The number is only ever consumed as a number — it is
   * subtracted from a CPU count — so a French or German host must schedule
   * exactly as an English one does.
   */
  @HegelTest
  void theLoadDoesNotDependOnTheHostLocale(TestCase tc) {
    double numCpus = tc.draw(integers().min(1).max(64), "numCpus");
    Map<String, String> met = metrics(tc, numCpus, false);
    double weightOne = tc.draw(doubles().min(0.1).max(10), "weightOne");
    double weightFive = tc.draw(doubles().min(0.1).max(10), "weightFive");
    double weightFifteen = tc.draw(doubles().min(0.1).max(10), "weightFifteen");
    Locale other =
        tc.draw(
            sampledFrom(Arrays.asList(Locale.GERMANY, Locale.FRANCE, Locale.ITALY, Locale.US)),
            "locale");

    Locale original = Locale.getDefault();
    double reference;
    double underOtherLocale;
    try {
      Locale.setDefault(Locale.US);
      reference =
          new WeightedAverageLoadCalc(weightOne, weightFive, weightFifteen).calculateLoad(met);

      Locale.setDefault(other);
      underOtherLocale =
          new WeightedAverageLoadCalc(weightOne, weightFive, weightFifteen).calculateLoad(met);
    } finally {
      Locale.setDefault(original);
    }

    assertEquals(reference, underOtherLocale, "the load changed with the host locale: " + other);
  }
}
