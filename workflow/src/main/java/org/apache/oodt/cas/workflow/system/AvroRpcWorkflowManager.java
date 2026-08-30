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

import com.google.common.base.Preconditions;
import org.apache.avro.AvroRemoteException;
import org.apache.oodt.cas.workflow.struct.avrotypes.OodtError;
import org.apache.oodt.cas.workflow.struct.avrotypes.OodtFailureKind;
import org.apache.oodt.commons.rpc.FailureKinds;
import org.apache.avro.ipc.HttpServer;
import org.apache.avro.ipc.Server;
import org.apache.oodt.commons.rpc.ErrorUnwrappingResponder;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.engine.ThreadPoolWorkflowEngineFactory;
import org.apache.oodt.cas.workflow.engine.WorkflowEngine;
import org.apache.oodt.cas.workflow.repository.DataSourceWorkflowRepositoryFactory;
import org.apache.oodt.cas.workflow.repository.WorkflowRepository;
import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflow;
import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflowCondition;
import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflowInstance;
import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflowInstancePage;
import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflowTask;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.exceptions.EngineException;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;
import org.apache.oodt.cas.workflow.structs.exceptions.RepositoryException;
import org.apache.oodt.cas.workflow.util.AvroTypeFactory;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.apache.oodt.cas.workflow.util.GenericWorkflowObjectFactory.getWorkflowEngineFromClassName;
import static org.apache.oodt.cas.workflow.util.GenericWorkflowObjectFactory.getWorkflowRepositoryFromClassName;

/**
 * @author radu
 *
 * <p>
 * The Avro RPC based workflow manager.
 * </p>
 */
public class AvroRpcWorkflowManager implements WorkflowManager,org.apache.oodt.cas.workflow.struct.avrotypes.WorkflowManager {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(AvroRpcWorkflowManager.class);

    private Server server;
    private volatile Thread shutdownHook;
    private final WorkflowEngine engine;
    private WorkflowRepository repo;

    public AvroRpcWorkflowManager(){
        this(DEFAULT_WEB_SERVER_PORT);
    }

    public AvroRpcWorkflowManager(int port){
        logger.info("Starting workflow manager on port: {} as {}",
                port, System.getProperty("user.name", "unknown"));

        Preconditions.checkArgument(port > 0, "Must specify a port greater than 0");
    
        try {
            loadProperties();
        } catch (IOException e) {
            logger.error("Error occurred when loading properties", e);
        }
    
        logger.debug("Getting workflow engine");
        engine = getWorkflowEngineFromProperty();
        if(engine == null){
            throw new IllegalStateException("Null engine");
        }

        URL workflowManagerUrl = safeGetUrlFromString("http://" + getHostname() + ":" + port);
        if(workflowManagerUrl == null){
            throw new IllegalStateException("Null workflow manager URL");
        }

        logger.debug("Setting workflow engine url: {}", workflowManagerUrl.toString());
        engine.setWorkflowManagerUrl(safeGetUrlFromString("http://" + getHostname()  + ":" + port));
        repo = getWorkflowRepositoryFromProperty();

        logger.debug("Starting Http Server...");
        // start up the server
        try {
            server = new HttpServer(new ErrorUnwrappingResponder(
                    org.apache.oodt.cas.workflow.struct.avrotypes.WorkflowManager.class,this), port);
        } catch (IOException e) {
            logger.error("Unable to create http server on port: {}", e);
            throw new IllegalStateException("Unable to start http server on port: " + port, e);
        }

        logger.debug("Server created. Starting ...");
        server.start();

        // Held so shutdown() can take it back off. An anonymous hook was
        // registered per instance and never removed, so it outlived the
        // manager it was closing: every manager built in a JVM -- which is
        // every test that starts one -- left a hook behind holding a strong
        // reference to it, and the hooks ran at exit against servers that had
        // been shut down long before.
        this.shutdownHook = new Thread("workflow-manager-shutdown") {
            @Override
            public void run() {
                shutdownInternal();
            }
        };
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
        logger.info("Workflow Manager started by {} for url: {}",
                System.getProperty("user.name", "unknown"), workflowManagerUrl);

    }

