/**
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

package org.apache.oodt.cas.crawl.daemon;

//Avro imports
import org.apache.avro.ipc.NettyTransceiver;
import org.apache.oodt.commons.rpc.AvroTransceivers;
import org.apache.avro.ipc.Transceiver;
import org.apache.avro.ipc.specific.SpecificRequestor;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.util.HashedWheelTimer;

//OODT imports
import org.apache.oodt.cas.crawl.structs.avrotypes.AvroCrawlDaemon;
import org.apache.oodt.cas.crawl.structs.exceptions.CrawlException;

//JDK imports
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Talks to an {@link AvroRpcCrawlDaemon}.
 *
 * The same six questions {@link CrawlDaemonController} asked over XML-RPC,
 * asked over Avro instead.
 *
 * Unlike its predecessor this is {@link Closeable}, because the transceiver
 * holds a socket. The XML-RPC client did not, so nothing in the older code
 * closed anything; a caller that constructs one of these should close it.
 *
 * Netty threads are shared across controllers. Avro 1.8's one-arg
 * {@code NettyTransceiver} constructor builds a new thread pool per call,
 * and if the connect fails those threads are never released. PCS health
 * polls that path every few seconds against a down crawler, which exhausts
 * native threads and takes down the OPSUI status page.
 *
 * @author mattmann
 */
public class AvroRpcCrawlDaemonController implements Closeable {

    private static final long CONNECT_TIMEOUT_MILLIS = 40000L;

    private static final ChannelFactory CHANNEL_FACTORY =
        newSharedChannelFactory("avro-crawler-client");

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                CHANNEL_FACTORY.releaseExternalResources();
            }
        }, "avro-crawler-client-shutdown"));
    }

    private final Transceiver transceiver;

    private final AvroCrawlDaemon proxy;

    public AvroRpcCrawlDaemonController(String crawlUrlStr)
            throws InstantiationException {
        Transceiver created = null;
        try {
            URL url = new URL(crawlUrlStr);
            created = new NettyTransceiver(
                new InetSocketAddress(url.getHost(), url.getPort()),
                CHANNEL_FACTORY, Long.valueOf(CONNECT_TIMEOUT_MILLIS));
            this.transceiver = created;
            this.proxy = SpecificRequestor.getClient(AvroCrawlDaemon.class,
                this.transceiver);
        } catch (MalformedURLException e) {
            closeQuietly(created);
            throw new InstantiationException(e.getMessage());
        } catch (IOException e) {
            closeQuietly(created);
            InstantiationException wrapped = new InstantiationException(e.getMessage());
            wrapped.initCause(e);
            throw wrapped;
        } catch (RuntimeException e) {
            closeQuietly(created);
            throw e;
        }
    }

    public double getAverageCrawlTime() throws CrawlException {
        try {
            return proxy.getAverageCrawlTime();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    public int getMilisCrawling() throws CrawlException {
        try {
            return proxy.getMilisCrawling();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    public int getNumCrawls() throws CrawlException {
        try {
            return proxy.getNumCrawls();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    public int getWaitInterval() throws CrawlException {
        try {
            return proxy.getWaitInterval();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    public boolean isRunning() throws CrawlException {
        try {
            return proxy.isRunning();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    public void stop() throws CrawlException {
        try {
            proxy.stop();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        closeQuietly(this.transceiver);
    }

    /**
     * Disconnect the socket without {@code NettyTransceiver.close()}, which
     * also shuts down the shared channel factory.
     */
    private static void closeQuietly(Transceiver transceiver) {
        if (!(transceiver instanceof NettyTransceiver)) {
            if (transceiver != null) {
                try {
                    transceiver.close();
                } catch (IOException ignored) {
                    // best-effort cleanup on a failed construct
                }
            }
            return;
        }
        try {
            closeSharedNettyTransceiver((NettyTransceiver) transceiver);
        } catch (IOException ignored) {
            // best-effort cleanup on a failed construct
        }
    }

    private static void closeSharedNettyTransceiver(NettyTransceiver transceiver)
            throws IOException {
        // Was the same reflection block as AvroFileManagerClient's, which was
        // in turn the same as the resource manager client needed. One copy
        // now, in commons, where the reasoning lives with it.
        AvroTransceivers.closeSharing(transceiver);
    }

    private static ExecutorService newDaemonCachedThreadPool(final String namePrefix) {
        return Executors.newCachedThreadPool(newDaemonThreadFactory(namePrefix));
    }

    private static ChannelFactory newSharedChannelFactory(String namePrefix) {
        return new NioClientSocketChannelFactory(
            newDaemonCachedThreadPool(namePrefix + "-boss"),
            1,
            new NioWorkerPool(newDaemonCachedThreadPool(namePrefix + "-worker"),
                getIoWorkerCount()),
            new HashedWheelTimer(newDaemonThreadFactory(namePrefix + "-timer")));
    }

    private static int getIoWorkerCount() {
        return Integer.getInteger("org.apache.oodt.avro.client.ioWorkers", 2).intValue();
    }

    private static ThreadFactory newDaemonThreadFactory(final String namePrefix) {
        final AtomicInteger count = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                    namePrefix + "-" + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
