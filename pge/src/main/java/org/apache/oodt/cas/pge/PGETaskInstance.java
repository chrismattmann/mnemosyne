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
package org.apache.oodt.cas.pge;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import org.apache.commons.lang.Validate;
import org.apache.oodt.cas.crawl.AutoDetectProductCrawler;
import org.apache.oodt.cas.crawl.ProductCrawler;
import org.apache.oodt.cas.crawl.StdProductCrawler;
import org.apache.oodt.cas.crawl.status.IngestStatus;
import org.apache.oodt.cas.crawl.structs.exceptions.CrawlerActionException;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;
import org.apache.oodt.cas.filemgr.structs.exceptions.ConnectionException;
import org.apache.oodt.cas.filemgr.structs.exceptions.DataTransferException;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.metadata.SerializableMetadata;
import org.apache.oodt.cas.metadata.exceptions.CasMetadataException;
import org.apache.oodt.cas.metadata.exceptions.MetExtractionException;
import org.apache.oodt.cas.metadata.exceptions.MetExtractorConfigReaderException;
import org.apache.oodt.cas.metadata.exceptions.NamingConventionException;
import org.apache.oodt.cas.metadata.filenaming.PathUtilsNamingConvention;
import org.apache.oodt.cas.pge.config.DynamicConfigFile;
import org.apache.oodt.cas.pge.config.OutputDir;
import org.apache.oodt.cas.pge.config.PgeConfig;
import org.apache.oodt.cas.pge.config.RegExprOutputFiles;
import org.apache.oodt.cas.pge.config.XmlFilePgeConfigBuilder;
import org.apache.oodt.cas.pge.exceptions.PGEException;
import org.apache.oodt.cas.pge.metadata.PgeMetadata;
import org.apache.oodt.cas.pge.metadata.PgeProgress;
import org.apache.oodt.cas.pge.metadata.PgeTaskMetKeys;
import org.apache.oodt.cas.pge.staging.FileManagerFileStager;
import org.apache.oodt.cas.pge.staging.FileStager;
import org.apache.oodt.cas.pge.writers.PcsMetFileWriter;
import org.apache.oodt.cas.pge.writers.SciPgeConfigFileWriter;
import org.apache.oodt.cas.workflow.exceptions.WorkflowException;
import org.apache.oodt.cas.workflow.metadata.CoreMetKeys;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.WorkflowTaskInstanceException;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;
import org.apache.oodt.cas.workflow.system.rpc.RpcCommunicationFactory;
import org.apache.oodt.cas.workflow.util.ScriptFile;
import org.apache.oodt.commons.exceptions.CommonsException;
import org.apache.oodt.commons.exec.ExecUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.FileSystemXmlApplicationContext;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.ParseException;
import java.util.Date;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

import static org.apache.oodt.cas.pge.metadata.PgeTaskMetKeys.*;
import static org.apache.oodt.cas.pge.metadata.PgeTaskStatus.CONF_FILE_BUILD;
import static org.apache.oodt.cas.pge.metadata.PgeTaskStatus.CRAWLING;
import static org.apache.oodt.cas.pge.metadata.PgeTaskStatus.RUNNING_PGE;
import static org.apache.oodt.cas.pge.metadata.PgeTaskStatus.STAGING_INPUT;
import static org.apache.oodt.cas.pge.util.GenericPgeObjectFactory.createConfigFilePropertyAdder;
import static org.apache.oodt.cas.pge.util.GenericPgeObjectFactory.createFileStager;
import static org.apache.oodt.cas.pge.util.GenericPgeObjectFactory.createPgeConfigBuilder;
import static org.apache.oodt.cas.pge.util.GenericPgeObjectFactory.createSciPgeConfigFileWriter;


/**
 * Runs a CAS-style Product Generation Executive based on the PCS Wrapper
 * Architecture from mattmann et al. on OCO.
 *
 * @author mattmann (Chris Mattmann)
 * @author bfoster (Brian Foster)
 */
public class PGETaskInstance implements WorkflowTaskInstance {

   protected Logger logger = LoggerFactory.getLogger(PGETaskInstance.class);

   /**
    * This JUL logger is kept for now to avoid large scale changes in logging dependencies.
    * Should be removed in future with an alternative to address the logging dependencies.
    */
   protected java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger(PGETaskInstance.class.getName());

