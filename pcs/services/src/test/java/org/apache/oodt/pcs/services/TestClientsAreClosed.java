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
package org.apache.oodt.pcs.services;

//JDK imports
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//JUnit imports
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An endpoint that opens a client has to close it.
 *
 * <p>Every client in this module holds an Avro RPC connection, and CXF
 * instantiates a class listed in {@code jaxrs.serviceClasses} once per request,
 * so a client left unclosed is one socket leaked per call. On 2026-10-02 a
 * dashboard polling {@code /resource/overview} every 8 seconds left <b>16,314
 * established sockets</b> from Tomcat to the Resource Manager after about 36
 * hours — every one of the host's 16,384 ephemeral ports.</p>
 *
 * <p>The machine then could not open a TCP connection to anything: not the
 * internet, not another node on the LAN. ICMP still worked, because ping needs
 * no port, so it looked like a routing fault for a while. Tomcat's own
 * {@code shutdown.sh} could not run either — it needs a socket to reach the
 * shutdown port — so the process had to be killed.</p>
 *
 * <p>Nothing caught it because nothing was looking. Most endpoints in this
 * module already close their clients: {@code WorkflowResource} calls
 * {@code closeQuietly} in a finally on all seven of its methods,
 * {@code CatalogResource} closes its {@code FileManagerUtils},
 * {@code HealthResource} uses try-with-resources. Two did not, and they were
 * indistinguishable from the rest by inspection.</p>
 *
 * <p>This is a lint rather than a behavioural test. Exercising an endpoint for
 * real needs a CXF container and a live Resource Manager, and a test that heavy
 * would not be run; this reads the source and is honest about what it checks.</p>
 */
public class TestClientsAreClosed {

    /**
     * Opening a connection that has to be given back.
     *
     * <p>Not preceded by {@code return}: a helper whose whole body is
     * {@code return RpcCommunicationFactory.createClient(...)} hands the client
     * to its caller, and the caller is where the close belongs. Both
     * CatalogResource and WorkflowResource have one of those, and reporting
     * them would have made this check something to be switched off.</p>
     */
    private static final Pattern ACQUIRES = Pattern.compile(
        "(?<!return )(?:getResourceManagerClient\\s*\\(|=\\s*wm\\s*\\(\\)|"
        + "RpcCommunicationFactory\\.createClient\\s*\\(|"
        + "new FileManagerUtils\\s*\\(|=\\s*open\\s*\\(\\))");

    /** Giving it back, in any of the three forms this module uses. */
    private static final Pattern RELEASES = Pattern.compile(
        "closeQuietly\\s*\\(|\\.close\\s*\\(\\)|try\\s*\\(");

    /** The start of a JAX-RS endpoint. */
    private static final Pattern ENDPOINT = Pattern.compile(
        "@(GET|POST|PUT|DELETE)\\b");

    private static File sourceDir() {
        File here = new File(System.getProperty("user.dir"));
        File dir = new File(here,
            "src/main/java/org/apache/oodt/pcs/services");
        if (dir.isDirectory()) {
            return dir;
        }
        // run from the reactor root rather than the module
        dir = new File(here,
            "pcs/services/src/main/java/org/apache/oodt/pcs/services");
        return dir.isDirectory() ? dir : null;
    }

    /**
     * Methods that acquire a client without releasing one.
     *
     * <p>Crude on purpose: a method is the text from one endpoint annotation to
     * the next. It cannot tell a nested class from a method body, which is why
     * the counts are per-method rather than exact, and it is enough to catch a
     * missing close without pretending to parse Java.</p>
     */
    static List<String> leaksIn(String name, String source) {
        List<String> leaks = new ArrayList<String>();
        String[] chunks = ENDPOINT.split(source);
        // chunks[0] is everything before the first endpoint: fields, imports,
        // constructors. Acquisitions there are per-instance, which for a
        // per-request resource is the same leak -- so it is checked too.
        for (int i = 0; i < chunks.length; i++) {
            String chunk = chunks[i];
            if (!ACQUIRES.matcher(chunk).find()) {
                continue;
            }
            if (RELEASES.matcher(chunk).find()) {
                continue;
            }
            String where = i == 0 ? "a field or constructor" : signature(chunk);
            leaks.add(name + ": " + where);
        }
        return leaks;
    }

