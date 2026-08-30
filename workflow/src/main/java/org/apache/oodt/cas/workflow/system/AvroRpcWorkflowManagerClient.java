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

package org.apache.oodt.cas.workflow.system;

import org.apache.avro.AvroRemoteException;
import org.apache.avro.ipc.HttpTransceiver;
import org.apache.avro.ipc.NettyTransceiver;
import org.apache.avro.ipc.Transceiver;
import org.apache.avro.ipc.specific.SpecificRequestor;
import org.apache.oodt.commons.rpc.RequestTimeout;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.util.HashedWheelTimer;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.util.AvroTypeFactory;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author radu
 *
 * <p>
 * The Avro RPC based workflow manager client.
 * </p>
 */
public class AvroRpcWorkflowManagerClient implements WorkflowManagerClient {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(AvroRpcWorkflowManagerClient.class);

    private static final ChannelFactory CHANNEL_FACTORY = newSharedChannelFactory("avro-workflow-client");

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                CHANNEL_FACTORY.releaseExternalResources();
            }
        }, "avro-workflow-client-shutdown"));
    }

    private transient Transceiver client;
    private transient org.apache.oodt.cas.workflow.struct.avrotypes.WorkflowManager proxy;
    private URL workflowManagerUrl;

    public AvroRpcWorkflowManagerClient(URL url){
        workflowManagerUrl = url;
        try {
            client = new HttpTransceiver(url);
            // Bounded: the transceiver is given no request timeout, so a
            // lost response parked the caller forever.
            proxy = RequestTimeout.bound(
                org.apache.oodt.cas.workflow.struct.avrotypes.WorkflowManager.class,
                SpecificRequestor.getClient(
                    org.apache.oodt.cas.workflow.struct.avrotypes.WorkflowManager.class, client));
        } catch (IOException e) {
            logger.error("Error occurred when creating client for: {}", url, e);
        }
        logger.info("Client created successfully for workflow manager URL: {}", url);
    }

    @Override
    public boolean refreshRepository() throws Exception {
        return proxy.refreshRepository();
    }

    @Override
    public String executeDynamicWorkflow(List<String> taskIds, Metadata metadata) throws Exception {
        logger.debug("Executing dynamic workflow for taskIds: {}", taskIds);
        return proxy.executeDynamicWorkflow(taskIds, AvroTypeFactory.getAvroMetadata(metadata));
    }

    @Override
    public List getRegisteredEvents() throws Exception {
        return proxy.getRegisteredEvents();
    }

    @Override
    public WorkflowInstancePage getFirstPage() throws Exception {
        return AvroTypeFactory.getWorkflowInstancePage(proxy.getFirstPage());
    }

    @Override
    public WorkflowInstancePage getNextPage(WorkflowInstancePage currentPage) throws Exception {
        return AvroTypeFactory.getWorkflowInstancePage(proxy.getNextPage(AvroTypeFactory.getAvroWorkflowInstancePage(currentPage)));
    }

    @Override
    public WorkflowInstancePage getPrevPage(WorkflowInstancePage currentPage) throws Exception {
        return AvroTypeFactory.getWorkflowInstancePage(proxy.getPrevPage(AvroTypeFactory.getAvroWorkflowInstancePage(currentPage)));
    }

    @Override
    public WorkflowInstancePage getLastPage() throws Exception {
        return AvroTypeFactory.getWorkflowInstancePage(proxy.getLastPage());
    }

    @Override
    public WorkflowInstancePage paginateWorkflowInstances(int pageNum, String status) throws Exception {
        return AvroTypeFactory.getWorkflowInstancePage(proxy.paginateWorkflowInstancesOfStatus(pageNum, status));
    }

    @Override
    public WorkflowInstancePage paginateWorkflowInstances(int pageNum) throws Exception {
        return AvroTypeFactory.getWorkflowInstancePage(proxy.paginateWorkflowInstances(pageNum));
    }

    @Override
    public List getWorkflowsByEvent(String eventName) throws Exception {
        return AvroTypeFactory.getWorkflows(proxy.getWorkflowsByEvent(eventName));
    }

    @Override
    public Metadata getWorkflowInstanceMetadata(String wInstId) throws Exception {
        return AvroTypeFactory.getMetadata(proxy.getWorkflowInstanceMetadata(wInstId));
    }

    @Override
    public boolean setWorkflowInstanceCurrentTaskStartDateTime(String wInstId, String startDateTimeIsoStr) throws Exception {
        return proxy.setWorkflowInstanceCurrentTaskStartDateTime(wInstId, startDateTimeIsoStr);
    }

    @Override
    public double getWorkflowCurrentTaskWallClockMinutes(String workflowInstId) throws Exception {
        return proxy.getWorkflowCurrentTaskWallClockMinutes(workflowInstId);
    }

    @Override
    public double getWorkflowWallClockMinutes(String workflowInstId) throws Exception {
        return proxy.getWorkflowWallClockMinutes(workflowInstId);
    }

    @Override
    public boolean stopWorkflowInstance(String workflowInstId) throws Exception {
        return proxy.stopWorkflowInstance(workflowInstId);
    }

    @Override
    public boolean pauseWorkflowInstance(String workflowInstId) throws Exception {
        return proxy.pauseWorkflowInstance(workflowInstId);
    }

    @Override
    public boolean resumeWorkflowInstance(String workflowInstId) throws Exception {
        return proxy.resumeWorkflowInstance(workflowInstId);
    }

    @Override
    public boolean setWorkflowInstanceCurrentTaskEndDateTime(String wInstId, String endDateTimeIsoStr) throws Exception {
        return proxy.setWorkflowInstanceCurrentTaskEndDateTime(wInstId, endDateTimeIsoStr);
    }

    @Override
    public boolean updateWorkflowInstanceStatus(String workflowInstId, String status) throws Exception {
        logger.debug("Updating workflow instance status for instance ID: {}, status: {}", workflowInstId, status);
        return proxy.updateWorkflowInstanceStatus(workflowInstId, status);
    }

    @Override
    public boolean updateWorkflowInstance(WorkflowInstance instance) throws Exception {
        return proxy.updateWorkflowInstance(AvroTypeFactory.getAvroWorkflowInstance(instance));
    }

    @Override
    public boolean updateMetadataForWorkflow(String workflowInstId, Metadata metadata) throws Exception {
        return proxy.updateMetadataForWorkflow(workflowInstId, AvroTypeFactory.getAvroMetadata(metadata));
    }

    @Override
    public boolean sendEvent(String eventName, Metadata metadata) throws Exception {
        return proxy.handleEvent(eventName, AvroTypeFactory.getAvroMetadata(metadata));
    }

    @Override
    public WorkflowTask getTaskById(String taskId) throws Exception {
        return AvroTypeFactory.getWorkflowTask(proxy.getTaskById(taskId));
    }

    @Override
    public WorkflowCondition getConditionById(String conditionId) throws Exception {
        return AvroTypeFactory.getWorkflowCondition(proxy.getConditionById(conditionId));
    }

    @Override
    public WorkflowInstance getWorkflowInstanceById(String wInstId) throws Exception {
        return AvroTypeFactory.getWorkflowInstance(proxy.getWorkflowInstanceById(wInstId));
    }

    @Override
    public Workflow getWorkflowById(String workflowId) throws Exception {
        return AvroTypeFactory.getWorkflow(proxy.getWorkflowById(workflowId));
    }

    @Override
    public Vector getWorkflows() throws Exception {
        Vector works = new Vector();

        List<Workflow> worksList = AvroTypeFactory.getWorkflows(proxy.getWorkflows());
        for (Workflow w : worksList){
            works.add(w);
        }
        return works;
    }

    @Override
    public int getNumWorkflowInstancesByStatus(String status) throws Exception {
        return proxy.getNumWorkflowInstancesByStatus(status);
    }

    @Override
    public int getNumWorkflowInstances() throws Exception {
        return proxy.getNumWorkflowInstances();
    }

    @Override
    public Vector getWorkflowInstancesByStatus(String status) throws Exception {
        // AvroTypeFactory returns an ArrayList, so casting it threw every time.
        // getWorkflowInstances and getWorkflows next door copy instead; this one
        // was left behind when the client was adapted from the XML-RPC version,
        // where the wire type really was a Vector.
        Vector instances = new Vector();
        instances.addAll(AvroTypeFactory.getWorkflowInstances(proxy.getWorkflowInstancesByStatus(status)));
        return instances;

    }

    @Override
    public List getExecutingWorkflowInstanceIds() throws Exception {
        List ids = proxy.getExecutingWorkflowInstanceIds();
        return ids == null ? new Vector() : new Vector(ids);
    }

    public Vector getWorkflowInstances() throws Exception {
        List workflowInstances =  AvroTypeFactory.getWorkflowInstances(proxy.getWorkflowInstances());
        Vector vector = new Vector();
        for (Object o : workflowInstances){
            vector.add(o);
        }
        return vector;
    }

    @Override
    public URL getWorkflowManagerUrl() {
        return this.workflowManagerUrl;
    }

    @Override
    public void setWorkflowManagerUrl(URL workflowManagerUrl) {
        this.workflowManagerUrl = workflowManagerUrl;
        // The transport in place is released before another replaces it.
        // This is a public setter that assigned straight over the field, so
        // every call after the first orphaned a connection -- and if
        // getClient threw, it orphaned the one just built as well.
        try {
            close();
        } catch (IOException e) {
            logger.warn("Unable to close the previous workflow manager "
                + "connection: {}", e.getMessage());
        }
        Transceiver opened = null;
        try {
            opened = new NettyTransceiver(
                new InetSocketAddress(workflowManagerUrl.getHost(), workflowManagerUrl.getPort()), CHANNEL_FACTORY);
            client = opened;
            // Bounded: the transceiver is given no request timeout, so a
            // lost response parked the caller forever.
            proxy = RequestTimeout.bound(
                org.apache.oodt.cas.workflow.struct.avrotypes.WorkflowManager.class,
                SpecificRequestor.getClient(
                    org.apache.oodt.cas.workflow.struct.avrotypes.WorkflowManager.class, client));
        } catch (IOException e) {
            closeQuietly(opened);
            client = null;
            proxy = null;
            logger.error("Error occurred when setting workflow manager url: {}", workflowManagerUrl, e);
        } catch (RuntimeException e) {
            closeQuietly(opened);
            client = null;
            proxy = null;
            throw e;
        }

    }

    @Override
    public boolean isAlive() {
        try {
            return proxy.isAlive();
        } catch (AvroRemoteException e) {
            logger.error("Error occurred when checking if WM is alive", e);
            return false;
        }
    }

    /** Releases a transport that will not be kept, on a failure path. */
    private static void closeQuietly(Transceiver transceiver) {
        if (transceiver == null) {
            return;
        }
        try {
            closeTransceiver(transceiver);
        } catch (Exception ignore) {
            // already failing; nothing useful to add
        }
    }

    @Override
    public void close() throws IOException {
        if (client != null) {
            closeTransceiver(client);
            client = null;
            proxy = null;
            logger.info("Closed workflow manager client: {}", workflowManagerUrl.toString());
        }
    }

    @Override
    public void finalize() throws IOException {
        close();
        logger.info("Finalized client");
    }

    private static void closeTransceiver(Transceiver transceiver) throws IOException {
        if (transceiver instanceof NettyTransceiver) {
            closeSharedNettyTransceiver((NettyTransceiver) transceiver);
        } else {
            transceiver.close();
        }
    }

    private static void closeSharedNettyTransceiver(NettyTransceiver transceiver) throws IOException {
        try {
            Field stopping = NettyTransceiver.class.getDeclaredField("stopping");
            stopping.setAccessible(true);
            stopping.set(transceiver, Boolean.TRUE);

            Method disconnect = NettyTransceiver.class.getDeclaredMethod(
                "disconnect", boolean.class, boolean.class, Throwable.class);
            disconnect.setAccessible(true);
            disconnect.invoke(transceiver, true, true, null);
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException e) {
            throw new IOException("Unable to close shared Avro Netty transceiver", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IOException("Unable to close shared Avro Netty transceiver", cause);
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
