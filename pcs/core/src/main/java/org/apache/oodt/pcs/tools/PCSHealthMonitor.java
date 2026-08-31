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

package org.apache.oodt.pcs.tools;

//JDK imports
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

//APACHE imports
import org.apache.avro.ipc.NettyTransceiver;
import org.apache.avro.ipc.specific.SpecificRequestor;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.util.HashedWheelTimer;
import org.apache.oodt.cas.resource.system.extern.AvroRpcBatchStub;
import org.apache.oodt.cas.crawl.daemon.AvroRpcCrawlDaemonController;
import org.apache.oodt.cas.filemgr.metadata.CoreMetKeys;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.resource.structs.ResourceNode;
import org.apache.oodt.commons.date.DateUtils;
import org.apache.oodt.pcs.health.CrawlInfo;
import org.apache.oodt.pcs.health.CrawlPropertiesFile;
import org.apache.oodt.pcs.health.CrawlerHealth;
import org.apache.oodt.pcs.health.CrawlerStatus;
import org.apache.oodt.pcs.health.JobHealthStatus;
import org.apache.oodt.pcs.health.PCSDaemonStatus;
import org.apache.oodt.pcs.health.PCSHealthMonitorMetKeys;
import org.apache.oodt.pcs.health.PCSHealthMonitorReport;
import org.apache.oodt.pcs.health.WorkflowStatesFile;
import org.apache.oodt.cas.filemgr.util.RpcCommunicationFactory;
import org.apache.oodt.pcs.util.FileManagerUtils;
import org.apache.oodt.pcs.util.ResourceManagerUtils;
import org.apache.oodt.pcs.util.WorkflowManagerUtils;

/**
 * 
 * A tool to monitor the health of the PCS.
 * 
 * @author mattmann
 * @version $Revision$
 */
