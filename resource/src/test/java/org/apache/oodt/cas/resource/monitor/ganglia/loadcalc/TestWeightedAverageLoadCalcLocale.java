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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * The weighted load was rounded by formatting it with a default-locale
 * NumberFormat and re-parsing it with the locale-independent Double.valueOf.
 * On any comma-decimal locale the formatter emitted "0,333" and the parse
 * threw NumberFormatException, so the Ganglia monitor could not read the load
 * on an ordinary German or French host.
 */
public class TestWeightedAverageLoadCalcLocale {

    private Locale original;

    @Before
    public void rememberLocale() {
        original = Locale.getDefault();
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(original);
    }

    private static Map<String, String> metrics(String one, String five,
            String fifteen, String cpus) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("TN", "0");
        m.put("TMAX", "60");
        m.put("cpu_num", cpus);
        m.put("load_one", one);
        m.put("load_five", five);
        m.put("load_fifteen", fifteen);
        return m;
    }

    /** The shrunk counterexample: de_DE, one CPU, loads 0.0/0.0/1.0. */
    @Test
    public void testTheLoadIsReadableOnACommaDecimalLocale() {
        Locale.setDefault(Locale.GERMANY);

        double load = new WeightedAverageLoadCalc(1.0, 1.0, 1.0)
                .calculateLoad(metrics("0.0", "0.0", "1.0", "1"));

        assertEquals(0.333d, load, 0.0005d);
    }

    @Test
    public void testTheLoadDoesNotDependOnTheHostLocale() {
        Map<String, String> m = metrics("0.0", "0.0", "1.0", "1");

        Locale.setDefault(Locale.US);
        double inUs = new WeightedAverageLoadCalc(1.0, 1.0, 1.0).calculateLoad(m);

        Locale.setDefault(Locale.FRANCE);
        double inFrance = new WeightedAverageLoadCalc(1.0, 1.0, 1.0).calculateLoad(m);

        Locale.setDefault(Locale.GERMANY);
        double inGermany = new WeightedAverageLoadCalc(1.0, 1.0, 1.0).calculateLoad(m);

        assertEquals(inUs, inFrance, 0.0d);
        assertEquals(inUs, inGermany, 0.0d);
    }

    /** Still rounded to three places, which is what the formatter did. */
    @Test
    public void testTheLoadIsStillRoundedToThreePlaces() {
        Locale.setDefault(Locale.US);

        double load = new WeightedAverageLoadCalc(1.0, 1.0, 1.0)
                .calculateLoad(metrics("0.0", "0.0", "1.0", "1"));

        assertEquals(0.333d, load, 0.0d);
    }

    /** An offline node still reports its capacity as its load. */
    @Test
    public void testAnOfflineNodeReportsItsCapacity() {
        Locale.setDefault(Locale.GERMANY);
        Map<String, String> m = metrics("0.0", "0.0", "1.0", "4");
        m.put("TN", "1000");
        m.put("TMAX", "60");

        assertEquals(4.0d, new WeightedAverageLoadCalc(1.0, 1.0, 1.0)
                .calculateLoad(m), 0.0d);
    }
}