   private WorkflowManagerClient wmClient;
   private String workflowInstId;
   protected PgeMetadata pgeMetadata;
   protected PgeConfig pgeConfig;
   /**
    * The metadata object {@link #run} was given. W1 TaskJob persists this
    * at task end (replace), so progress and JobDir have to live on it or
    * a previous task's {@code PGETask_*} stamps come back.
    */
   protected Metadata workflowMetadata;
   /**
    * Written by the progress watcher and read by the thread running the
    * task, so it is published rather than left to chance.
    */
   private volatile PgeProgress lastProgress;
   static final long PROGRESS_POLL_MS = 2000L;

   protected PGETaskInstance() {}

   @Override
   public void run(Metadata metadata, WorkflowTaskConfiguration config) throws WorkflowTaskInstanceException {
      logger.debug("Starting PGE Task instance...");
      try {
         // Initialize CAS-PGE.
         workflowMetadata = metadata;
         pgeMetadata = createPgeMetadata(metadata, config);
         pgeConfig = createPgeConfig();
         runPropertyAdders();
         workflowInstId = getWorkflowInstanceId();
         bindProgressContext();

         // use workflow ID specific logger from now on
         logger = LoggerFactory.getLogger(PGETaskInstance.class.getName() + "." + workflowInstId);
         logger.debug("Workflow instance ID is [{}]", workflowInstId);
         julLogger = createLogger();

         // Write out PgeMetadata.
         dumpMetadataIfRequested();

         // Setup the PGE.
         createExeDir();
         createOuputDirsIfRequested();
         updateStatus(CONF_FILE_BUILD.getWorkflowStatusName());
         createDynamicConfigFiles();
         updateStatus(STAGING_INPUT.getWorkflowStatusName());
         stageFiles();

         // Run the PGE.
         runPge();

         // Ingest products.
         ProductCrawler productCrawler = createProductCrawler();
         runIngestCrawler(productCrawler);
         productCrawler.shutdown();

         // Commit dynamic metadata.
         updateDynamicMetadata();
      } catch (Exception e) {
         logger.error("PGETask FAILED!!! Error occurred when running", e);
         throw new WorkflowTaskInstanceException("PGETask FAILED!!! : " + e.getMessage(), e);
      }
   }

   /** The state a PGE phase is reported as when the engine does not know it. */
   protected static final String RUNNING_STATE = "Executing";

   /** Statuses the manager's engine declares; null until asked, empty if it cannot say. */
   private List<String> supportedStatuses = null;

   protected void updateStatus(String status) throws Exception {
      String reported = reportableStatus(status);
      if (!reported.equals(status)) {
         logger.info("Updating status to workflow as [" + reported
             + "] for PGE phase [" + status + "]");
      } else {
         logger.info("Updating status to workflow as [" + status + "]");
      }
      if (!getWorkflowManagerClient().updateWorkflowInstanceStatus(workflowInstId, reported)) {
         throw new PGEException("Failed to update workflow status : client returned false");
      }
   }

   /**
    * The status to actually report for a PGE phase.
    *
    * <p>
    * A PGE has phases of its own -- building its config file, staging input,
    * running, crawling -- and has always published them as the workflow
    * instance's status. Those names are the older engine's vocabulary, and a
    * status the running engine's lifecycle cannot categorise is one
    * WorkflowProcessorQueue drops: it guards on a non-null category, so a PGE
    * was invisible to the task querier for as long as it ran.
    * </p>
    *
    * <p>
    * So ask. A manager whose lifecycle declares the phase is told the phase,
    * which is every deployment on the older engine, so their behaviour is
    * unchanged by construction rather than by care. A manager whose lifecycle
    * does not is told the state the phase actually is: all of these are the
    * task running.
    * </p>
    *
    * <p>
    * The phase itself is not lost. It belongs on the instance's metadata,
    * where detail about what a task is doing goes, rather than in the field
    * the engine uses to decide what to do next.
    * </p>
    */
   protected String reportableStatus(String phase) {
      if (phase == null) {
         return null;
      }
      if (supportedStatuses == null) {
         supportedStatuses = loadSupportedStatuses();
      }
      if (supportedStatuses.isEmpty() || supportedStatuses.contains(phase)) {
         // Either the lifecycle knows this phase, or the manager could not
         // say what it knows. Neither is a reason to start renaming things.
         return phase;
      }
      return RUNNING_STATE;
   }

