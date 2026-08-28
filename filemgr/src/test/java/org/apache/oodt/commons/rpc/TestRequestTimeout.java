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

import org.apache.avro.AvroRemoteException;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * {@link RequestTimeout} puts a deadline on an Avro client call.
 *
 * Written against a stand-in protocol rather than a real one: the behaviour
 * being asserted is the proxy's, and a hand-written interface makes the
 * "declares AvroRemoteException" and "does not declare it" cases both
 * expressible, which no generated protocol does.
 *
 * It lives in filemgr rather than commons because commons runs surefire 2.4
 * with useSystemClassLoader=false, under which its tests cannot load classes
 * this one needs.
 */
public class TestRequestTimeout {

    /** Stands in for a generated Avro protocol. */
    public interface Protocol {
        String slow() throws AvroRemoteException;

        String quick() throws AvroRemoteException;

        String boom() throws AvroRemoteException;

        String undeclared();
    }

    /** A call that blocks until released, the shape of a lost response. */
    private static final class Blocking implements Protocol {
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean(false);

        @Override
        public String slow() throws AvroRemoteException {
            try {
                // Stands in for CallFuture.get(): an interruptible wait with
                // no deadline of its own.
                release.await();
                return "eventually";
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new AvroRemoteException("interrupted");
            }
        }

        @Override
        public String quick() {
            return "immediately";
        }

        @Override
        public String boom() throws AvroRemoteException {
            throw new AvroRemoteException("the server said no");
        }

        @Override
        public String undeclared() {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "eventually";
        }
    }

    @Test
    public void testACallThatNeverAnswersIsAbandoned() throws Exception {
        Blocking server = new Blocking();
        Protocol client = RequestTimeout.bound(Protocol.class, server, 200L);

        long started = System.currentTimeMillis();
        try {
            client.slow();
            fail("the call was allowed to run forever");
        } catch (AvroRemoteException expected) {
            assertTrue("the timeout is not described: " + expected.getMessage(),
                    expected.getMessage().contains("200ms"));
        }
        long waited = System.currentTimeMillis() - started;

        assertTrue("returned before the deadline: " + waited + "ms", waited >= 200L);
        assertTrue("waited far past the deadline: " + waited + "ms", waited < 15000L);
    }

    /**
     * The thread the abandoned call was running on has to come back, or a
     * bounded wait just moves the leak from the caller to the pool.
     */
    @Test
    public void testTheAbandonedCallIsInterrupted() throws Exception {
        Blocking server = new Blocking();
        Protocol client = RequestTimeout.bound(Protocol.class, server, 200L);

        try {
            client.slow();
            fail("the call was allowed to run forever");
        } catch (AvroRemoteException expected) {
            // the point is what happened to the other thread
        }

        long deadline = System.currentTimeMillis() + 10000L;
        while (!server.interrupted.get() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20L);
        }
        assertTrue("the abandoned call was left running", server.interrupted.get());
    }

    /** A call that answers in time is untouched. */
    @Test
    public void testAPromptCallIsUnaffected() throws Exception {
        Protocol client = RequestTimeout.bound(Protocol.class, new Blocking(), 5000L);

        assertEquals("immediately", client.quick());
    }

    /**
     * The proxy has to be invisible to a caller that catches what the call
     * threw; wrapping it in ExecutionException would break every catch block
     * in the clients.
     */
    @Test
    public void testTheCallsOwnExceptionReachesTheCallerUnwrapped() {
        Protocol client = RequestTimeout.bound(Protocol.class, new Blocking(), 5000L);

        try {
            client.boom();
            fail("the exception was swallowed");
        } catch (AvroRemoteException e) {
            assertEquals("the server said no", e.getMessage());
        }
    }

    /** A timeout of zero means the old unbounded behaviour, and no proxy. */
    @Test
    public void testATimeoutOfZeroOptsOut() {
        Blocking server = new Blocking();

        assertSame(server, RequestTimeout.bound(Protocol.class, server, 0L));
        assertSame(server, RequestTimeout.bound(Protocol.class, server, -1L));
    }

    /** A method that declares no AvroRemoteException still gets a deadline. */
    @Test
    public void testAMethodThatCannotThrowAvroRemoteExceptionStillTimesOut() {
        Protocol client = RequestTimeout.bound(Protocol.class, new Blocking(), 200L);

        try {
            client.undeclared();
            fail("the call was allowed to run forever");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("200ms"));
        }
    }

    /** toString must not go over the wire; loggers call it. */
    @Test
    public void testObjectMethodsAreAnsweredLocally() {
        Protocol client = RequestTimeout.bound(Protocol.class, new Blocking(), 200L);

        assertNotNull(client.toString());
        assertEquals(client, client);
        assertEquals(System.identityHashCode(client), client.hashCode());
    }

    /** null in, null out: a client that failed to build is not wrapped. */
    @Test
    public void testANullClientIsNotWrapped() {
        assertNull(RequestTimeout.bound(Protocol.class, null, 200L));
    }
}