    private static String signature(String chunk) {
        Matcher m = Pattern.compile(
            "public\\s+\\S+\\s+([a-zA-Z0-9_]+)\\s*\\(").matcher(chunk);
        return m.find() ? m.group(1) + "()" : "an endpoint";
    }

    // ------------------------------------------------- the matcher works ---

    @Test
    public void testItFindsTheLeakThatTookTheMachineOut() {
        String before =
            "@GET public String overview() {\n"
            + "  ResourceManagerClient client ="
            + " ResourceManagerFactory.getResourceManagerClient(url);\n"
            + "  return json(client);\n"
            + "}\n";
        assertEquals(1, leaksIn("Sample", before).size());
    }

    @Test
    public void testEachOfTheThreeReleaseFormsCounts() {
        String closeQuietly =
            "@GET public String a() { WorkflowManagerClient c = wm();"
            + " try { return x(c); } finally { closeQuietly(c); } }";
        String plainClose =
            "@GET public String b() { FileManagerUtils fm ="
            + " new FileManagerUtils(url); try { return x(fm); }"
            + " finally { fm.close(); } }";
        String tryWith =
            "@GET public String c() { try (FileManagerUtils fm ="
            + " new FileManagerUtils(url)) { return x(fm); } }";
        for (String ok : new String[] {closeQuietly, plainClose, tryWith}) {
            assertTrue("this should not be reported: " + ok,
                leaksIn("Sample", ok).isEmpty());
        }
    }

    @Test
    public void testAClientHeldInAFieldIsReported() {
        // PedigreeResource's shape: acquired in the constructor, held as a
        // field, never closed. One connection per request, because CXF builds
        // the resource per request.
        String fieldHeld =
            "public class R { private FileManagerUtils fm;\n"
            + "  public R() { this.fm = new FileManagerUtils(url); }\n"
            + "@GET public String a() { return x(this.fm); } }";
        List<String> leaks = leaksIn("R", fieldHeld);
        assertEquals(1, leaks.size());
        assertTrue(leaks.get(0).contains("field or constructor"));
    }

    @Test
    public void testAFactoryHelperIsNotALeak() {
        // The shape both CatalogResource and WorkflowResource use: a helper
        // that returns a client for the caller to close.
        String factory =
            "private WorkflowManagerClient wm() throws MalformedURLException {\n"
            + "  return RpcCommunicationFactory.createClient(url);\n"
            + "}\n"
            + "@GET public String a() { WorkflowManagerClient c = wm();"
            + " try { return x(c); } finally { closeQuietly(c); } }";
        assertTrue("a helper that returns the client is not the leak; the "
            + "caller owns it", leaksIn("Sample", factory).isEmpty());
    }

    @Test
    public void testAFileWithNoClientsIsNotReported() {
        assertTrue(leaksIn("Sample",
            "@GET public String ping() { return \"ok\"; }").isEmpty());
    }

    // ------------------------------------------------- the module is clean ---

    @Test
    public void testEveryEndpointInThisModuleClosesWhatItOpens()
            throws IOException {
        File dir = sourceDir();
        assertTrue("could not find the resource sources from "
            + System.getProperty("user.dir") + "; this test proved nothing",
            dir != null);
        File[] files = dir.listFiles();
        assertTrue("no sources found; this test proved nothing",
            files != null && files.length > 0);

        List<String> leaks = new ArrayList<String>();
        int checked = 0;
        for (File f : files) {
            if (!f.getName().endsWith("Resource.java")) {
                continue;
            }
            checked++;
            String source = new String(Files.readAllBytes(f.toPath()),
                Charset.forName("UTF-8"));
            leaks.addAll(leaksIn(f.getName(), source));
        }
        assertTrue("no *Resource.java files were read; this test proved nothing",
            checked > 0);
        assertTrue("these open a client and never close it, which leaks one "
            + "socket per request: " + leaks, leaks.isEmpty());
    }
}
