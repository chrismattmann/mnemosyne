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
import org.apache.oodt.cas.resource.system.extern.AvroRpcBatchStub;
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

    private transient AvroRpcBatchStub proxy;

    private AvroRpcBatchMgr parent;

    public AvroRpcBatchMgrProxy(JobSpec jobSpec, ResourceNode remoteHost,
                               AvroRpcBatchMgr par) {
        this.jobSpec = jobSpec;
        this.remoteHost = remoteHost;
        this.parent = par;
    }

    public boolean nodeAlive() {

        try {
            this.client = new NettyTransceiver(
                    new InetSocketAddress(remoteHost.getIpAddr().getHost(), remoteHost.getIpAddr().getPort()),
                    CHANNEL_FACTORY);
            this.proxy = (AvroRpcBatchStub) SpecificRequestor.getClient(AvroRpcBatchStub.class, client);
        } catch (IOException e) {
            e.printStackTrace();
            LOG.log(Level.SEVERE, "Failed connection with the server.", e);
        }



        boolean alive = false;

        try {
            alive = proxy.isAlive();
        } catch (AvroRemoteException e) {
            alive = false;
        }
        return alive;

    }

    public boolean killJob() {

        try {
            this.client = new NettyTransceiver(
                    new InetSocketAddress(remoteHost.getIpAddr().getHost(), remoteHost.getIpAddr().getPort()),
                    CHANNEL_FACTORY);
            this.proxy = (AvroRpcBatchStub) SpecificRequestor.getClient(AvroRpcBatchStub.class, client);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed connection with the server.", e);
        }


        boolean result = false;
        try {
            result = proxy.killJob(AvroTypeFactory.getAvroJob(jobSpec.getJob()));
        } catch (AvroRemoteException e) {
            e.printStackTrace();
            result = false;
        }

        if (result) {
            parent.jobKilled(jobSpec);
        }

        return result;
    }

    public void run() {
        try {
            this.client = new NettyTransceiver(
                    new InetSocketAddress(remoteHost.getIpAddr().getHost(), remoteHost.getIpAddr().getPort()),
                    CHANNEL_FACTORY);
            this.proxy = (AvroRpcBatchStub) SpecificRequestor.getClient(AvroRpcBatchStub.class, client);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed connection with the server.", e);
        }

        boolean result = false;
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
        }finally {
            parent.notifyMonitor(remoteHost, jobSpec);
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
