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

package org.apache.oodt.cas.workflow.structs;

import org.apache.oodt.cas.metadata.Metadata;

import org.junit.Test;

import java.util.Map;
import java.util.Vector;

import static org.junit.Assert.*;

/**
 * The flat view a job hands to the resource manager dropped every dynamic
 * metadata key.
 *
 * It called Metadata.getAllValues(metName), which returns the values of
 * everything *below* a group and excludes the group's own. A flat key has
 * nothing below it, so the vector was always empty, the size guard never
 * fired, and the key was silently omitted -- leaving the note in that method,
 * that dynamic metadata takes precedence over configuration metadata, true
 * only in the sense that it contributed nothing at all.
 */
public class TestTaskJobInputFlatView {

    private static TaskJobInput inputWith(WorkflowTaskConfiguration config,
            Metadata dyn) {
        TaskJobInput in = new TaskJobInput();
        in.setDynMetadata(dyn);
        in.setTaskConfig(config);
        return in;
    }

    /** The counterexample: a single key A=[v0]. */
    @Test
    public void testASingleDynamicKeyReachesTheFlatView() {
        Metadata dyn = new Metadata();
        dyn.addMetadata("A", "v0");

        Map<String, Vector<String>> met =
                inputWith(new WorkflowTaskConfiguration(), dyn).getMetadata();

        assertNotNull("the dynamic key was dropped", met.get("A"));
        assertEquals(1, met.get("A").size());
        assertEquals("v0", met.get("A").get(0));
    }

    /** Every value under a key comes through, not just the first. */
    @Test
    public void testEveryValueOfADynamicKeyReachesTheFlatView() {
        Metadata dyn = new Metadata();
        dyn.addMetadata("A", "v0");
        dyn.addMetadata("A", "v1");

        Map<String, Vector<String>> met =
                inputWith(new WorkflowTaskConfiguration(), dyn).getMetadata();

        assertEquals(2, met.get("A").size());
        assertTrue(met.get("A").contains("v0"));
        assertTrue(met.get("A").contains("v1"));
    }

    /** Configuration still reaches the flat view. */
    @Test
    public void testConfigurationStillReachesTheFlatView() {
        WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
        config.addConfigProperty("FromConfig", "c0");

        Map<String, Vector<String>> met =
                inputWith(config, new Metadata()).getMetadata();

        assertNotNull(met.get("FromConfig"));
        assertEquals("c0", met.get("FromConfig").get(0));
    }

    /**
     * The precedence the method documents: dynamic metadata over
     * configuration. It could not hold while dynamic metadata contributed
     * nothing.
     */
    @Test
    public void testDynamicMetadataTakesPrecedenceOverConfiguration() {
        WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
        config.addConfigProperty("Shared", "from-config");
        Metadata dyn = new Metadata();
        dyn.addMetadata("Shared", "from-metadata");

        Map<String, Vector<String>> met = inputWith(config, dyn).getMetadata();

        assertEquals("from-metadata", met.get("Shared").get(0));
    }

    /** and both are present when they do not collide. */
    @Test
    public void testConfigurationAndMetadataAreBothPresent() {
        WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
        config.addConfigProperty("FromConfig", "c0");
        Metadata dyn = new Metadata();
        dyn.addMetadata("FromMetadata", "m0");

        Map<String, Vector<String>> met = inputWith(config, dyn).getMetadata();

        assertEquals("c0", met.get("FromConfig").get(0));
        assertEquals("m0", met.get("FromMetadata").get(0));
    }
}
