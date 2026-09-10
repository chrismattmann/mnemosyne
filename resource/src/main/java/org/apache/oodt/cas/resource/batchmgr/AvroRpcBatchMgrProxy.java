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
import org.apache.oodt.cas.resource.util.StructFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
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

    static final String ABANDONED_POLL_PROPERTY =
            "org.apache.oodt.cas.resource.batchmgr.abandonedPollMillis";

    static final String ABANDONED_MAX_HOLD_PROPERTY =
            "org.apache.oodt.cas.resource.batchmgr.abandonedMaxHoldMillis";

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
            if (RequestTimeout.isExpired(e)) {
                // Abandoning the call is not abandoning the work. Executing a
                // job is a blocking call that returns when the job is done, so
                // running out of time on it says nothing about the job: the
                // node still has it. Falling straight through here, as every
                // other failure does, let the finally below hand the node's
                // capacity back while the task was still running on it -- so
                // the scheduler placed another, and another every time the
                // bound elapsed again. One machine reached thirty concurrent
                // tasks against a capacity of eight, and jobs that finished
                // perfectly were recorded as failures.
                //
                // So wait for the node to stop reporting the job before
                // falling through. Its capacity is held until then, because
                // until then it is genuinely in use.
                awaitAbandonedJob(e);
            } else {
                LOG.log(Level.SEVERE, "Job execution failed for jobId '" + jobSpec.getJob().getId() + "' : " + e.getMessage(), e);
            }
            parent.jobFailure(jobSpec);
        } finally {
            disconnect();
            parent.notifyMonitor(remoteHost, jobSpec);
        }

    }

    /**
     * How long between asking the node whether it still has the job. Read on
     * each poll rather than once at class load, so it can be turned down for a
     * test and up on a large cluster without a restart.
     */
    static long abandonedPollMillis() {
        long configured = Long.getLong(ABANDONED_POLL_PROPERTY, 30000L);
        return configured > 0L ? configured : 30000L;
    }

    /**
     * The longest a node may hold a slot for a job it keeps reporting.
     *
     * <p>
     * A backstop, not a task deadline. The node's own answer decides when the
     * slot comes back, and this exists only so that a node reporting a job
     * that will never finish -- a wedged science process, a stub that has lost
     * track of its own threads -- cannot pin a slot and a dispatch thread for
     * the life of the process. Twenty-four hours is far longer than any task
     * this schedules, so reaching it means something is wrong and the log says
     * so rather than the slot quietly vanishing.
     * </p>
     */
    static long abandonedMaxHoldMillis() {
        long configured = Long.getLong(ABANDONED_MAX_HOLD_PROPERTY,
                24L * 60L * 60L * 1000L);
        return configured > 0L ? configured : 24L * 60L * 60L * 1000L;
    }

    /**
     * Wait until the node stops reporting a job whose call we gave up on.
     *
     * <p>
     * There is no deadline here beyond the node answering. A node that still
     * lists the job still has it, and its capacity is not free however long
     * that takes; a node that has stopped answering has lost the job with it,
     * and holding capacity for it would leak. Those are the only two outcomes,
     * and asking is what tells them apart, so a deadline would only put back
     * the guess this exists to remove.
     * </p>
     *
     * <p>
     * The poll goes through the same bounded client, so a stub wedged badly
     * enough not to answer at all reads as unreachable rather than hanging
     * this thread forever -- which is what the bound was added for.
     * </p>
     */
    private void awaitAbandonedJob(Exception expired) {
        String jobId = jobSpec.getJob().getId();
        LOG.log(Level.WARNING, "Gave up waiting for job [" + jobId + "] on ["
                + remoteHost.getNodeId() + "]: " + expired.getMessage()
                + " The job is still the node's to finish, so its capacity is "
                + "held until the node stops reporting it.");

        long held = 0L;
        long maxHold = abandonedMaxHoldMillis();
        while (true) {
            NodeJobs running = jobsOnNode();
            if (!running.known()) {
                LOG.log(Level.WARNING, "Node [" + remoteHost.getNodeId()
                        + "] stopped answering while job [" + jobId + "] was "
                        + "outstanding; releasing its capacity after " + held
                        + "ms, because a node we cannot reach is not running "
                        + "anything for us.");
                return;
            }
            if (!running.contains(jobId)) {
                LOG.log(Level.INFO, "Node [" + remoteHost.getNodeId() + "] no "
                        + "longer reports job [" + jobId + "] after " + held
                        + "ms; releasing its capacity.");
                return;
            }
            if (held >= maxHold) {
                // Loudly, because this should not happen: the node says it is
                // still running a job it has had for a day. Something is wrong
                // on that node, and holding the slot for the life of the
                // process would only hide it behind a cluster that slowly
                // stops scheduling.
                LOG.log(Level.SEVERE, "Node [" + remoteHost.getNodeId()
                        + "] has reported job [" + jobId + "] as running for "
                        + held + "ms, past the " + maxHold + "ms this will hold "
                        + "a slot for. Releasing it: the node may still be "
                        + "working, so this is worth looking at rather than "
                        + "ignoring.");
                return;
            }
            try {
                Thread.sleep(abandonedPollMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(Level.WARNING, "Interrupted while waiting for job ["
                        + jobId + "]; releasing the node's capacity.");
                return;
            }
            held += abandonedPollMillis();
        }
    }

    /** What the node says it is running, or that it could not be asked. */
    NodeJobs jobsOnNode() {
        try {
            connect();
        } catch (IOException e) {
            return NodeJobs.unknown();
        }
        try {
            List<String> running = new ArrayList<String>();
            List<?> reported = proxy.getJobsOnNode(remoteHost.getNodeId());
            if (reported != null) {
                for (Object each : reported) {
                    running.add(String.valueOf(each));
                }
            }
            return NodeJobs.reported(running);
        } catch (Exception e) {
            return NodeJobs.unknown();
        } finally {
            disconnect();
        }
    }

    /**
     * What a node said about the jobs it is running, or that it could not be
     * asked at all.
     *
     * <p>
     * A bare list cannot say "we could not tell", and the obvious stand-in --
     * an empty list -- is the very mistake this class exists to prevent: empty
     * reads as "the job has finished" and releases a slot on a node that may
     * still be working. Returning null said it instead, in a convention no
     * compiler checks and every reader has to be told about. Saying it in the
     * type costs one small class and cannot be got wrong by accident.
     * </p>
     */
    static final class NodeJobs {

        private final List<String> jobs;

        private NodeJobs(List<String> jobs) {
            this.jobs = jobs;
        }

        /** The node could not be asked, so nothing is known either way. */
        static NodeJobs unknown() {
            return new NodeJobs(null);
        }

        static NodeJobs reported(List<String> jobs) {
            return new NodeJobs(new ArrayList<String>(jobs));
        }

        /** Whether the node answered at all. */
        boolean known() {
            return jobs != null;
        }

        /**
         * Whether the node reported this job. False when the node could not be
         * asked, so callers must check {@link #known} first if the difference
         * matters -- and for releasing capacity it always does.
         */
        boolean contains(String jobId) {
            return jobs != null && jobs.contains(jobId);
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
        Transceiver opened = new NettyTransceiver(
                new InetSocketAddress(remoteHost.getIpAddr().getHost(), remoteHost.getIpAddr().getPort()),
                CHANNEL_FACTORY);
        try {
            // Bounded, because a batch stub that stops responding mid-job
            // used to hold this thread for the life of the process.
            this.proxy = RequestTimeout.bound(AvroIntrBatchmgr.class,
                    SpecificRequestor.getClient(AvroIntrBatchmgr.class, opened));
            this.client = opened;
        } catch (RuntimeException e) {
            // The socket is open by now; the callers' finally only runs once
            // connect has returned, so it would be orphaned otherwise.
            try {
                AvroTransceivers.closeSharing(opened);
            } catch (IOException ignore) {
                // already failing
            }
            throw e;
        }
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
