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
package org.apache.oodt.cas.workflow.util;

//JDK imports
import java.io.File;

//OODT imports
import org.apache.oodt.cas.workflow.repository.PackagedWorkflowRepositoryFactory;

//JUnit imports
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A configured path with a name left in it is not a path.
 *
 * <p>On 2026-11-04 a DRAT audit could not start. Its workflow manager was
 * configured with {@code [DRAT_HOME]/workflow/policy-wengine} and what the
 * operator saw was {@code Null engine} followed by {@code Unable to create
 * server} — naming neither the property, nor the unset name, nor the fact that
 * a path had gone to the filesystem verbatim. It was diagnosed first as a
 * service readiness race.</p>
 *
 * <p>Two separate faults produced that, and both are covered here: the wengine
 * repository factory never substituted environment names at all, where the W1
 * factory always had; and nothing anywhere refused a value that still carried
 * a token.</p>
 */
public class TestConfiguredPaths {

    private static final String PROPERTY =
        "org.apache.oodt.cas.workflow.wengine.packagedRepo.dir.path";

    // ------------------------------------------------------- substituting ---

    /**
     * A name that is set gets substituted.
     *
     * <p>Uses HOME because PathUtils reads the real environment through
     * EnvUtilities, and a java process cannot add to its own environment --
     * System.setProperty sets a system property, which is a different thing
     * and is not consulted. My first version of this test did exactly that and
     * failed, which is a better outcome than it passing for the wrong
     * reason.</p>
     */
    @Test
    public void testASetNameIsSubstituted() {
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            return;  // nothing to assert against on a host without HOME
        }
        assertEquals(home + "/policy",
            ConfiguredPaths.resolve(PROPERTY, "[HOME]/policy"));
    }

    @Test
    public void testAPathWithNoNamesIsUntouched() {
        assertEquals("/opt/drat/workflow/policy-wengine",
            ConfiguredPaths.resolve(PROPERTY, "/opt/drat/workflow/policy-wengine"));
    }

    @Test
    public void testAnUnsetPropertyStaysNull() {
        assertEquals(null, ConfiguredPaths.resolve(PROPERTY, null));
    }

    // ----------------------------------------------------------- refusing ---

    /** The incident, as a test. */
    @Test
    public void testAnUnsetNameIsRefusedAndNamed() {
        try {
            ConfiguredPaths.resolve(PROPERTY,
                "[DRAT_HOME]/workflow/policy-wengine");
            fail("a path still containing [DRAT_HOME] was accepted");
        } catch (IllegalStateException expected) {
            String m = expected.getMessage();
            assertTrue("it should name the unset variable: " + m,
                m.contains("DRAT_HOME"));
            assertTrue("it should name the property to go and look at: " + m,
                m.contains(PROPERTY));
            assertTrue("it should say what to do: " + m,
                m.contains("Set DRAT_HOME"));
        }
    }

    @Test
    public void testOnlyOneOfSeveralNamesNeedSurviveToBeRefused() {
        if (System.getenv("HOME") == null) {
            return;
        }
        try {
            ConfiguredPaths.resolve(PROPERTY,
                "[HOME]/[TEST_GUARD_DEFINITELY_UNSET]/policy");
            fail("a half-resolved path was accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage()
                .contains("TEST_GUARD_DEFINITELY_UNSET"));
        }
    }

    /**
     * A bracket is not always a token.
     *
     * <p>A check that refuses any bracket would refuse somebody's directory
     * name, and a check people route around is worse than none.</p>
     */
    @Test
    public void testADeliberateBracketIsNotAToken() {
        assertFalse(ConfiguredPaths.isUnsubstituted("/archive/[2026] reports"));
        assertFalse(ConfiguredPaths.isUnsubstituted("/archive/[draft notes]"));
        assertTrue(ConfiguredPaths.isUnsubstituted("/archive/[DRAT_HOME]"));
        assertTrue(ConfiguredPaths.isUnsubstituted("[OODT_HOME]/policy"));
    }

    @Test
    public void testRefuseWithoutSubstitutingAlsoNamesIt() {
        try {
            ConfiguredPaths.refuseUnsubstituted("some.property",
                "[OODT_HOME]/policy");
            fail("accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("OODT_HOME"));
            assertTrue(expected.getMessage().contains("some.property"));
        }
    }

    // --------------------------------------- the factory that never did it ---

    /**
     * The wengine factory now substitutes, which it never did.
     *
     * <p>This is the asymmetry that made W2 fail where W1 worked:
     * XMLWorkflowRepositoryFactory ran its directory list through PathUtils and
     * this one did not, so an ordinary {@code [DRAT_HOME]/...} policy path
     * could not resolve however the environment was set.</p>
     */
    @Test
    public void testTheWengineFactoryResolvesASetName() throws Exception {
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            return;
        }
        File dir = new File(home, "wengine-policy-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        // Written the way a deployment writes it, with the root as a name.
        // Before the fix this reached File.isDirectory() verbatim, which is
        // false, and the constructor threw.
        System.setProperty(PROPERTY, "[HOME]/" + dir.getName());
        try {
            new PackagedWorkflowRepositoryFactory();
        } finally {
            System.clearProperty(PROPERTY);
            dir.delete();
        }
    }

    @Test
    public void testTheWengineFactorySaysWhichNameWasUnset() {
        System.setProperty(PROPERTY,
            "[DRAT_HOME]/workflow/policy-wengine");
        try {
            new PackagedWorkflowRepositoryFactory();
            fail("an unsubstituted policy path was accepted");
        } catch (InstantiationException expected) {
            String m = expected.getMessage();
            assertTrue("the message should name DRAT_HOME rather than only "
                + "showing the path: " + m, m.contains("DRAT_HOME"));
            assertTrue("and the property: " + m,
                m.contains("packagedRepo.dir.path"));
        } finally {
            System.clearProperty(
                PROPERTY);
        }
    }
}
