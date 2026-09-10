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

import junit.framework.TestCase;

/**
 * "No answer yet" and "the call failed" need opposite responses, so a caller
 * has to be able to tell them apart.
 *
 * <p>
 * The batch manager could not. It caught the timeout as an ordinary failure,
 * recorded the job as failed and handed the node's capacity back while the job
 * carried on running, so the scheduler placed another on top. One machine
 * reached thirty concurrent tasks against a capacity of eight.
 * </p>
 *
 * <p>
 * The only thing that distinguished a timeout was its message, and branching
 * on message text is how you get a fix that stops working when someone
 * rewords a string.
 * </p>
 */
public class TestRequestTimeoutIsDistinguishable extends TestCase {

    /** A call that takes longer than it is given. */
    public interface Slow {
        String slowly() throws InterruptedException;

        String quickly();

        String broken();
    }

    private static class Impl implements Slow {
        public String slowly() throws InterruptedException {
            Thread.sleep(5000L);
            return "eventually";
        }

        public String quickly() {
            return "at once";
        }

        public String broken() {
            throw new IllegalArgumentException("the call itself failed");
        }
    }

    private Slow bounded(long millis) {
        return RequestTimeout.bound(Slow.class, new Impl(), millis);
    }

    public void testACallThatRunsOutOfTimeIsRecognisable() throws Exception {
        try {
            bounded(100L).slowly();
            fail("a call that outlasted its bound should have thrown");
        } catch (Throwable thrown) {
            assertTrue("a timeout must be recognisable without reading its "
                    + "message, got " + thrown.getClass().getName(),
                    RequestTimeout.isExpired(thrown));
        }
    }

    public void testACallThatFailedIsNotATimeout() throws Exception {
        try {
            bounded(5000L).broken();
            fail("should have thrown");
        } catch (Throwable thrown) {
            assertFalse("a genuine failure must not read as a timeout",
                    RequestTimeout.isExpired(thrown));
            assertTrue(thrown instanceof IllegalArgumentException);
        }
    }

    public void testACallWithinItsBoundIsUntouched() throws Exception {
        assertEquals("at once", bounded(5000L).quickly());
    }

    /** Wrapped by a layer in between, it is still a timeout. */
    public void testItIsFoundThroughACauseChain() throws Exception {
        Throwable expired = null;
        try {
            bounded(100L).slowly();
        } catch (Throwable thrown) {
            expired = thrown;
        }
        assertNotNull(expired);
        assertTrue(RequestTimeout.isExpired(
                new RuntimeException("wrapped", expired)));
        assertTrue(RequestTimeout.isExpired(new IllegalStateException(
                "twice", new RuntimeException("wrapped", expired))));
    }

    public void testOrdinaryFailuresAreNotTimeoutsHowEverTheyAreWrapped() {
        assertFalse(RequestTimeout.isExpired(new RuntimeException("no")));
        assertFalse(RequestTimeout.isExpired(
                new RuntimeException("no", new IllegalStateException("still no"))));
        assertFalse(RequestTimeout.isExpired(null));
    }

    /** A cycle in the chain must not become a loop. */
    public void testASelfReferencingCauseTerminates() {
        RuntimeException loop = new RuntimeException("round") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(RequestTimeout.isExpired(loop));
    }

    public void testAnUnboundedClientIsNotWrapped() {
        Slow impl = new Impl();
        assertSame("zero means wait indefinitely, so there is nothing to wrap",
                impl, RequestTimeout.bound(Slow.class, impl, 0L));
    }
}
