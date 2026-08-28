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

package org.apache.oodt.cas.resource.batchmgr;

import org.apache.avro.AvroRemoteException;
import org.apache.avro.ipc.NettyTransceiver;
import org.apache.avro.ipc.Transceiver;
import org.apache.avro.ipc.specific.SpecificRequestor;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.util.HashedWheelTimer;
import org.apache.oodt.cas.resource.structs.AvroTypeFactory;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.ResourceNode;
import org.apache.oodt.cas.resource.structs.avrotypes.AvroIntrBatchmgr;
import org.apache.oodt.commons.rpc.AvroTransceivers;
import org.apache.oodt.commons.rpc.RequestTimeout;
import org.apache.oodt.cas.resource.util.XmlRpcStructFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AvroRpcBatchMgrProxy extends Thread implements Runnable {

    // Was XmlRpcBatchMgrProxy.class: every line this class has ever logged
    // was filed under the name of a different class, so anyone filtering logs
    // by this one saw nothing.
    private static final Logger LOG = Logger.getLogger(AvroRpcBatchMgrProxy.class.getName());

    private static final ChannelFactory CHANNEL_FACTORY = newSharedChannelFactory("avro-batch-client");

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                CHANNEL_FACTORY.releaseExternalResources();
            }
        }, "avro-batch-client-shutdown"));
    }

    private JobSpec jobSpec;

    private ResourceNode remoteHost;

    private transient Transceiver client;

    // Was AvroRpcBatchStub, which is the server class, not the protocol.
    // SpecificRequestor.getClient builds a java.lang.reflect.Proxy, and that
    // requires an interface -- so connecting threw
    // "IllegalArgumentException: ...AvroRpcBatchStub is not an interface",
    // unchecked and past the IOException catch below. Every path through
    // this class died on its first line, which is to say the Avro batch
    // manager could not dispatch a job at all.
    private transient AvroIntrBatchmgr proxy;

    private AvroRpcBatchMgr parent;

    public AvroRpcBatchMgrProxy(JobSpec jobSpec, ResourceNode remoteHost,
                               AvroRpcBatchMgr par) {
        this.jobSpec = jobSpec;
        this.remoteHost = remoteHost;
        this.parent = par;
    }

    public boolean nodeAlive() {
        try {
            connect();
        } catch (IOException e) {
            // The connection failure used to be logged and then ignored,
            // leaving proxy null for the call below to dereference. A node
            // this cannot reach is a node that is not alive.
            LOG.log(Level.SEVERE, "Failed connection with the server.", e);
            return false;
        }

        try {
            return proxy.isAlive();
        } catch (AvroRemoteException e) {
            return false;
        } finally {
            disconnect();
        }
    }

    public boolean killJob() {
        try {
            connect();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed connection with the server.", e);
            return false;
        }

        boolean result = false;
        try {
            result = proxy.killJob(AvroTypeFactory.getAvroJob(jobSpec.getJob()));
        } catch (AvroRemoteException e) {
            LOG.log(Level.WARNING, "Unable to kill job: ["
                    + jobSpec.getJob().getId() + "]: " + e.getMessage(), e);
            result = false;
        } finally {
            disconnect();
        }

        if (result) {
            parent.jobKilled(jobSpec);
        }

        return result;
    }

    public void run() {
        try {
            connect();
        } catch (IOException e) {
            // Was logged and ignored, leaving proxy null for executeJob to
            // dereference; the job then failed with a NullPointerException
            // rather than with the connection error that caused it.
            LOG.log(Level.SEVERE, "Failed connection with the server.", e);
            parent.jobFailure(jobSpec);
            parent.notifyMonitor(remoteHost, jobSpec);
            return;
        }

        boolean result;
        try {
            parent.jobExecuting(jobSpec);
            result = proxy.executeJob(AvroTypeFactory.getAvroJob(jobSpec.getJob()),
                    AvroTypeFactory.getAvroJobInput(jobSpec.getIn()));
            if (result)
                parent.jobSuccess(jobSpec);
            else
                throw new Exception("batchstub.executeJob returned false");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Job execution failed for jobId '" + jobSpec.getJob().getId() + "' : " + e.getMessage(), e);
            parent.jobFailure(jobSpec);
        } finally {
            disconnect();
            parent.notifyMonitor(remoteHost, jobSpec);
        }

    }






    /**
     * Opens the transport to the node this proxy speaks for.
     *
     * Each of the three entry points opened its own and none of them closed
     * it, so a proxy leaked one socket per call -- the same leak as #144 and
     * #192, three times over in one class. They are closed in a finally now.
     */
    private void connect() throws IOException {
        this.client = new NettyTransceiver(
                new InetSocketAddress(remoteHost.getIpAddr().getHost(), remoteHost.getIpAddr().getPort()),
                CHANNEL_FACTORY);
        // Bounded, because a batch stub that stops responding mid-job used
        // to hold this thread for the life of the process.
        this.proxy = RequestTimeout.bound(AvroIntrBatchmgr.class,
                SpecificRequestor.getClient(AvroIntrBatchmgr.class, client));
    }

    /** Releases the transport, leaving the shared Netty threads running. */
    private void disconnect() {
        Transceiver toClose = this.client;
        this.client = null;
        this.proxy = null;
        try {
            AvroTransceivers.closeSharing(toClose);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Unable to close the batch stub transport: "
                    + e.getMessage(), e);
        }
    }

    private static ExecutorService newDaemonCachedThreadPool(final String namePrefix) {
        return Executors.newCachedThreadPool(newDaemonThreadFactory(namePrefix));
    }

    private static ChannelFactory newSharedChannelFactory(String namePrefix) {
        return new NioClientSocketChannelFactory(
                newDaemonCachedThreadPool(namePrefix + "-boss"),
                1,
                new NioWorkerPool(newDaemonCachedThreadPool(namePrefix + "-worker"), getIoWorkerCount()),
                new HashedWheelTimer(newDaemonThreadFactory(namePrefix + "-timer")));
    }

    private static int getIoWorkerCount() {
        return Integer.getInteger("org.apache.oodt.avro.client.ioWorkers", 2);
    }

    private static ThreadFactory newDaemonThreadFactory(final String namePrefix) {
        final AtomicInteger count = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, namePrefix + "-" + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
