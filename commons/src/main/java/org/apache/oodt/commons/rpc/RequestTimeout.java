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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Puts a bound on how long an Avro client call may take.
 *
 * The transceivers these clients build are given a connect timeout and
 * nothing else. Once connected, a request that never gets a response parks
 * the calling thread in CallFuture.get() -- a CountDownLatch await with no
 * deadline -- and there is no recovery: the caller is not slow, it is gone.
 * A crawler killed mid-request, a file manager that ran out of heap, a
 * network path that dropped the connection without an RST, all end the same
 * way, and in a poller like OPSUI each one takes a thread with it.
 *
 * Avro 1.8.2 offers no request timeout of its own: NettyTransceiver's
 * constructors take a connect timeout only, and it builds its own channel
 * pipeline, so there is nowhere to hang a Netty ReadTimeoutHandler either.
 * What is left is to run the call somewhere the caller can stop waiting for
 * it, which is what this does -- one proxy over the generated client
 * interface, each call handed to a pooled thread and collected with a
 * deadline.
 *
 * Cancelling interrupts that thread, and the latch inside CallFuture is
 * interruptible, so the abandoned call unwinds rather than accumulating.
 *
 * Every method Avro generates for a protocol declares AvroRemoteException,
 * so a timeout is reported through the signature the caller already handles;
 * no call site changes. A method that does not declare it -- there are none
 * today -- gets an unchecked exception instead, since the alternative is to
 * go on waiting.
 *
 * The bound is deliberately generous by default. A catalog query over a
 * large repository can legitimately run for minutes, and the defect here is
 * waiting forever, not waiting a while: any finite bound fixes it, and a
 * tight one would break deployments that currently work. Set
 * org.apache.oodt.avro.client.requestTimeoutMillis to tune it, or to 0 to
 * opt out entirely and get the old unbounded behaviour back.
 */
public final class RequestTimeout {

    /** System property naming the per-request bound, in milliseconds. */
    public static final String TIMEOUT_PROPERTY =
        "org.apache.oodt.avro.client.requestTimeoutMillis";

    /** Ten minutes: long enough for any legitimate call, short of forever. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 600000L;

    private static final ExecutorService CALLS = Executors.newCachedThreadPool(
        new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "avro-client-call-" + count.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

    private RequestTimeout() {
    }

    /**
     * @return the configured bound in milliseconds, or 0 to mean unbounded
     */
    public static long configuredTimeoutMillis() {
        return Long.getLong(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Wrap a generated Avro client so no call to it can run forever.
     *
     * @param protocol the generated protocol interface
     * @param client   the client Avro built for it
     * @return a client bounded by the configured timeout, or the client
     *         itself if the timeout is 0 or negative
     */
    public static <T> T bound(Class<T> protocol, T client) {
        return bound(protocol, client, configuredTimeoutMillis());
    }

    /**
     * @param timeoutMillis the bound to apply; 0 or negative means unbounded
     */
    public static <T> T bound(Class<T> protocol, T client, long timeoutMillis) {
        if (client == null || timeoutMillis <= 0L) {
            return client;
        }
        return protocol.cast(Proxy.newProxyInstance(
            protocol.getClassLoader(),
            new Class<?>[] { protocol },
            new BoundedCall(client, timeoutMillis)));
    }

    /**
     * Carries the exception a call threw across the executor boundary
     * without ExecutionException wrapping it a second time.
     */
    private static final class CallFailed extends Exception {
        private CallFailed(Throwable cause) {
            super(cause);
        }
    }

    private static final class BoundedCall implements InvocationHandler {

        private final Object client;
        private final long timeoutMillis;

        private BoundedCall(Object client, long timeoutMillis) {
            this.client = client;
            this.timeoutMillis = timeoutMillis;
        }

        @Override
        public Object invoke(Object proxy, final Method method, final Object[] args)
                throws Throwable {
            // equals, hashCode and toString are answered here. Sending them
            // over the wire would be absurd, and toString in particular is
            // called by loggers.
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                return "bounded[" + client + ", " + timeoutMillis + "ms]";
            }

            Future<Object> pending = CALLS.submit(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    try {
                        return method.invoke(client, args);
                    } catch (InvocationTargetException e) {
                        throw new CallFailed(e.getCause());
                    }
                }
            });

            try {
                return pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Interrupts the waiting thread; CallFuture's latch is
                // interruptible, so the abandoned call unwinds.
                pending.cancel(true);
                throw timedOut(method);
            } catch (InterruptedException e) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw timedOut(method);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CallFailed) {
                    // The exception the call itself threw, unwrapped, so the
                    // proxy is invisible to a caller that catches it.
                    throw cause.getCause();
                }
                throw cause;
            }
        }

        private Throwable timedOut(Method method) {
            String message = "No response to " + method.getName() + " within "
                + timeoutMillis + "ms; abandoning the call. Set "
                + TIMEOUT_PROPERTY + " to change this bound, or to 0 to wait "
                + "indefinitely.";

            for (Class<?> declared : method.getExceptionTypes()) {
                if (declared.isAssignableFrom(AvroRemoteException.class)) {
                    return new AvroRemoteException(message);
                }
            }
            return new IllegalStateException(message);
        }
    }
}
