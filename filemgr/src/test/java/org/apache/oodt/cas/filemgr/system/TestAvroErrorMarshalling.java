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
package org.apache.oodt.cas.filemgr.system;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.util.Properties;

import org.apache.oodt.cas.filemgr.structs.avrotypes.OodtError;
import org.apache.oodt.cas.filemgr.structs.avrotypes.OodtFailureKind;
import org.apache.oodt.cas.filemgr.util.RpcCommunicationFactory;

import junit.framework.TestCase;

/**
 * A failure on the server has to reach the caller as the failure, not as a
 * complaint about serialization.
 *
 * Responder hands the caught exception straight to writeError, and
 * SpecificResponder writes it unchanged. These protocols declare no error
 * type, so the error schema is the implicit ["string"] and an
 * AvroRemoteException matches nothing in it, which made Avro fail while
 * reporting the failure:
 *
 *   AvroRuntimeException: Unknown datum type org.apache.avro.AvroRemoteException: ...
 */
public class TestAvroErrorMarshalling extends TestCase {

    private static final int FM_PORT = 50011;

    private FileManagerServer fm;
    private final Properties initialProperties = new Properties(System.getProperties());

    protected void setUp() throws Exception {
        Properties properties = new Properties(System.getProperties());

        URL loggingUrl = getClass().getResource("/test.logging.properties");
        properties.setProperty("java.util.logging.config.file",
                new File(loggingUrl.getFile()).getAbsolutePath());
        URL fmProps = getClass().getResource("/filemgr.properties");
        properties.load(new FileInputStream(new File(fmProps.getFile())));

        URL ingestUrl = getClass().getResource("/ingest");
        String luceneCatLoc = new File(ingestUrl.getFile()).getCanonicalPath() + "/cat";
        properties.setProperty("filemgr.catalog.factory",
                "org.apache.oodt.cas.filemgr.catalog.LuceneCatalogFactory");
        properties.setProperty("org.apache.oodt.cas.filemgr.catalog.lucene.idxPath",
                luceneCatLoc);

        URL fmpolicyUrl = getClass().getResource("/ingest/fmpolicy");
        properties.setProperty("org.apache.oodt.cas.filemgr.repositorymgr.dirs",
                "file://" + new File(fmpolicyUrl.getFile()).getCanonicalPath());
        properties.setProperty("org.apache.oodt.cas.filemgr.validation.dirs",
                "file://" + new File(fmpolicyUrl.getFile()).getAbsolutePath());

        URL mimeTypesUrl = getClass().getResource("/mime-types.xml");
        properties.setProperty("org.apache.oodt.cas.filemgr.mime.type.repository",
                new File(mimeTypesUrl.getFile()).getAbsolutePath());

        System.setProperties(properties);

        fm = RpcCommunicationFactory.createServer(FM_PORT);
        fm.startUp();
    }

    protected void tearDown() throws Exception {
        if (fm != null) {
            fm.shutdown();
            fm = null;
        }
        System.setProperties(initialProperties);
    }

    /**
     * The server fails reading a file that is not there. What comes back must
     * describe that, not describe Avro being unable to write it.
     */
    public void testServerFailureReachesTheCaller() throws Exception {
        FileManagerClient fmc =
                RpcCommunicationFactory.createClient(new URL("http://localhost:" + FM_PORT));
        try {
            fmc.retrieveFile("/definitely/not/a/real/path/nope.bin", 0, 16);
            fail("expected the server to fail reading a path that does not exist");
        } catch (Exception e) {
            String rendered = String.valueOf(e.getMessage());

            assertFalse("the caller got a serialization complaint instead of the fault: "
                            + rendered,
                    rendered.contains("Unknown datum type"));

            assertTrue("the server's message did not survive: " + rendered,
                    rendered.contains("/definitely/not/a/real/path/nope.bin"));
        } finally {
            fmc.close();
        }
    }

    /**
     * The failure now arrives as the declared error, so a caller can branch on
     * the kind instead of reading the message, and still see the exact class.
     */
    public void testFailureArrivesTypedAndClassified() throws Exception {
        FileManagerClient fmc =
                RpcCommunicationFactory.createClient(new URL("http://localhost:" + FM_PORT));
        try {
            fmc.retrieveFile("/definitely/not/a/real/path/nope.bin", 0, 16);
            fail("expected the server to fail reading a path that does not exist");
        } catch (Exception e) {
            OodtError error = null;
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof OodtError) {
                    error = (OodtError) t;
                    break;
                }
            }
            assertNotNull("the failure did not arrive as the declared error: " + e, error);

            assertEquals("a failed read is a transfer failure",
                    OodtFailureKind.TRANSFER, error.getKind());
            assertEquals("the exact exception class should still be visible",
                    "org.apache.oodt.cas.filemgr.structs.exceptions.DataTransferException",
                    error.getType());
            assertTrue("the server's message did not survive: " + error.getDetail(),
                    String.valueOf(error.getDetail())
                            .contains("/definitely/not/a/real/path/nope.bin"));
        } finally {
            fmc.close();
        }
    }
}