   /**
    * The statuses the manager's engine declares, or empty if it cannot say.
    *
    * <p>
    * Overridable so the decision above can be tested against a known
    * lifecycle without a manager to ask.
    * </p>
    */
   protected List<String> loadSupportedStatuses() {
      try {
         List<?> reported = getWorkflowManagerClient().getSupportedWorkflowStatuses();
         List<String> statuses = new ArrayList<String>();
         if (reported != null) {
            for (Object status : reported) {
               if (status != null) {
                  statuses.add(status.toString());
               }
            }
         }
         return statuses;
      } catch (Exception e) {
         // An older manager cannot be asked. Report as this PGE always has.
         logger.info("Workflow manager did not report its statuses ("
             + e.getMessage() + "); reporting PGE phases unchanged");
         return new ArrayList<String>();
      }
   }

   /**
    * Drop a previous task's {@code PGETask_*} stamps and persist this task's
    * {@code JobDir} so OPSUI peeks the current {@code .progress} file.
    */
   protected void bindProgressContext() {
      // Whether anything stale was actually inherited decides below whether
      // this costs a round-trip at all.
      boolean inherited = PgeProgress.fromMetadata(workflowMetadata) != null;
      PgeProgress.clearFrom(workflowMetadata);
      copyJobDir(workflowMetadata);
      discardPreviousProgressFile();
      if (inherited) {
         // Only the shared context held by the workflow manager still has the
         // previous task's stamps at this point, and only clearing those needs
         // the network. A task that inherited nothing -- the common case, and
         // every first task in a workflow -- starts without two extra calls
         // against a manager whose handler pool is worth not spending.
         persistProgress(null);
      }
   }

   /**
    * Remove a previous run's {@code .progress} before this one starts.
    *
    * <p>
    * The peek prefers this file over the metadata keys, so leaving one behind
    * hands the next task a finished bar to display: an {@code exeDir} that is
    * reused -- a retry, or one not templated per instance -- would show
    * 653/653 for a task that has not started, which is the bug this change
    * exists to fix, pointed the other way. Clearing the keys is not enough
    * while the file outranks them.
    * </p>
    */
   protected void discardPreviousProgressFile() {
      if (pgeConfig == null || pgeConfig.getExeDir() == null) {
         return;
      }
      File stale = new File(pgeConfig.getExeDir(), PgeProgress.FILE_NAME);
      if (stale.exists() && !stale.delete()) {
         logger.warn("Could not remove a previous run's progress file: {}",
               stale.getAbsolutePath());
      }
   }