    @Override
    public boolean shutdown() {
        Thread hook = this.shutdownHook;
        this.shutdownHook = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // The JVM is on its way out and is running the hooks itself;
                // there is nothing to deregister and nothing to report.
            }
        }
        return shutdownInternal();
    }

    /**
     * The shutdown itself, without touching the hook -- so the hook can call
     * it without trying to remove itself while it is running.
     */
    private boolean shutdownInternal() {
        logger.debug("Shutting down");
        // The engine starts threads of its own and was never told to stop, so
        // shutting the manager down left them running for the life of the
        // process. Stopped before the server, so nothing new is accepted
        // while it winds down.
        if (engine != null) {
            try {
                engine.shutdown();
            } catch (RuntimeException e) {
                logger.warn("Error shutting down the workflow engine", e);
            }
        }
        if (server != null) {
            server.close();
            server = null;
            logger.info("Successfully shutdown");
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean refreshRepository() throws AvroRemoteException {
        repo = getWorkflowRepositoryFromProperty();
        return true;
    }

    @Override
    public String executeDynamicWorkflow(List<String> taskIds, Map<String, Object> metadata) throws AvroRemoteException {
        logger.debug("Executing dynamic workflow with task IDs: {}", taskIds);
        try {
            if (taskIds == null || taskIds.size() == 0){
                logger.warn("Null or empty task IDs");
                throw new RepositoryException("Must specify task identifiers to build dynamic workflows!");
            }
            Workflow dynamicWorkflow = new Workflow();

            for (String taskId : taskIds) {
                WorkflowTask task = this.repo.getWorkflowTaskById(taskId);
                if (task == null){
                    throw new RepositoryException("Dynamic workflow task: [" + taskId
                            + "] is not defined!");
                }
                dynamicWorkflow.getTasks().add(task);
            }

            dynamicWorkflow.setId(this.repo.addWorkflow(dynamicWorkflow));
            dynamicWorkflow.setName("Dynamic Workflow-" + dynamicWorkflow.getId());

            Metadata met = new Metadata();
            met.addMetadata(AvroTypeFactory.getMetadata(metadata));

            logger.info("Created dynamic workflow[{}] for task IDs: {}", dynamicWorkflow.getName(), taskIds);
            WorkflowInstance inst = this.engine.startWorkflow(dynamicWorkflow, met);
            return inst.getId();
        }catch (RepositoryException | EngineException e){
            logger.error("Error occurred when creating dynamic workflow for taskIDs: {}", taskIds, e);
            throw oodtError(e);
        }
    }

    @Override
    public List<String> getRegisteredEvents() throws AvroRemoteException {
        List events = null;
        try {
            events = repo.getRegisteredEvents();
            return events;
        } catch (RepositoryException e) {
            logger.error("Error occurred when registering events: {}", e.getMessage());
            throw oodtError(e, "Exception getting registered events from repository: Message: " + e.getMessage());
        }
    }

    @Override
    public AvroWorkflowInstancePage getFirstPage() throws AvroRemoteException {
        WorkflowInstancePage page = engine.getInstanceRepository().getFirstPage();
        if (page != null) {
            logger.debug("Found first page: {}", page);
            populateWorkflows(page.getPageWorkflows());
            return AvroTypeFactory.getAvroWorkflowInstancePage(page);
        } else
            return AvroTypeFactory.getAvroWorkflowInstancePage(WorkflowInstancePage.blankPage());
    }

    @Override
    public AvroWorkflowInstancePage getNextPage(AvroWorkflowInstancePage currentPage) throws AvroRemoteException {
        // first unpack current page
        WorkflowInstancePage currPage = AvroTypeFactory.getWorkflowInstancePage(currentPage);
        WorkflowInstancePage page = engine.getInstanceRepository().getNextPage(
                currPage);
        if (page != null) {
            populateWorkflows(page.getPageWorkflows());
            return AvroTypeFactory.getAvroWorkflowInstancePage(page);
        } else
            return AvroTypeFactory.getAvroWorkflowInstancePage(WorkflowInstancePage
                    .blankPage());
    }

    @Override
    public AvroWorkflowInstancePage getPrevPage(AvroWorkflowInstancePage currentPage) throws AvroRemoteException {
        // first unpack current page
        WorkflowInstancePage currPage = AvroTypeFactory.getWorkflowInstancePage(currentPage);
        WorkflowInstancePage page = engine.getInstanceRepository().getPrevPage(
                currPage);
        if (page != null) {
            populateWorkflows(page.getPageWorkflows());
            return AvroTypeFactory.getAvroWorkflowInstancePage(page);
        } else
            return AvroTypeFactory.getAvroWorkflowInstancePage(WorkflowInstancePage
                    .blankPage());
    }

    @Override
    public AvroWorkflowInstancePage getLastPage() throws AvroRemoteException {
        WorkflowInstancePage page = engine.getInstanceRepository()
                .getLastPage();
        if (page != null) {
            populateWorkflows(page.getPageWorkflows());
            return AvroTypeFactory.getAvroWorkflowInstancePage(page);
        } else
            return AvroTypeFactory.getAvroWorkflowInstancePage(WorkflowInstancePage
                    .blankPage());
    }

    @Override
    public AvroWorkflowInstancePage paginateWorkflowInstancesOfStatus(int pageNum, String status)
    throws AvroRemoteException{
        WorkflowInstancePage page = null;
        try {
            page = engine.getInstanceRepository()
                    .getPagedWorkflows(pageNum, status);
        if (page != null) {
            populateWorkflows(page.getPageWorkflows());
            return AvroTypeFactory.getAvroWorkflowInstancePage(page);
        } else
            return AvroTypeFactory.getAvroWorkflowInstancePage(WorkflowInstancePage
                    .blankPage());
        } catch (InstanceRepositoryException e) {
            throw oodtError(e);
        }
    }

    @Override
    public AvroWorkflowInstancePage paginateWorkflowInstances(int pageNum) throws AvroRemoteException {
        WorkflowInstancePage page = null;
        try {
            page = engine.getInstanceRepository()
                    .getPagedWorkflows(pageNum);
        if (page != null) {
            populateWorkflows(page.getPageWorkflows());
            return AvroTypeFactory.getAvroWorkflowInstancePage(page);
        } else
            return AvroTypeFactory.getAvroWorkflowInstancePage(WorkflowInstancePage
                    .blankPage());
        } catch (InstanceRepositoryException e) {
            throw oodtError(e);
        }

    }

    @Override
    public List<AvroWorkflow> getWorkflowsByEvent(String eventName) throws AvroRemoteException {
        List workflows = null;
        try {
            workflows = repo.getWorkflowsForEvent(eventName);
            if (workflows != null)
                return AvroTypeFactory.getAvroWorkflows(workflows);
            else
                return new ArrayList();
        } catch (Exception e) {
            e.printStackTrace();
            throw oodtError(e, "Exception getting workflows for event: " + eventName
                            + " from repository: Message: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getWorkflowInstanceMetadata(String wInstId) throws AvroRemoteException {
        Metadata met = engine.getWorkflowInstanceMetadata(wInstId);
        return AvroTypeFactory.getAvroMetadata(met);
    }


    @Override
    public boolean handleEvent(String eventName, Map<String, Object> metadata) throws AvroRemoteException {
        logger.info("Received event: {}", eventName);
        logger.debug("Reveiced meta data for event: {} -> {}", eventName, metadata);

        List workflows = null;

        try {
            workflows = repo.getWorkflowsForEvent(eventName);
        } catch (Exception e) {
            logger.error("Couldn't get workflows for event: {}", eventName, e);
            throw oodtError(e, "Exception getting workflows associated with event: "
                            + eventName + ": Message: " + e.getMessage());
        }

        if (workflows != null) {
            logger.debug("Found {} workflows for event: {}", workflows.size(), eventName);

            for (Iterator i = workflows.iterator(); i.hasNext();) {
                Workflow w = (Workflow) i.next();
                logger.debug("Workflow {} retrieved for event: {}", w.getName(), eventName);

                Metadata m = new Metadata();
                m.addMetadata(AvroTypeFactory.getMetadata(metadata));

                try {
                    engine.startWorkflow(w, m);
                } catch (Exception e) {
                    logger.error("Error when starting workflow: {} with metadata: {}", w.getName(), m.getAllKeys(), e);
                    throw oodtError(e, "Engine exception when starting workflow: " + w.getName() + ": Message: " + e.getMessage());
                }
            }

            logger.info("Event: {} handled successfully", eventName);
            return true;
        } else
            return false;
    }

    @Override
    public AvroWorkflowInstance getWorkflowInstanceById(String wInstId) throws AvroRemoteException {
        WorkflowInstance inst = null;

        try {
            inst = engine.getInstanceRepository().getWorkflowInstanceById(
                    wInstId);
        } catch (Exception e) {
            logger.error("Error obtaining workflow instance with ID: [{}], error: {}", wInstId, e.getMessage());
            inst = new WorkflowInstance();
        }

        return AvroTypeFactory.getAvroWorkflowInstance(inst);
    }

    @Override
    public boolean stopWorkflowInstance(String workflowInstId) throws AvroRemoteException {
        engine.stopWorkflow(workflowInstId);
        return true;
    }

    @Override
    public boolean pauseWorkflowInstance(String workflowInstId) throws AvroRemoteException {
        engine.pauseWorkflowInstance(workflowInstId);
        return true;
    }

    @Override
    public boolean resumeWorkflowInstance(String workflowInstId) throws AvroRemoteException {
        engine.resumeWorkflowInstance(workflowInstId);
        return true;
    }



    @Override
    public double getWorkflowWallClockMinutes(String workflowInstId) throws AvroRemoteException {
        return engine.getWallClockMinutes(workflowInstId);
    }

    @Override
    public double getWorkflowCurrentTaskWallClockMinutes(String workflowInstId) throws AvroRemoteException {
        return engine.getCurrentTaskWallClockMinutes(workflowInstId);
    }

    @Override
    public int getNumWorkflowInstancesByStatus(String status) throws AvroRemoteException {
        try {
            return engine.getInstanceRepository().getNumWorkflowInstancesByStatus(
                    status);
        } catch (InstanceRepositoryException e) {
            throw oodtError(e);
        }
    }

    @Override
    public int getNumWorkflowInstances() throws AvroRemoteException {
        try {
            return engine.getInstanceRepository().getNumWorkflowInstances();
        } catch (InstanceRepositoryException e) {
            e.printStackTrace();
            throw oodtError(e);
        }
    }


    @Override
    public List<AvroWorkflowInstance> getWorkflowInstancesByStatus(String status) throws AvroRemoteException {
        List workflowInsts = null;
        List<AvroWorkflowInstance> avroWorkflowInstances = new ArrayList<AvroWorkflowInstance>();
        try {
            workflowInsts = engine.getInstanceRepository()
                    .getWorkflowInstancesByStatus(status);
        } catch (Exception e) {
            logger.error("Error when obtaining workflow instances by status: {}, error: {}", status, e.getMessage());
            return avroWorkflowInstances; //AvroTypeFactory.getAvroWorkflowInstances(workflowInsts);
        }

        if (workflowInsts != null) {
            logger.debug("Retrieved {} instances by status: {}", workflowInsts.size(), status);

            try {
                for (WorkflowInstance wi :(List<WorkflowInstance>) workflowInsts){
                    // pick up the description of the workflow
                    Workflow wDesc = repo.getWorkflowById(wi.getWorkflow().getId());
                    // TODO: hack for now, fix this, we shouldn't have to cast
                    // here, bad
                    // design
                    if(wDesc == null){
                        //Possible dynamic workflow for instance
                        //reconsitute it from cache
                        wDesc = wi.getWorkflow();
                        repo.addWorkflow(wDesc);
                    }
                    wi.setWorkflow(wDesc);
                    avroWorkflowInstances.add(AvroTypeFactory.getAvroWorkflowInstance(wi));

                }
            } catch (Exception e) {
                logger.error("Error when getting workflow instances by status: {}, error: {}", status, e.getMessage());
                throw oodtError(e, "Exception getting workflow instances by statusfrom workflow engine: Message: "
                                + e.getMessage());
            }
        }

        return avroWorkflowInstances;

    }

    @Override
    public List<AvroWorkflowInstance> getWorkflowInstances() throws AvroRemoteException {
        List workflowInsts = null;
        List<AvroWorkflowInstance> avroWorkflowInstances = new ArrayList<AvroWorkflowInstance>();

        try {
            workflowInsts = engine.getInstanceRepository()
                    .getWorkflowInstances();
        } catch (Exception e) {
            logger.error("Exception getting workflow instances. Message: {}", e.getMessage());
            return avroWorkflowInstances;
        }

        if (workflowInsts != null) {
            logger.debug("Retrieved {} workflow instances", workflowInsts.size());

            try {
                for (WorkflowInstance wi :(List<WorkflowInstance>) workflowInsts){
                    // pick up the description of the workflow
                    Workflow wDesc = repo.getWorkflowById(wi.getWorkflow()
                            .getId());
                    // TODO: hack for now, fix this, we shouldn't have to cast
                    // here, bad
                    // design
                    if(wDesc == null){
                        //Possible dynamic workflow for instance
                        //reconsitute it from cache
                        wDesc = wi.getWorkflow();
                        repo.addWorkflow(wDesc);
                    }
                    wi.setWorkflow(wDesc);
                    avroWorkflowInstances.add(AvroTypeFactory.getAvroWorkflowInstance(wi));

                }
                return avroWorkflowInstances;
            } catch (Exception e) {
                logger.error("Error getting workflow instances", e);
                throw oodtError(e, "Exception getting workflow instances from workflow engine: Message: "
                                + e.getMessage());
            }
        }
        // Empty repo used to return null. Avro cannot write a null array
        // ("array is null"), so OPSUI's workflow-filtered instances call 500s.
        return avroWorkflowInstances;

    }

    @Override
    public List<AvroWorkflow> getWorkflows() throws AvroRemoteException {
        List workflowList = null;
        try {
            workflowList = repo.getWorkflows();
        } catch (RepositoryException e) {
            throw oodtError(e);
        }

        if (workflowList != null) {
            logger.debug("Retrieved {} workflows", workflowList.size());

            try {
                return AvroTypeFactory.getAvroWorkflows(workflowList);
            } catch (Exception e) {
                logger.error("Unable to get workflows: {}", e.getMessage());
                throw oodtError(e, "Exception getting workflows from repository: Message: "
                                + e.getMessage());
            }

        } else
            return null;

    }

    @Override
    public AvroWorkflowTask getTaskById(String taskId) throws AvroRemoteException {
        try {
            WorkflowTask t = repo.getWorkflowTaskById(taskId);
            return AvroTypeFactory.getAvroWorkflowTask(t);
        } catch (Exception e) {
            logger.error("Error when getting task by ID: {} - {}", taskId, e.getMessage());
            throw oodtError(e, "Exception getting task by id: Message: " + e.getMessage());
        }
    }

    @Override
    public AvroWorkflowCondition getConditionById(String conditionId) throws AvroRemoteException {
        try {
            WorkflowCondition c = repo.getWorkflowConditionById(conditionId);
            return AvroTypeFactory.getAvroWorkflowCondition(c);
        } catch (Exception e) {
            logger.error("Error when getting condition by ID: {} - {}", conditionId, e.getMessage());
            throw oodtError(e, "Exception getting condition by id: Message: " + e.getMessage());
        }

    }

    @Override
    public AvroWorkflow getWorkflowById(String workflowId) throws AvroRemoteException {
        try {
            Workflow workflow = repo.getWorkflowById(workflowId);
            return AvroTypeFactory.getAvroWorkflow(workflow);
        } catch (Exception e) {
            logger.error("Error getting workflow by ID: {} - {}", workflowId, e.getMessage());
            throw oodtError(e, "Exception getting workflow by id from the repository: Message: "
                            + e.getMessage());
        }

    }

    @Override
    public synchronized boolean updateMetadataForWorkflow(String workflowInstId, Map<String, Object> metadata) throws AvroRemoteException {
        Metadata met = new Metadata();
        met.addMetadata(AvroTypeFactory.getMetadata(metadata));
        return this.engine.updateMetadata(workflowInstId, met);
    }

    @Override
    public synchronized boolean updateWorkflowInstance(AvroWorkflowInstance instance) throws AvroRemoteException {
        WorkflowInstance wInst = AvroTypeFactory.getWorkflowInstance(instance);
        return doUpdateWorkflowInstance(wInst);
    }

    @Override
    public boolean setWorkflowInstanceCurrentTaskStartDateTime(String wInstId, String startDateTimeIsoStr) throws AvroRemoteException {
        WorkflowInstance wInst = null;
        try {
            wInst = this.engine.getInstanceRepository()
                    .getWorkflowInstanceById(wInstId);
        } catch (InstanceRepositoryException e) {
            e.printStackTrace();
            return false;
        }
        wInst.setCurrentTaskStartDateTimeIsoStr(startDateTimeIsoStr);
        return doUpdateWorkflowInstance(wInst);

    }

    @Override
    public synchronized boolean setWorkflowInstanceCurrentTaskEndDateTime(String wInstId, String endDateTimeIsoStr) throws AvroRemoteException {
        WorkflowInstance wInst = null;
        try {
            wInst = this.engine.getInstanceRepository()
                    .getWorkflowInstanceById(wInstId);
        } catch (InstanceRepositoryException e) {
            e.printStackTrace();
            return false;
        }
        wInst.setCurrentTaskEndDateTimeIsoStr(endDateTimeIsoStr);
        return doUpdateWorkflowInstance(wInst);

    }

    public static void loadProperties() throws FileNotFoundException, IOException {
        String configFile = System.getProperty(PROPERTIES_FILE_PROPERTY);
        if (configFile != null) {
            logger.info("Loading Workflow Manager Configuration Properties from: {}", configFile);
            System.getProperties().load(new FileInputStream(new File(configFile)));
        }
    }



    @Override
    public synchronized boolean updateWorkflowInstanceStatus(String workflowInstId, String status) throws AvroRemoteException {
        logger.debug("Updating workflow instance[{}] status to {}", workflowInstId, status);
        WorkflowInstance wInst = null;
        try {
            wInst = engine.getInstanceRepository().getWorkflowInstanceById(workflowInstId);
        } catch (Exception e) {
            logger.error("Unable to updated workflow instance [{}] status to {} - {}",
                    workflowInstId, status, e.getMessage());
            throw oodtError(e);
        }

        wInst.setStatus(status);
        return doUpdateWorkflowInstance(wInst);
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    private static WorkflowEngine getWorkflowEngineFromProperty() {
        return getWorkflowEngineFromClassName(System.getProperty(
                WORKFLOW_ENGINE_FACTORY_PROPERTY,
                ThreadPoolWorkflowEngineFactory.class.getCanonicalName()));
    }

    private static WorkflowRepository getWorkflowRepositoryFromProperty() {
        return getWorkflowRepositoryFromClassName(System.getProperty(
                WORKFLOW_REPOSITORY_FACTORY_PROPERTY,
                DataSourceWorkflowRepositoryFactory.class.getCanonicalName()));
    }

    private String getHostname() {
        try {
            // Get hostname by textual representation of IP address
            InetAddress addr = InetAddress.getLocalHost();
            // Get the host name
            String hostname = addr.getHostName();
            return hostname;
        } catch (UnknownHostException e) {
        }
        return null;
    }

    private URL safeGetUrlFromString(String urlStr) {
        try {
            return new URL(urlStr);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private boolean doUpdateWorkflowInstance(WorkflowInstance wInst) {
        logger.debug("Updating workflow instance: {}", wInst.getId());
        try {
            engine.getInstanceRepository().updateWorkflowInstance(wInst);
            return true;
        } catch (InstanceRepositoryException e) {
            logger.error("Error when updating workflow instance: {}", wInst.getId());
            return false;
        }
    }

    private void populateWorkflows(List wInsts) {
        if (wInsts != null && wInsts.size() > 0) {
            for (Iterator i = wInsts.iterator(); i.hasNext();) {
                WorkflowInstance wInst = (WorkflowInstance) i.next();
                if(wInst.getWorkflow() == null ||
                        (wInst.getWorkflow() != null &&
                                (wInst.getWorkflow().getName() == null ||
                                        wInst.getWorkflow().getId() == null))){
                    wInst.setWorkflow(safeGetWorkflowById(wInst.getWorkflow()
                            .getId()));
                }
                else{
                    // check to see if the workflow exists in the
                    // repo
                    try {
                        if(repo.getWorkflowById(wInst.getWorkflow().getId()) == null){
                            repo.addWorkflow(wInst.getWorkflow());
                        }
                    } catch (RepositoryException e) {
                        logger.error("Error when attempting to look up workflow[{}] in populate workflows. Message:",
                                wInst.getWorkflow().getId(), e.getMessage());
                    }
                }
            }
        }
    }

    private Workflow safeGetWorkflowById(String workflowId) {
        logger.debug("Safe get workflow by ID: {}", workflowId);
        try {
            return repo.getWorkflowById(workflowId);
        } catch (Exception e) {
            logger.error("Error getting workflow by id: [{}], error: {}", workflowId, e.getMessage());
            return new Workflow();
        }
    }


    /**
     * Describe a server-side failure in the shape the protocol declares.
     *
     * The kind lets a caller branch without reading prose; the class name goes
     * alongside it so the exact exception is still visible when the kind is
     * coarse. FailureKinds owns the vocabulary, shared by every protocol.
     */
    private static OodtError oodtError(Throwable cause) {
        OodtError error = new OodtError();
        error.setKind(OodtFailureKind.valueOf(FailureKinds.classify(cause)));
        error.setType(cause.getClass().getName());
        error.setDetail(cause.getMessage());
        return error;
    }

    /**
     * As above, but keeping a message the caller composed. The kind and the
     * class name still come from the cause, so context is added rather than
     * traded for it.
     */
    private static OodtError oodtError(Throwable cause, String detail) {
        OodtError error = oodtError(cause);
        error.setDetail(detail);
        return error;
    }
}
