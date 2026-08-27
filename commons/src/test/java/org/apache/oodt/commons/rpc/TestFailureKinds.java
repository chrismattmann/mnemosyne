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
package org.apache.oodt.commons.rpc;

import java.io.IOException;
import java.net.MalformedURLException;

import junit.framework.TestCase;

public class TestFailureKinds extends TestCase {

    /** Classified by simple name, so this need not depend on four modules. */
    public void testSortsAKnownExceptionByItsName() {
        assertEquals("CATALOG", FailureKinds.classify(new CatalogException("nope")));
        assertEquals("MONITOR", FailureKinds.classify(new MonitorException("nope")));
    }

    /** A subclass sorts with its parent rather than falling to UNKNOWN. */
    public void testWalksUpToAKnownParent() {
        assertEquals("IO", FailureKinds.classify(new MalformedURLException("nope")));
        assertEquals("IO", FailureKinds.classify(new IOException("nope")));
    }

    /** The case that will actually happen as the servers grow. */
    public void testAnUnrecognisedExceptionIsUnknownRatherThanAFailure() {
        assertEquals("UNKNOWN", FailureKinds.classify(new IllegalStateException("nope")));
        assertEquals("UNKNOWN", FailureKinds.classify(null));
    }

    /** all() is what the protocol enums are checked against, so it must hold every result. */
    public void testEveryClassificationIsDeclared() {
        Throwable[] cases = new Throwable[] {
                new CatalogException("x"), new MonitorException("x"),
                new IOException("x"), new InterruptedException("x"),
                new IllegalStateException("x") };
        for (Throwable t : cases) {
            String kind = FailureKinds.classify(t);
            assertTrue(kind + " is returned but not declared in all()",
                    FailureKinds.all().contains(kind));
        }
    }

    /** NOT_FOUND is declared ahead of use; nothing classifies to it yet. */
    public void testNotFoundIsDeclaredButUnused() {
        assertTrue(FailureKinds.all().contains("NOT_FOUND"));
    }

    private static class CatalogException extends Exception {
        CatalogException(String m) { super(m); }
    }

    private static class MonitorException extends Exception {
        MonitorException(String m) { super(m); }
    }
}