   /**
    * While the script runs, publish {@code .progress} to the workflow manager
    * so OPSUI can draw a bar. Scripts that never write the file are a no-op.
    *
    * <p>
    * This thread only ever touches metadata of its own: it posts the current
    * shared context back with the progress keys overlaid, and records what it
    * saw in {@link #lastProgress}. The metadata object {@link #run} was given
    * belongs to the thread running the task, which copies the last progress
    * onto it in {@link #adoptProgressIntoTaskMetadata} once this watcher has
    * been stopped.
    */
   protected Thread startProgressWatcher() {
      Thread watcher = new Thread(new Runnable() {
         @Override
         public void run() {
            while (!Thread.currentThread().isInterrupted()) {
               reportProgress();
               try {
                  Thread.sleep(PROGRESS_POLL_MS);
               } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
               }
            }
         }
      }, "pge-progress-" + workflowInstId);
      watcher.setDaemon(true);
      watcher.start();
      return watcher;
   }

   protected void stopProgressWatcher(Thread watcher) {
      if (watcher == null) {
         return;
      }
      watcher.interrupt();
      try {
         watcher.join(1000L);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }
   }

   protected void reportProgress() {
      if (pgeConfig == null || pgeConfig.getExeDir() == null) {
         return;
      }
      PgeProgress next = PgeProgress.readFile(new File(pgeConfig.getExeDir(), PgeProgress.FILE_NAME));
      if (next == null || next.sameAs(lastProgress)) {
         return;
      }
      persistProgress(next);
      lastProgress = next;
   }

   /**
    * Copy the progress last seen onto the metadata object {@link #run} was
    * given, for the W1 TaskJob end-persist to carry.
    *
    * <p>
    * Only the thread running the task calls this. The watcher used to write
    * into that object itself, but {@code stopProgressWatcher} joins with a
    * one-second bound -- a bound, not a guarantee -- so a watcher parked in a
    * workflow manager call outlives the join and would still be writing while
    * this thread hands the same object to the end-persist. {@code Metadata}
    * is not thread-safe, and a workflow manager that answers slowly is
    * exactly the condition that makes the overlap likely rather than rare.
    * </p>
    */
   protected void adoptProgressIntoTaskMetadata() {
      PgeProgress snapshot = lastProgress;
      if (snapshot != null) {
         snapshot.applyTo(workflowMetadata);
      }
      copyJobDir(workflowMetadata);
   }

   /**
    * Overlay progress and this task's JobDir onto the live shared context.
    * {@code progress} may be null: that is bind, which only clears stale
    * keys and publishes JobDir.
    */
   protected void persistProgress(PgeProgress progress) {
      try {
         WorkflowManagerClient client = getWorkflowManagerClient();
         Metadata current = null;
         try {
            current = client.getWorkflowInstanceMetadata(workflowInstId);
         } catch (Exception e) {
            logger.debug("No instance metadata yet for progress: {}", e.getMessage());
         }
         if (current == null) {
            current = new Metadata();
         }
         PgeProgress.clearFrom(current);
         copyJobDir(current);
         if (progress != null) {
            progress.applyTo(current);
         }
         client.updateMetadataForWorkflow(workflowInstId, current);
      } catch (Exception e) {
         logger.warn("Could not report PGE progress: {}", e.getMessage());
      }
   }

   protected void copyJobDir(Metadata met) {
      if (met == null || pgeMetadata == null) {
         return;
      }
      String jobDir = pgeMetadata.getMetadata("JobDir");
      if (jobDir != null && jobDir.length() > 0) {
         met.replaceMetadata("JobDir", jobDir);
      }
      String outputDir = pgeMetadata.getMetadata("JobOutputDir");
      if (outputDir != null && outputDir.length() > 0) {
         met.replaceMetadata("JobOutputDir", outputDir);
      }
   }

   protected java.util.logging.Logger createLogger() throws IOException, PGEException {
      File logDir = new File(pgeConfig.getExeDir(), "logs");
      if (!(logDir.exists() || logDir.mkdirs())) {
         throw new PGEException("mkdirs for logs directory return false");
      }

      java.util.logging.Logger logger = java.util.logging.Logger.getLogger(PGETaskInstance.class.getName() + "." + workflowInstId);
      // TODO Need to find an alternative way to add a dynamic handler to write workflowInstance logs to a separate file
      FileHandler handler = new FileHandler(new File(logDir, createLogFileName()).getAbsolutePath());
      handler.setEncoding("UTF-8");
      handler.setFormatter(new SimpleFormatter());
      logger.addHandler(handler);
      return logger;
   }

   protected String createLogFileName() {
      String filenamePattern = pgeMetadata.getMetadata(LOG_FILENAME_PATTERN);
      if (filenamePattern != null) {
         return filenamePattern;
      } else {
         return pgeMetadata.getMetadata(NAME) + "." + System.currentTimeMillis() + ".log";
      }
   }

   protected PgeMetadata createPgeMetadata(Metadata dynMetadata, WorkflowTaskConfiguration config) {
      logger.info("Converting workflow configuration to static metadata");
      logger.debug("PGE Metadata Config: {}", config.getProperties().entrySet());
      logger.debug("PGE Metadata Dynamic Metadata: {}", dynMetadata.getMap());
      Metadata staticMetadata = new Metadata();
      for (Object objKey : config.getProperties().keySet()) {
         String key = (String) objKey;
         PgeTaskMetKeys metKey = PgeTaskMetKeys.getByName(key);
         if (metKey != null && metKey.isVector()) {
            List<String> values = Lists.newArrayList(
                  Splitter.on(",").trimResults()
                  .omitEmptyStrings()
                  .split(config.getProperty(key)));
            logger.debug("Adding static metadata: key = [{}] value = {}", key, values);
            staticMetadata.addMetadata(key, values);
         } else {
            String value = config.getProperty(key);
            logger.debug("Adding static metadata: key = [{}] value = {}", key, value);
            staticMetadata.addMetadata(key, value);
         }
      }

      logger.info("Loading workflow context metadata...");
      for (String key : dynMetadata.getAllKeys()) {
         logger.debug("Adding dynamic metadata: key = [{}] value = {}", key, dynMetadata.getAllMetadata(key));
      }
      return new PgeMetadata(staticMetadata, dynMetadata);
   }

   protected PgeConfig createPgeConfig() throws Exception {
      logger.info("Create PgeConfig...");
      String pgeConfigBuilderClass = pgeMetadata.getMetadata(PGE_CONFIG_BUILDER);
      if (pgeConfigBuilderClass != null) {
         logger.info("Using PgeConfigBuilder: {}", pgeConfigBuilderClass);
         return createPgeConfigBuilder(pgeConfigBuilderClass, logger).build(pgeMetadata);
      } else {
         logger.info("Using default PgeConfigBuilder: {}", XmlFilePgeConfigBuilder.class.getCanonicalName());
         return new XmlFilePgeConfigBuilder().build(pgeMetadata);
      }
   }

   protected void runPropertyAdders() throws PGEException {
      try {
         logger.info("Loading/Running property adders...");
         List<String> propertyAdders = pgeMetadata.getAllMetadata(PROPERTY_ADDERS);
         if (propertyAdders != null) {
            for (String propertyAdder : propertyAdders) {
               runPropertyAdder(loadPropertyAdder(propertyAdder));
            }
         } else {
            logger.info("No property adders specified");
         }
      } catch (Exception e) {
         logger.error("Error occurred when running property adders", e);
         throw new PGEException("Failed to instantiate/run Property Adders : " + e.getMessage(), e);
      }
   }

   protected ConfigFilePropertyAdder loadPropertyAdder(String propertyAdderClasspath) {
      logger.debug("Loading property adder: {}", propertyAdderClasspath);
      return createConfigFilePropertyAdder(propertyAdderClasspath, logger);
   }

   protected void runPropertyAdder(ConfigFilePropertyAdder propAdder) {
      logger.info("Running property adder: {}", propAdder.getClass().getCanonicalName());
      propAdder.addConfigProperties(pgeMetadata, pgeConfig.getPropertyAdderCustomArgs());
   }

   protected WorkflowManagerClient getWorkflowManagerClient() throws MalformedURLException {
      if (this.wmClient == null) {
         String url = pgeMetadata.getMetadata(WORKFLOW_MANAGER_URL);
         logger.info("Creating WorkflowManager client for url [" + url + "]");
         Validate.notNull(url, "Must specify " + WORKFLOW_MANAGER_URL);
         this.wmClient = RpcCommunicationFactory.createClient(new URL(url));
      }

      return this.wmClient;
   }

   protected String getWorkflowInstanceId() {
      String instanceId = pgeMetadata.getMetadata(CoreMetKeys.WORKFLOW_INST_ID);
      logger.debug("Workflow instanceId is [{}]", instanceId);
      Validate.notNull(instanceId, "Must specify " + CoreMetKeys.WORKFLOW_INST_ID);
      return instanceId;
   }

   protected void dumpMetadataIfRequested() throws IOException {
      if (Boolean.parseBoolean(pgeMetadata
            .getMetadata(DUMP_METADATA))) {
         new SerializableMetadata(pgeMetadata.asMetadata())
               .writeMetadataToXmlStream(new FileOutputStream(
                     getDumpMetadataPath()));
      }
   }

   protected String getDumpMetadataPath()  {
      return new File(pgeConfig.getExeDir()).getAbsolutePath() + "/"
            + getDumpMetadataName();
   }

   protected String getDumpMetadataName() {
      return "pgetask-metadata.xml";
   }

   protected void createExeDir() throws PGEException {
      logger.info("Creating PGE execution working directory: [{}]", pgeConfig.getExeDir());
      File executionDir = new File(pgeConfig.getExeDir());
      if (!(executionDir.exists() || executionDir.mkdirs())) {
         logger.warn("Unable to create execution working directory: {}", pgeConfig.getExeDir());
         throw new PGEException("mkdirs returned false for creating [" + pgeConfig.getExeDir() + "]");
      }
   }

   protected void createOuputDirsIfRequested() throws PGEException {
      for (OutputDir outputDir : pgeConfig.getOuputDirs()) {
         if (outputDir.isCreateBeforeExe()) {
            logger.info("Creating PGE file ouput directory: [{}]", outputDir.getPath());
            File dir = new File(outputDir.getPath());
            if (!(dir.exists() || dir.mkdirs())) {
               logger.warn("Unable to create output dirs: {}", outputDir.getPath());
               throw new PGEException("mkdir returned false for creating [" + outputDir.getPath() + "]");
            }
         }
      }
   }

   protected void stageFiles()
       throws PGEException, IOException, ConnectionException, CatalogException, URISyntaxException,
       DataTransferException, InstantiationException {
      if (pgeConfig.getFileStagingInfo() != null) {
         FileStager fileStager = getFileStager();
         logger.info("Starting file staging...");
         fileStager.stageFiles(pgeConfig.getFileStagingInfo(), pgeMetadata, logger);
      } else {
         logger.info("No files to stage.");
      }
   }

   protected FileStager getFileStager() {
      String fileStagerClass = pgeMetadata.getMetadata(FILE_STAGER);
      if (fileStagerClass != null) {
         logger.info("Loading FileStager [{}]", fileStagerClass);
         return createFileStager(fileStagerClass, logger);
      } else {
         logger.info("Using default FileStager ["
               + FileManagerFileStager.class.getCanonicalName() + "]");
         return new FileManagerFileStager();
      }
   }

   protected void createDynamicConfigFiles() throws IOException, PGEException {
      logger.info("Starting creation of sci pge config files...");
      for (DynamicConfigFile dynamicConfigFile : pgeConfig
            .getDynamicConfigFiles()) {
         createDynamicConfigFile(dynamicConfigFile);
      }
      logger.info("Successfully wrote all sci pge config files!");
   }

   protected void createDynamicConfigFile(DynamicConfigFile dynamicConfigFile)
       throws PGEException, IOException {
      Validate.notNull(dynamicConfigFile, "dynamicConfigFile cannot be null");
      logger.debug("Starting creation of sci pge config file: {}", dynamicConfigFile.getFilePath());

      // Create parent directory if it doesn't exist.
      File parentDir = new File(dynamicConfigFile.getFilePath()).getParentFile();
      if (!(parentDir.exists() || parentDir.mkdirs())) {
         logger.warn("Unable to create directory({}) with sci pge config file", dynamicConfigFile.getFilePath());
         throw new PGEException("Failed to create directory where sci pge config file ["
               + dynamicConfigFile.getFilePath() + "] was to be written");
      }

      // Load writer and write file.
      logger.debug("Loading writer class for sci pge config file [{}]", dynamicConfigFile.getFilePath());
      SciPgeConfigFileWriter writer = createSciPgeConfigFileWriter(dynamicConfigFile.getWriterClass(), logger);
      logger.debug("Loaded writer [{}] for sci pge config file [{}]",
              writer.getClass().getCanonicalName(),dynamicConfigFile.getFilePath());
      logger.info("Writing sci pge config file [{}]", dynamicConfigFile.getFilePath());
      File configFile = writer.createConfigFile(dynamicConfigFile.getFilePath(),
              pgeMetadata.asMetadata(), dynamicConfigFile.getArgs());
      if (!configFile.exists()) {
         logger.warn("Failed to create config file '{}'. File doesn't exist", configFile);
         throw new PGEException("Writer failed to create config file [" + configFile + "], exists returned false");
      }
   }

   protected ScriptFile buildPgeRunScript() {
      logger.debug("Creating PGE run script for shell [{}] with contents: {}",
              pgeConfig.getShellType(), pgeConfig.getExeCmds());
      ScriptFile sf = new ScriptFile(pgeConfig.getShellType());
      sf.setCommands(pgeConfig.getExeCmds());
      return sf;
   }

   protected File getScriptPath() {
      File script = new File(pgeConfig.getExeDir(), getPgeScriptName());
      logger.debug("Script file with be written to [{}]", script);
      return script;
   }

   protected String getPgeScriptName() {
      String pgeScriptName = "sciPgeExeScript_" + pgeMetadata.getMetadata(NAME);
      logger.debug("Generated script file name [{}]", pgeScriptName);
      return pgeScriptName;
   }

   protected void runPge() throws Exception {
      ScriptFile sf = null;
      try {
         long startTime = System.currentTimeMillis();
         logger.info("PGE start time [{}]", new Date(startTime));

         // create script to run
         sf = buildPgeRunScript();
         sf.writeScriptFile(getScriptPath().getAbsolutePath());

         // run script and evaluate whether success or failure
         updateStatus(RUNNING_PGE.getWorkflowStatusName());
         logger.debug("Starting execution of PGE: {}", sf.getCommands());
         Thread progressWatcher = startProgressWatcher();
         try {
            if (!wasPgeSuccessful(ExecUtils.callProgram(
                  pgeConfig.getShellType() + " " + getScriptPath(), julLogger,
                  new File(pgeConfig.getExeDir()).getAbsoluteFile()))) {
               logger.error("PGE didn't finish successfully: {}", sf);
               throw new RuntimeException("Pge didn't finish successfully");
            } else {
               logger.info("Successfully completed running script file: '{}'", sf);
            }
         } finally {
            stopProgressWatcher(progressWatcher);
            reportProgress();
            adoptProgressIntoTaskMetadata();
         }

         long endTime = System.currentTimeMillis();
         logger.info("PGE end time [{}]", new Date(startTime));

         long runTime = endTime - startTime;
         logger.info("PGE runtime in millis [{}]", runTime);

         pgeMetadata.replaceMetadata(PGE_RUNTIME, Long.toString(runTime));
      } catch (WorkflowException | IOException e) {
         logger.error("Error when executing PGE commands: {}", sf);
        throw new PGEException("Exception when executing PGE commands '" +
                (sf.getCommands()) + "' : " + e.getMessage(), e);
      }
   }

   protected boolean wasPgeSuccessful(int returnCode) {
      return returnCode == 0;
   }

   protected void processOutput() throws IOException {
      logger.debug("Processing output");
      for (final OutputDir outputDir : this.pgeConfig.getOuputDirs()) {
         File[] createdFiles = new File(outputDir.getPath()).listFiles();
         if (createdFiles != null) {
            for (File createdFile : createdFiles) {
               Metadata outputMetadata = new Metadata();
               for (RegExprOutputFiles regExprFiles : outputDir
                       .getRegExprOutputFiles()) {
                  if (Pattern.matches(regExprFiles.getRegExp(), createdFile
                          .getName())) {
                     try {
                        PcsMetFileWriter writer = (PcsMetFileWriter) Class
                                .forName(regExprFiles.getConverterClass())
                                .newInstance();
                        outputMetadata.replaceMetadata(this.getMetadataForFile(
                                (regExprFiles.getRenamingConv() != null)
                                        ? createdFile = this.renameFile(createdFile, regExprFiles.getRenamingConv())
                                        : createdFile, writer, regExprFiles.getArgs()));
                     } catch (Exception e) {
                        logger.error("Failed to create metadata file for '{}'", createdFile, e);
                     }
                  }
               }

               if (outputMetadata.getAllKeys().size() > 0) {
                  this.writeFromMetadata(outputMetadata, createdFile.getAbsolutePath()
                          + "." + this.pgeMetadata.getMetadata(MET_FILE_EXT));
               }
            }
         }
      }
   }

	protected File renameFile(File file, PathUtilsNamingConvention renamingConv)
        throws NamingConventionException {
		Metadata curMetadata = this.pgeMetadata.asMetadata();
		curMetadata.replaceMetadata(renamingConv.getTmpReplaceMet());
		return renamingConv.rename(file, curMetadata);
	}

	protected Metadata getMetadataForFile(File sciPgeCreatedDataFile,
			PcsMetFileWriter writer, Object[] args)
        throws PGEException, MetExtractorConfigReaderException, ParseException, MetExtractionException,
        CommonsException, CasMetadataException, FileNotFoundException {
		return writer.getMetadataForFile(sciPgeCreatedDataFile,
				this.pgeMetadata, args);
	}

	protected void writeFromMetadata(Metadata metadata, String toMetFilePath)
			throws IOException {
		new SerializableMetadata(metadata, "UTF-8", false)
				.writeMetadataToXmlStream(new FileOutputStream(toMetFilePath));
	}

   protected ProductCrawler createProductCrawler()
           throws MalformedURLException, IllegalAccessException, CrawlerActionException, MetExtractionException,
           InstantiationException, FileNotFoundException, ClassNotFoundException {
      /* create a ProductCrawler based on whether or not the output dir specifies a MIME_EXTRACTOR_REPO */
      logger.info("Configuring ProductCrawler...");
      ProductCrawler crawler;
      if (pgeMetadata.getMetadata(MIME_EXTRACTOR_REPO) != null &&
              !pgeMetadata.getMetadata(MIME_EXTRACTOR_REPO).equals("")) {
         crawler = new AutoDetectProductCrawler();
         ((AutoDetectProductCrawler) crawler).
                 setMimeExtractorRepo(pgeMetadata.getMetadata(MIME_EXTRACTOR_REPO));
      } else {
         crawler = new StdProductCrawler();
      }

      crawler.setClientTransferer(pgeMetadata
              .getMetadata(INGEST_CLIENT_TRANSFER_SERVICE_FACTORY));
      crawler.setFilemgrUrl(pgeMetadata.getMetadata(INGEST_FILE_MANAGER_URL));
      String crawlerConfigFile = pgeMetadata.getMetadata(CRAWLER_CONFIG_FILE);
      if (!Strings.isNullOrEmpty(crawlerConfigFile)) {
         crawler.setApplicationContext(
                 new FileSystemXmlApplicationContext(crawlerConfigFile));
         List<String> actionIds = pgeMetadata.getAllMetadata(ACTION_IDS);
         if (actionIds != null) {
            crawler.setActionIds(actionIds);
         }
      }
      crawler.setRequiredMetadata(pgeMetadata.getAllMetadata(REQUIRED_METADATA));
      crawler.setCrawlForDirs(Boolean.parseBoolean(pgeMetadata
              .getMetadata(CRAWLER_CRAWL_FOR_DIRS)));
      crawler.setNoRecur(!Boolean.parseBoolean(
              pgeMetadata.getMetadata(CRAWLER_RECUR)));
      logger.debug("Passing Workflow Metadata to CAS-Crawler as global metadata . . .");
      crawler.setGlobalMetadata(pgeMetadata.asMetadata(PgeMetadata.Type.DYNAMIC));
      logger.debug("Created ProductCrawler [{}]", crawler.getClass().getCanonicalName());
      return crawler;
   }

   protected void runIngestCrawler(ProductCrawler crawler) throws Exception {
      // Determine if we need to create Metadata files
	   if (crawler instanceof StdProductCrawler){
		   this.processOutput();
	   }
	   
	   // Determine directories to crawl.
      List<File> crawlDirs = new LinkedList<File>();
      for (OutputDir outputDir : pgeConfig.getOuputDirs()) {
         crawlDirs.add(new File(outputDir.getPath()));
      }

      // Start crawlin...
      updateStatus(CRAWLING.getWorkflowStatusName());
      boolean attemptIngestAll = Boolean.parseBoolean(pgeMetadata
            .getMetadata(ATTEMPT_INGEST_ALL));
      for (File crawlDir : crawlDirs) {
         logger.info("Crawling for products in [" + crawlDir + "]");
         crawler.crawl(crawlDir);
         if (!attemptIngestAll) {
            verifyIngests(crawler);
         }
      }
      if (attemptIngestAll) {
         verifyIngests(crawler);
      }
   }

   protected void verifyIngests(ProductCrawler crawler) throws PGEException {
      julLogger.info("Verifying ingests successful...");
      boolean ingestsSuccess = true;
      String exceptionMsg = "";
      for (IngestStatus status : crawler.getIngestStatus()) {
         if (status.getResult().equals(IngestStatus.Result.FAILURE)) {
            exceptionMsg += (exceptionMsg.equals("") ? "" : " : ")
                  + "Failed to ingest product [file='"
                  + status.getProduct().getAbsolutePath() + "',result='"
                  + status.getResult() + "',msg='" + status.getMessage() + "']";
            ingestsSuccess = false;
         } else if (!status.getResult().equals(IngestStatus.Result.SUCCESS)) {
            julLogger.warning(String.format("Product was not ingested [file='%s',result='%s',msg='%s']",
                    status.getProduct().getAbsolutePath(), status.getResult(),status.getMessage()));
         }
      }

      if (!ingestsSuccess) {
         julLogger.severe("Ingest wasn't successful: " + exceptionMsg);
         throw new PGEException(exceptionMsg);
      } else {
         julLogger.info("Ingests were successful");
      }
   }

   protected void updateDynamicMetadata() throws Exception {
      logger.debug("Updating dynamic metadata ...");
      pgeMetadata.commitMarkedDynamicMetadataKeys();
      Metadata dynamic = pgeMetadata.asMetadata(PgeMetadata.Type.DYNAMIC);
      PgeProgress.clearFrom(dynamic);
      copyJobDir(dynamic);
      PgeProgress snapshot = lastProgress;
      if (snapshot == null && pgeConfig != null && pgeConfig.getExeDir() != null) {
         snapshot = PgeProgress.readFile(new File(pgeConfig.getExeDir(), PgeProgress.FILE_NAME));
      }
      if (snapshot != null) {
         snapshot.applyTo(dynamic);
         snapshot.applyTo(workflowMetadata);
      }
      copyJobDir(workflowMetadata);
      getWorkflowManagerClient()
              .updateMetadataForWorkflow(workflowInstId, dynamic);
   }

   public String getWorkflowInstId() {
      return workflowInstId;
   }

   public void setWorkflowInstId(String workflowInstId) {
      logger.debug("Set workflow instance ID: {}", workflowInstId);
      this.workflowInstId = workflowInstId;
   }

   public void setWmClient(WorkflowManagerClient wmClient) {
      this.wmClient = wmClient;
   }

   @Override
   public void finalize() throws IOException {
      logger.debug("Finalizing ...");
      if (wmClient != null) {
         wmClient.close();
         logger.debug("Workflow manager client closed");
      }
   }
}