public final class PCSHealthMonitor implements CoreMetKeys,
    PCSHealthMonitorMetKeys {
  public static final double DOUBLE = 1000.0;
  public static final double DOUBLE1 = 1000.0;
  private static Logger LOG = Logger.getLogger(PCSHealthMonitor.class.getName());
  private static final ChannelFactory BATCH_STUB_CHANNEL_FACTORY =
      newSharedChannelFactory("pcs-health-batchstub-client");

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      @Override
      public void run() {
        BATCH_STUB_CHANNEL_FACTORY.releaseExternalResources();
      }
    }, "pcs-health-batchstub-client-shutdown"));
  }

  private FileManagerUtils fm;

  private final String fmUrlStr;

  private final String wmUrlStr;

  private final String rmUrlStr;

  private WorkflowManagerUtils wm;

  private ResourceManagerUtils rm;

  private CrawlPropertiesFile crawlProps;

  private WorkflowStatesFile statesFile;

  /**
   * Records where the three services are. It does not connect to them.
   *
   * <p>
   * It used to build all three clients here, so one unreachable service made
   * the monitor impossible to construct -- and with it the whole report,
   * including the sections for the two services that were answering perfectly
   * well. A health report that cannot be produced when something is unhealthy
   * is the one case it exists for.
   * </p>
   *
   * <p>
   * Each client is built on first use instead, and a service that cannot be
   * reached is reported as down rather than throwing. The report was always
   * written to degrade -- the batch stub section already skips itself when the
   * resource manager is down -- and only this constructor stood in the way.
   * </p>
   */
  public PCSHealthMonitor(String fmUrlStr, String wmUrlStr, String rmUrlStr,
      String crawlPropFilePath, String statesFilePath)
      throws InstantiationException {
    this.fmUrlStr = fmUrlStr;
    this.wmUrlStr = wmUrlStr;
    this.rmUrlStr = rmUrlStr;
    this.crawlProps = new CrawlPropertiesFile(crawlPropFilePath);
    this.statesFile = new WorkflowStatesFile(statesFilePath);
  }

  /**
   * The configured URL, so a service that is down still says where it was
   * looked for. A report that omits the address is harder to act on than one
   * that shows it next to "DOWN".
   */
  private String safeUrl(Object url) {
    return url != null ? url.toString() : "unavailable";
  }

  /** @return the file manager client, or null if it cannot be built */
  private synchronized FileManagerUtils fm() {
    if (fm == null && fmUrlStr != null) {
      try {
        fm = new FileManagerUtils(fmUrlStr);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "File manager at [" + fmUrlStr
            + "] is not reachable: " + e.getMessage());
      }
    }
    return fm;
  }

  /** @return the workflow manager client, or null if it cannot be built */
  private synchronized WorkflowManagerUtils wm() {
    if (wm == null && wmUrlStr != null) {
      try {
        wm = new WorkflowManagerUtils(wmUrlStr);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Workflow manager at [" + wmUrlStr
            + "] is not reachable: " + e.getMessage());
      }
    }
    return wm;
  }

  /** @return the resource manager client, or null if it cannot be built */
  private synchronized ResourceManagerUtils rm() {
    if (rm == null && rmUrlStr != null) {
      try {
        rm = new ResourceManagerUtils(rmUrlStr);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Resource manager at [" + rmUrlStr
            + "] is not reachable: " + e.getMessage());
      }
    }
    return rm;
  }

  public synchronized PCSHealthMonitorReport getReport() {
    PCSHealthMonitorReport report = new PCSHealthMonitorReport();
    report.setGenerationDate(new Date());
    report.setFmStatus(getFileManagerStatus());
    report.setWmStatus(getWorkflowManagerStatus());
    report.setRmStatus(getResourceManagerStatus());
    report.setBatchStubStatus(getBatchStubStatus());
    report.setCrawlerStatus(getCrawlerStatus());
    report.setLatestProductsIngested(getProductHealth());
    report.setJobHealthStatus(getJobStatusHealth());
    report.setCrawlerHealthStatus(getIngestHealth());

    return report;
  }

  public void quickPrintMonitorToConsole() {
    System.out.println(HEADER_AND_FOOTER);
    System.out.println(REPORT_BANNER);
    System.out.println("Generated on: "
        + DateUtils.toString(Calendar.getInstance()));
    System.out.println("");
    System.out.println("Service Status:");
    System.out.println("");

    System.out.println(FILE_MANAGER_DAEMON_NAME
        + getStrPadding(FILE_MANAGER_DAEMON_NAME, WORKFLOW_MANAGER_DAEMON_NAME)
        + ":\t[" + safeUrl(fm() == null ? null : fm().getFmUrl()) + "]: " + printUp(getFmUp()));

    System.out.println(WORKFLOW_MANAGER_DAEMON_NAME + ":\t["
        + safeUrl(wm() == null ? null : wm().getWmUrl()) + "]: " + printUp(getWmUp()));

    System.out.println(RESOURCE_MANAGER_DAEMON_NAME + ":\t["
        + safeUrl(rm() == null ? null : rm().getResmgrUrl()) + "]: " + printUp(getRmUp()));

    quickPrintBatchStubs();

    System.out.println("");
    System.out.println("Crawlers:");
    quickPrintCrawlers();
    System.out.println("");

    System.out.println("PCS Health: ");
    System.out.println("");
    System.out.println("Files:");
    System.out.println(SECTION_SEPARATOR);

    quickPrintProductHealth();

    System.out.println("");
    System.out.println("Jobs:");
    System.out.println(SECTION_SEPARATOR);

    quickPrintJobStatusHealth();

    System.out.println("");
    System.out.println("Ingest:");
    System.out.println(SECTION_SEPARATOR);

    quickPrintIngestStatusHealth();

    System.out.println(HEADER_AND_FOOTER);

  }

  public void printMonitorToConsole(PCSHealthMonitorReport report) {

    System.out.println(HEADER_AND_FOOTER);
    System.out.println(REPORT_BANNER);
    System.out.println("Generated on: " + report.getCreateDateIsoFormat());
    System.out.println("");
    System.out.println("Service Status:");
    System.out.println("");

    System.out.println(report.getFmStatus().getDaemonName()
        + getStrPadding(report.getFmStatus().getDaemonName(), report
            .getWmStatus().getDaemonName()) + ":\t["
        + report.getFmStatus().getUrlStr() + "]: "
        + report.getFmStatus().getStatus());

    System.out.println(report.getWmStatus().getDaemonName() + ":\t["
        + report.getWmStatus().getUrlStr() + "]: "
        + report.getWmStatus().getStatus());

    System.out.println(report.getRmStatus().getDaemonName() + ":\t["
        + report.getRmStatus().getUrlStr() + "]: "
        + report.getRmStatus().getStatus());

    printBatchStubs(report);

    System.out.println("");
    System.out.println("Crawlers:");
    printCrawlers(report);
    System.out.println("");

    System.out.println("PCS Health: ");
    System.out.println("");
    System.out.println("Files:");
    System.out.println(SECTION_SEPARATOR);

    printProductHealth(report);

    System.out.println("");
    System.out.println("Jobs:");
    System.out.println(SECTION_SEPARATOR);

    printJobStatusHealth(report);

    System.out.println("");
    System.out.println("Ingest:");
    System.out.println(SECTION_SEPARATOR);

    printIngestStatusHealth(report);

    System.out.println(HEADER_AND_FOOTER);

  }

  public static void main(String[] args) throws InstantiationException {
    String usage = "PCSHealthMonitor <fm url> <wm url> <rm url> <crawler xml file path> <workflow states xml file path>\n";
    String fmUrlStr, wmUrlStr, rmUrlStr;
    String crawlerXmlFilePath, workflowStateXmlPath;

    if (args.length != 5) {
      System.err.println(usage);
      System.exit(1);
    }

    fmUrlStr = args[0];
    wmUrlStr = args[1];
    rmUrlStr = args[2];
    crawlerXmlFilePath = args[3];
    workflowStateXmlPath = args[4];

    PCSHealthMonitor mon = new PCSHealthMonitor(fmUrlStr, wmUrlStr, rmUrlStr,
        crawlerXmlFilePath, workflowStateXmlPath);
    try {
      mon.quickPrintMonitorToConsole();
    } catch (Exception e) {
      LOG.log(Level.SEVERE, e.getMessage());
    }
  }

  private PCSDaemonStatus getFileManagerStatus() {
    PCSDaemonStatus fmStatus = new PCSDaemonStatus();

    fmStatus.setDaemonName(FILE_MANAGER_DAEMON_NAME);
    fmStatus.setStatus(printUp(getFmUp()));
    fmStatus.setUrlStr(safeUrl(fm() == null ? null : fm().getFmUrl()));

    return fmStatus;
  }

  private PCSDaemonStatus getWorkflowManagerStatus() {
    PCSDaemonStatus wmStatus = new PCSDaemonStatus();

    wmStatus.setDaemonName(WORKFLOW_MANAGER_DAEMON_NAME);
    wmStatus.setStatus(printUp(getWmUp()));
    wmStatus.setUrlStr(safeUrl(wm() == null ? null : wm().getWmUrl()));

    return wmStatus;
  }

  private PCSDaemonStatus getResourceManagerStatus() {
    PCSDaemonStatus rmStatus = new PCSDaemonStatus();

    rmStatus.setDaemonName(RESOURCE_MANAGER_DAEMON_NAME);
    rmStatus.setStatus(printUp(getRmUp()));
    // Do not call rm() just to print the URL. Building that client is an
    // Avro connect, and when Resource Manager is down it waits the transfer
    // timeout. The configured address is enough to show on the status page.
    rmStatus.setUrlStr(rmUrlStr != null ? rmUrlStr : "unavailable");

    return rmStatus;
  }

  private List getBatchStubStatus() {
    List batchStubStatus = new Vector();

    if (getRmUp()) {
      // only print if the resource manager is up
      List resNodes = rm() == null ? null : rm().safeGetResourceNodes();

      if (resNodes != null && resNodes.size() > 0) {
        for (Object resNode : resNodes) {
          ResourceNode node = (ResourceNode) resNode;
          PCSDaemonStatus batchStatus = new PCSDaemonStatus();
          batchStatus.setDaemonName(BATCH_STUB_DAEMON_NAME);
          batchStatus.setUrlStr(node.getIpAddr().toString());
          batchStatus.setStatus(printUp(getBatchStubUp(node)));
          batchStubStatus.add(batchStatus);
        }
      }
    }

    return batchStubStatus;
  }

  private List getCrawlerStatus() {
    List crawlers = this.crawlProps.getCrawlers();
    String crawlHost = this.crawlProps.getCrawlHost();
    List statuses = new Vector();

    if (crawlers != null && crawlers.size() > 0) {
      Collections.sort(crawlers, new Comparator() {
        public int compare(Object o1, Object o2) {
          CrawlInfo c1 = (CrawlInfo) o1;
          CrawlInfo c2 = (CrawlInfo) o2;

          return c1.getCrawlerName().compareTo(c2.getCrawlerName());
        }

      });

      for (Object crawler : crawlers) {
        CrawlInfo info = (CrawlInfo) crawler;
        String crawlerUrlStr = "http://" + crawlHost + ":"
                               + info.getCrawlerPort();
        CrawlerStatus status = new CrawlerStatus();
        status.setInfo(info);
        status.setStatus(printUp(getCrawlerUp(crawlerUrlStr)));
        status.setCrawlHost(crawlHost);
        statuses.add(status);
      }
    }

    return statuses;

  }

  private List getProductHealth() {
    if (getFmUp()) {
      return fm() == null ? null : fm().safeGetTopNProducts(TOP_N_PRODUCTS);
    } else {
      return new Vector();
    }
  }

  private List getJobStatusHealth() {
    if (!getWmUp()) {
      return new Vector();
    }

    List statuses = new Vector();
    List states = workflowStates();

    if (states != null && states.size() > 0) {
      for (Object state1 : states) {
        String state = (String) state1;
        int numPipelines = wm() == null ? -1 : wm().safeGetNumWorkflowInstancesByStatus(state);
        if (numPipelines == -1) {
          numPipelines = 0;
        }

        JobHealthStatus jobStatus = new JobHealthStatus();
        jobStatus.setStatus(state);
        jobStatus.setNumPipelines(numPipelines);
        statuses.add(jobStatus);
      }
    }

    return statuses;
  }

  /**
   * The workflow statuses to report on.
   *
   * <p>
   * Asked of the Workflow Manager, because its engine reads the lifecycle
   * that decides which statuses exist, and that lifecycle differs by engine:
   * the queue-based one declares Queued, WaitingOnResources and
   * PreConditionEval, none of which appear in the older file. A states file
   * written per deployment goes stale the moment the engine changes, and
   * reports every count as zero without saying why.
   * </p>
   *
   * <p>
   * The configured file is still used when the manager cannot be asked, so a
   * deployment running an older manager keeps what it had.
   * </p>
   */
  private List workflowStates() {
    if (wm() != null) {
      List reported = wm().safeGetSupportedStatuses();
      if (reported != null && !reported.isEmpty()) {
        return reported;
      }
    }
    return this.statesFile.getStates();
  }

  private List getIngestHealth() {
    if (this.crawlProps.getCrawlers().size()==0) {
      return new Vector();
    }

    List statuses = new Vector();

    for (Object o : this.crawlProps.getCrawlers()) {
      CrawlInfo info = (CrawlInfo) o;
      String crawlUrlStr = "http://" + this.crawlProps.getCrawlHost() + ":"
                           + info.getCrawlerPort();
      AvroRpcCrawlDaemonController controller = null;
      try {
        controller = new AvroRpcCrawlDaemonController(crawlUrlStr);
        CrawlerHealth health = new CrawlerHealth();
        health.setCrawlerName(info.getCrawlerName());
        health.setNumCrawls(controller.getNumCrawls());
        health
            .setAvgCrawlTime((double) (controller.getAverageCrawlTime() / DOUBLE));
        statuses.add(health);

      } catch (Exception e) {
        CrawlerHealth health = new CrawlerHealth();
        health.setCrawlerName(info.getCrawlerName());
        health.setNumCrawls(CRAWLER_DOWN_INT);
        health.setAvgCrawlTime(CRAWLER_DOWN_DOUBLE);
        statuses.add(health);
      } finally {
        // The Avro controller holds a socket; the XML-RPC one it replaced did
        // not, so nothing here used to need closing.
        if (controller != null) {
          try {
            controller.close();
          } catch (IOException e) {
            LOG.log(Level.FINE, "Unable to close crawler health client", e);
          }
        }
      }

    }

    return statuses;
  }

  private void printIngestStatusHealth(PCSHealthMonitorReport report) {
    if (report.getCrawlerHealthStatus() != null
        && report.getCrawlerHealthStatus().size() > 0) {
      for (Object o : report.getCrawlerHealthStatus()) {
        CrawlerHealth health = (CrawlerHealth) o;
        System.out.print(health.getCrawlerName() + ":");
        if (health.getNumCrawls() == CRAWLER_DOWN_INT) {
          System.out.println(" DOWN");
        } else {
          System.out.println("");
          System.out.println("Number of Crawls: " + health.getNumCrawls());
          System.out.println("Average Crawl Time (seconds): "
                             + health.getAvgCrawlTime());
          System.out.println("");
        }

      }

    }
  }

  private void printJobStatusHealth(PCSHealthMonitorReport report) {
    if (report.getJobHealthStatus() != null
        && report.getJobHealthStatus().size() > 0) {
      for (Object o : report.getJobHealthStatus()) {
        JobHealthStatus status = (JobHealthStatus) o;
        System.out.println(status.getNumPipelines() + " pipelines "
                           + status.getStatus());
      }
    }
  }

  private void printProductHealth(PCSHealthMonitorReport report) {
    if (report.getLatestProductsIngested() != null
        && report.getLatestProductsIngested().size() > 0) {
      System.out.println("Latest " + TOP_N_PRODUCTS + " products ingested:");
      for (Object o : report.getLatestProductsIngested()) {
        Product p = (Product) o;
        p.setProductType(fm.safeGetProductTypeById(p.getProductType()
                                                    .getProductTypeId()));
        p.setProductReferences(fm.safeGetProductReferences(p));
        Metadata prodMet = fm.safeGetMetadata(p);
        System.out.println(fm.getFilePath(p) + " at: "
                           + prodMet.getMetadata("CAS." + PRODUCT_RECEVIED_TIME));
      }

    }
  }

  private void printBatchStubs(PCSHealthMonitorReport report) {
    if (report.getBatchStubStatus() != null
        && report.getBatchStubStatus().size() > 0) {
      for (Object o : report.getBatchStubStatus()) {
        PCSDaemonStatus batchStatus = (PCSDaemonStatus) o;
        System.out.println("> " + batchStatus.getDaemonName() + ": ["
                           + batchStatus.getUrlStr() + "]: " + batchStatus.getStatus());
      }

    }
  }

  private void printCrawlers(PCSHealthMonitorReport report) {
    if (report.getCrawlerStatus() != null
        && report.getCrawlerStatus().size() > 0) {
      List crawlers = this.crawlProps.getCrawlers();
      String biggestString = getBiggestString(crawlers);

      for (Object o : report.getCrawlerStatus()) {
        CrawlerStatus status = (CrawlerStatus) o;
        String crawlerUrlStr = "http://" + status.getCrawlHost() + ":"
                               + status.getInfo().getCrawlerPort();
        System.out.println(getStrPadding(status.getInfo().getCrawlerName(),
            biggestString)
                           + status.getInfo().getCrawlerName()
                           + ": ["
                           + crawlerUrlStr
                           + "]: "
                           + status.getStatus());
      }

    }

  }

  private void quickPrintCrawlers() {
    List crawlers = this.crawlProps.getCrawlers();
    String crawlHost = this.crawlProps.getCrawlHost();

    if (crawlers != null && crawlers.size() > 0) {
      Collections.sort(crawlers, new Comparator() {
        public int compare(Object o1, Object o2) {
          CrawlInfo c1 = (CrawlInfo) o1;
          CrawlInfo c2 = (CrawlInfo) o2;

          return c1.getCrawlerName().compareTo(c2.getCrawlerName());
        }

      });

      String biggestString = getBiggestString(crawlers);
      for (Object crawler : crawlers) {
        CrawlInfo info = (CrawlInfo) crawler;
        String crawlerUrlStr = "http://" + crawlHost + ":"
                               + info.getCrawlerPort();
        System.out.println(getStrPadding(info.getCrawlerName(), biggestString)
                           + info.getCrawlerName() + ": [" + crawlerUrlStr + "]: "
                           + printUp(getCrawlerUp(crawlerUrlStr)));
      }
    }

  }

  private void quickPrintBatchStubs() {
    List resNodes;

    if (getRmUp()) {
      // only print if the resource manager is up
      resNodes = rm() == null ? null : rm().safeGetResourceNodes();

      if (resNodes != null && resNodes.size() > 0) {
        for (Object resNode : resNodes) {
          ResourceNode node = (ResourceNode) resNode;
          System.out.println("> " + BATCH_STUB_DAEMON_NAME + ": ["
                             + node.getIpAddr() + "]: " + printUp(getBatchStubUp(node)));
        }
      }
    }
  }

  private void quickPrintJobStatusHealth() {
    if (!getWmUp()) {
      return;
    }

    List states = workflowStates();

    if (states != null && states.size() > 0) {
      for (Object state1 : states) {
        String state = (String) state1;
        int numPipelines = wm() == null ? -1 : wm().safeGetNumWorkflowInstancesByStatus(state);
        if (numPipelines == -1) {
          numPipelines = 0;
        }
        System.out.println(numPipelines + " pipelines " + state);

      }
    }
  }

  private void quickPrintProductHealth() {
    if (getFmUp()) {
      System.out.println("Latest " + TOP_N_PRODUCTS + " products ingested:");

      List prods = fm() == null ? null : fm().safeGetTopNProducts(TOP_N_PRODUCTS);

      if (prods != null && prods.size() > 0) {
        for (Object prod : prods) {
          Product p = (Product) prod;
          p.setProductType(fm.safeGetProductTypeById(p.getProductType()
                                                      .getProductTypeId()));
          p.setProductReferences(fm.safeGetProductReferences(p));
          Metadata prodMet = fm.safeGetMetadata(p);
          System.out.println(fm.getFilePath(p) + " at: "
                             + prodMet.getMetadata("CAS." + PRODUCT_RECEVIED_TIME));
        }
      }

    }
  }

  private void quickPrintIngestStatusHealth() {
    if (this.crawlProps.getCrawlers().size()==0) {
      return;
    }

    for (Object o : this.crawlProps.getCrawlers()) {
      CrawlInfo info = (CrawlInfo) o;
      String crawlUrlStr = "http://" + this.crawlProps.getCrawlHost() + ":"
                           + info.getCrawlerPort();
      AvroRpcCrawlDaemonController controller = null;
      try {
        controller = new AvroRpcCrawlDaemonController(crawlUrlStr);
        System.out.println(info.getCrawlerName() + ":");
        System.out.println("Number of Crawls: " + controller.getNumCrawls());
        System.out.println("Average Crawl Time (seconds): "
                           + (double) (controller.getAverageCrawlTime() / DOUBLE1));
        System.out.println("");

      } catch (Exception e) {
        System.out.println(info.getCrawlerName() + ": DOWN");
      } finally {
        if (controller != null) {
          try {
            controller.close();
          } catch (IOException e) {
            LOG.log(Level.FINE, "Unable to close crawler client", e);
          }
        }
      }

    }
  }

  private String getBiggestString(List crawlInfos) {
    int biggestStrSz = Integer.MIN_VALUE;
    String biggestStr = null;

    for (Object crawlInfo : crawlInfos) {
      CrawlInfo info = (CrawlInfo) crawlInfo;
      String crawlInfoName = info.getCrawlerName();
      if (crawlInfoName.length() > biggestStrSz) {
        biggestStr = crawlInfoName;
        biggestStrSz = biggestStr.length();
      }
    }

    return biggestStr;
  }

  private String getStrPadding(String initString, String compareString) {
    int sizeInitStr = initString.length();
    int sizeCompareStr = compareString.length();

    int diff = Math.abs(sizeInitStr - sizeCompareStr);
    StringBuilder buf = new StringBuilder();
    for (int i = 0; i < diff; i++) {
      buf.append(" ");
    }

    return buf.toString();
  }

  private boolean getBatchStubUp(ResourceNode node) {

    NettyTransceiver client = null;
    AvroRpcBatchStub proxy;
    try {
      client = new NettyTransceiver(
          new InetSocketAddress(node.getIpAddr().getHost(), node.getIpAddr().getPort()),
          BATCH_STUB_CHANNEL_FACTORY, 40000L);
      proxy = (AvroRpcBatchStub) SpecificRequestor.getClient(AvroRpcBatchStub.class, client);
      return proxy.isAlive();
    } catch (IOException e) {
      return false;
    } finally {
      if (client != null) {
        try {
          closeNettyTransceiver(client);
        } catch (IOException | RuntimeException e) {
          LOG.log(Level.FINE, "Unable to close batch stub health check client", e);
        }
      }
    }
  }

  private static void closeNettyTransceiver(NettyTransceiver transceiver)
      throws IOException {
    try {
      Method close = NettyTransceiver.class.getMethod("close", boolean.class);
      close.invoke(transceiver, false);
    } catch (NoSuchMethodException e) {
      transceiver.close();
    } catch (IllegalAccessException e) {
      throw new IOException("Unable to close Avro Netty transceiver", e);
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
      throw new IOException("Unable to close Avro Netty transceiver", cause);
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

  /**
   * Avro's client timeout is minutes (transfers). A health ping against a
   * port nobody is listening on must not inherit that. TCP is enough to say
   * DOWN; the Avro call is only for a daemon that accepted the socket.
   */
  static final int HEALTH_CONNECT_TIMEOUT_MS = 750;

  static boolean tcpReachable(URL url, int timeoutMs) {
    if (url == null || url.getHost() == null || url.getHost().length() == 0) {
      return false;
    }
    int port = url.getPort();
    if (port <= 0) {
      port = url.getDefaultPort();
    }
    if (port <= 0) {
      return false;
    }
    Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(url.getHost(), port), timeoutMs);
      return true;
    } catch (Exception e) {
      return false;
    } finally {
      try {
        socket.close();
      } catch (Exception ignored) {
      }
    }
  }

  private boolean getCrawlerUp(String crawlUrlStr) {
    // Avro, like everything else PCS talks to. The XML-RPC controller this
    // used is on its way out of Mnemosyne along with the transport under it.
    //
    // Closed explicitly, which the XML-RPC controller never needed: that one
    // held no socket, and this one does. A health check runs on a timer, so a
    // transceiver leaked per probe is a transceiver leaked per minute.
    AvroRpcCrawlDaemonController controller = null;
    try {
      URL crawlUrl = new URL(crawlUrlStr);
      if (!tcpReachable(crawlUrl, HEALTH_CONNECT_TIMEOUT_MS)) {
        return false;
      }
      controller = new AvroRpcCrawlDaemonController(crawlUrlStr);
      return controller.isRunning();
    } catch (Exception e) {
      return false;
    } finally {
      if (controller != null) {
        try {
          controller.close();
        } catch (IOException e) {
          LOG.log(Level.FINE, "Unable to close crawler health check client", e);
        }
      }
    }
  }

  private boolean getFmUp() {
    if (fm() == null) {
      return false;
    }
    try {
      if (fm.getFmgrClient() != null && fm.getFmgrClient().isAlive()) {
        return true;
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "File Manager isAlive failed", e);
    }
    // The health monitor keeps one client for the JVM lifetime. If File
    // Manager was down at first check, or an Avro call left that socket
    // wedged, isAlive stays false even after the daemon is back. Try a
    // fresh client before declaring it down.
    //
    // Dedicated, not the ordinary client. RpcCommunicationFactory.createClient
    // leaves shareConnection at the factory default of true, so it hands back
    // the cached per-URL transceiver -- the very socket that is wedged. A
    // retry built that way is the same connection under a new object, and
    // fixes only the other case, where the first check happened while File
    // Manager was down and left fmgrClient null for good.
    try {
      URL fmUrl = fm.getFmUrl();
      if (fmUrl == null) {
        return false;
      }
      FileManagerUtils retry = new FileManagerUtils(fmUrl,
          RpcCommunicationFactory.createDedicatedClient(fmUrl));
      boolean up = retry.getFmgrClient() != null && retry.getFmgrClient().isAlive();
      if (up) {
        try {
          fm.close();
        } catch (Exception ignored) {
        }
        fm = retry;
      } else {
        try {
          retry.close();
        } catch (Exception ignored) {
        }
      }
      return up;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean getWmUp() {
    if (wm() == null) {
      return false;
    }
    try {
      wm.getClient().getRegisteredEvents();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean getRmUp() {
    URL url = null;
    try {
      url = rmUrlStr == null ? null : new URL(rmUrlStr);
    } catch (Exception e) {
      return false;
    }
    // Probe the port before building an Avro client. ImageCat runs jobs
    // locally and often has no Resource Manager; Avro would wait the
    // transfer timeout (minutes) on a closed port and hold getReport's
    // lock, which is the status page and then everything behind it.
    if (!tcpReachable(url, HEALTH_CONNECT_TIMEOUT_MS)) {
      return false;
    }
    ResourceManagerUtils client = rm();
    if (client == null || client.getClient() == null) {
      return false;
    }
    try {
      client.getClient().getNodes();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private String printUp(boolean upFlag) {
    return upFlag ? STATUS_UP : STATUS_DOWN;
  }

}
