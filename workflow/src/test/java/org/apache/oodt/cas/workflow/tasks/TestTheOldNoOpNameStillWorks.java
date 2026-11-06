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
package org.apache.oodt.cas.workflow.tasks;

//JDK imports
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

//OODT imports
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;

//JUnit imports
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The old name has to keep working, and new definitions have to use the new one.
 *
 * <p>{@code NoOpTask} is more exposed than {@code BranchRedirector} was. All
 * three workflow repositories generate a global-conditions task and persist
 * this class by name into the definition, so the old name is written into
 * catalogs that outlive an upgrade — not only into policy somebody can edit.
 * Both halves therefore matter: the deprecated name still loads, and the name
 * going into new definitions is the moved one.</p>
 *
 * <p>Policy naming the old package is exercised separately and more
 * convincingly by {@code wengine-e2e/e2e-subworkflow.xml}, which ten tests run
 * through the engine. What is checked here is the part reflection can see.</p>
 */
public class TestTheOldNoOpNameStillWorks {

    private static final String OLD =
        "org.apache.oodt.cas.workflow.examples.NoOpTask";

    // ------------------------------------------- the old name still loads ---

    @Test
    public void testTheOldNameStillLoads() throws Exception {
        Object task = Class.forName(OLD).getDeclaredConstructor().newInstance();
        assertTrue("a stored task definition naming " + OLD + " would fail at "
            + "load time", task instanceof WorkflowTaskInstance);
    }

    @Test
    public void testItIsTheSameBehaviourNotACopy() throws Exception {
        Object task = Class.forName(OLD).getDeclaredConstructor().newInstance();
        assertTrue("the old name should resolve to the moved class, so there "
            + "is one implementation rather than two that can drift",
            task instanceof NoOpTask);
    }

    @Test
    public void testItIsMarkedDeprecated() throws Exception {
        assertTrue("the old name should tell a compiler it has moved",
            Class.forName(OLD).isAnnotationPresent(Deprecated.class));
    }

    /** Still a no-op: running it must do nothing and raise nothing. */
    @Test
    public void testTheOldNameStillDoesNothing() throws Exception {
        WorkflowTaskInstance task = (WorkflowTaskInstance)
            Class.forName(OLD).getDeclaredConstructor().newInstance();
        task.run(null, null);
    }

    // --------------------------------- new definitions use the new name ---

    @Test
    public void testTheNameWrittenIntoNewDefinitionsIsTheMovedOne() {
        assertEquals("org.apache.oodt.cas.workflow.tasks.NoOpTask",
            NoOpTask.class.getName());
    }

    /**
     * The repositories import the moved class, not the deprecated shim.
     *
     * <p>They name it as {@code NoOpTask.class.getName()}, so which package
     * they import decides what every generated definition from here on says.
     * Importing the shim would compile, pass every behavioural test, and write
     * a deprecated name into catalogs — a change nothing else here would
     * notice.</p>
     *
     * <p>The sources are found from the compiled class rather than from a
     * hardcoded path, so this cannot quietly end up reading a file that is no
     * longer the subject, and it refuses to pass without having read any.</p>
     */
    @Test
    public void testTheRepositoriesImportTheMovedClass() throws IOException {
        File repositories = new File(moduleRoot(),
            "src/main/java/org/apache/oodt/cas/workflow/repository");
        assertTrue("could not find the repository sources at " + repositories
            + "; this test proved nothing", repositories.isDirectory());

        List<String> wrong = new ArrayList<String>();
        int named = 0;
        File[] files = repositories.listFiles();
        assertTrue("no repository sources were listed; this test proved nothing",
            files != null && files.length > 0);
        for (File f : files) {
            if (!f.getName().endsWith(".java")) {
                continue;
            }
            String source = new String(Files.readAllBytes(f.toPath()),
                Charset.forName("UTF-8"));
            if (!source.contains("NoOpTask")) {
                continue;
            }
            named++;
            if (source.contains("import " + OLD + ";")) {
                wrong.add(f.getName());
            }
        }
        assertEquals("all three repositories generate a global-conditions task "
            + "naming NoOpTask; finding a different number means this test is "
            + "no longer looking at what it was written for", 3, named);
        assertTrue("these would persist the deprecated name into every "
            + "generated task definition: " + wrong, wrong.isEmpty());
    }

    @Test
    public void testTheMovedClassIsNotItselfDeprecated() {
        assertFalse("the class code should be moving to must not be marked "
            + "deprecated", NoOpTask.class.isAnnotationPresent(Deprecated.class));
    }

    /** The module directory, from wherever the test classes were compiled to. */
    private static File moduleRoot() {
        File classes = new File(NoOpTask.class.getProtectionDomain()
            .getCodeSource().getLocation().getPath());
        // .../workflow/target/classes -> .../workflow
        return classes.getParentFile().getParentFile();
    }
}
