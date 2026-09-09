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

import java.io.File;
import java.util.Arrays;
import java.util.Vector;

import junit.framework.TestCase;

/**
 * A PGE whose science command fails must not report success.
 *
 * <p>
 * The generated script ran every command whatever the one before it did, and
 * exited with the status of the last one. PGETaskInstance checks that status
 * and is right to, but by then it is the status of whatever trailed the real
 * work -- a copy, a heartbeat, a log line. So a stage whose science command
 * died still exited zero, the task was marked Success, nothing was ingested,
 * and the run continued on no data.
 * </p>
 *
 * <p>
 * Seen on a translate stage where every one of 144 jobs failed with the same
 * error and every one of 144 instances went green. The pipeline looked healthy
 * for as long as anyone cared to watch it.
 * </p>
 */
public class TestScriptFileStopsOnError extends TestCase {

    private File script;

    @Override
    protected void tearDown() throws Exception {
        if (script != null && script.exists()) {
            script.delete();
        }
    }

    /**
     * The bug itself, run rather than inspected: a failing command followed by
     * a succeeding one used to exit zero.
     */
    public void testAFailingCommandFailsTheScript() throws Exception {
        int status = run("sh", Arrays.asList(
                "false",
                "true"));
        assertTrue("a script whose command failed must not exit zero",
                status != 0);
    }

    /** And the commands after the failure do not run. */
    public void testNothingAfterTheFailureRuns() throws Exception {
        File marker = File.createTempFile("scriptfile-marker", ".txt");
        assertTrue(marker.delete());

        run("sh", Arrays.asList(
                "false",
                "touch " + marker.getAbsolutePath()));

        assertFalse("the command after the failure ran anyway",
                marker.exists());
        marker.delete();
    }

    /** A script that works still works, and still runs everything in it. */
    public void testAWorkingScriptIsUnaffected() throws Exception {
        File marker = File.createTempFile("scriptfile-marker", ".txt");
        assertTrue(marker.delete());

        int status = run("sh", Arrays.asList(
                "true",
                "touch " + marker.getAbsolutePath()));

        assertEquals(0, status);
        assertTrue("the script stopped short of its last command",
                marker.exists());
        marker.delete();
    }

    public void testTheDirectiveIsWrittenForKnownShells() throws Exception {
        for (String shell : new String[] {
                "sh", "bash", "/bin/sh", "/bin/bash", "/usr/bin/zsh", "ksh",
                "dash"}) {
            assertTrue(shell + " should stop on error",
                    ScriptFile.stopsOnError(shell));
        }
    }

    /**
     * The limit of the change. A shell whose name we do not know is a shell
     * whose syntax we do not know, and writing "set -e" into it would be a
     * guess at best and a syntax error at worst.
     */
    public void testAnUnknownInterpreterIsLeftAlone() throws Exception {
        for (String shell : new String[] {
                "python", "/usr/bin/python3", "perl", "node", null, ""}) {
            assertFalse(String.valueOf(shell) + " should be left alone",
                    ScriptFile.stopsOnError(shell));
        }

        ScriptFile sf = new ScriptFile("python");
        sf.setCommands(new Vector<String>(Arrays.asList("print('hi')")));
        assertFalse("set -e must not be written into a python script",
                sf.toString().contains("set -e"));
    }

    public void testTheShebangIsStillFirst() throws Exception {
        ScriptFile sf = new ScriptFile("sh");
        sf.setCommands(new Vector<String>(Arrays.asList("true")));
        String[] lines = sf.toString().split("\n");
        assertEquals("#!sh", lines[0]);
    }

    // ---------------------------------------------------------- helpers ---

    /** Write the script the way the PGE does, then run it and report status. */
    private int run(String shell, java.util.List<String> commands)
            throws Exception {
        ScriptFile sf = new ScriptFile(shell);
        sf.setCommands(new Vector<String>(commands));

        script = File.createTempFile("sciPgeExeScript_test", ".sh");
        sf.writeScriptFile(script.getAbsolutePath());

        ProcessBuilder builder =
                new ProcessBuilder("/bin/sh", script.getAbsolutePath());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        process.getInputStream().close();
        return process.waitFor();
    }
}
