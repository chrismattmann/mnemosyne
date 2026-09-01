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
package org.apache.oodt.pcs.services;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import net.sf.json.JSONObject;

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;
import org.apache.oodt.cas.workflow.system.rpc.RpcCommunicationFactory;
import org.apache.oodt.pcs.pedigree.Pedigree;
import org.apache.oodt.pcs.util.FileManagerUtils;

/**
 * JSON workflow browse for the Vue OPSUI. Same data the Wicket monitor
 * pulled over Avro.
 */
@Path("workflow")
public class WorkflowResource extends PCSService {

  private static final long serialVersionUID = 1L;
  private static final Logger LOG = Logger.getLogger(WorkflowResource.class.getName());
  static final int RECENT_INSTANCE_LIMIT = 25;

  private WorkflowManagerClient wm() throws MalformedURLException {
    return RpcCommunicationFactory.createClient(PCSService.conf.getWmUrl());
  }

  @GET
  @Path("definitions")
  @Produces("application/json")
  public String definitions() throws Exception {
    WorkflowManagerClient client = wm();
    try {
      List<Workflow> workflows = client.getWorkflows();
      List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
      if (workflows != null) {
        for (int i = 0; i < workflows.size(); i++) {
          out.add(encodeWorkflow(workflows.get(i), false));
        }
      }
      return json("workflows", out);
    } finally {
      closeQuietly(client);
    }
  }

  @GET
  @Path("definitions/{id}")
  @Produces("application/json")
  public String definition(@PathParam("id") String id) throws Exception {
    WorkflowManagerClient client = wm();
    try {
      Workflow workflow = client.getWorkflowById(id);
      if (workflow == null) {
        throw new ResourceNotFoundException("No workflow [" + id + "]");
      }
      JSONObject response = new JSONObject();
      response.put("workflow", encodeWorkflow(workflow, true));
      return response.toString();
    } finally {
      closeQuietly(client);
    }
  }

  @GET
  @Path("instances")
  @Produces("application/json")
  public String instances(
      @QueryParam("status") @DefaultValue("ALL") String status,
      @QueryParam("page") @DefaultValue("1") int pageNum,
      @QueryParam("workflow") String workflow,
      @QueryParam("sort") String sort,
      @QueryParam("dir") String dir) throws Exception {
    if (pageNum < 1) {
      pageNum = 1;
    }
    WorkflowManagerClient client = wm();
    try {
      // A sort has to see everything to mean anything. Ordering a page after
      // it arrives orders the twenty rows the manager happened to return,
      // and a column header that does that says the longest-running task is
      // at the top when it is only the longest of those twenty.
      if (InstanceOrder.isSortable(sort)) {
        return orderedInstances(client, status, workflow, sort, dir, pageNum);
      }
      if (workflow != null && workflow.trim().length() > 0) {
        return instancesForWorkflow(client, status, workflow.trim());
      }
      WorkflowInstancePage page;
      if (status == null || status.trim().isEmpty() || "ALL".equalsIgnoreCase(status.trim())) {
        page = client.paginateWorkflowInstances(pageNum);
      } else {
        page = client.paginateWorkflowInstances(pageNum, status.trim());
      }
      Map<String, Object> body = new LinkedHashMap<String, Object>();
      body.put("status", status);
      body.put("page", Integer.valueOf(page == null ? pageNum : page.getPageNum()));
      body.put("totalPages", Integer.valueOf(page == null ? 0 : page.getTotalPages()));
      body.put("pageSize", Integer.valueOf(page == null ? 0 : page.getPageSize()));
      Set executing = executingIds(client);
      List<Map<String, Object>> insts = new ArrayList<Map<String, Object>>();
      if (page != null && page.getPageWorkflows() != null) {
        List list = page.getPageWorkflows();
        for (int i = 0; i < list.size(); i++) {
          Object item = list.get(i);
          if (item instanceof WorkflowInstance) {
            insts.add(encodeInstance((WorkflowInstance) item, executing));
          }
        }
      }
      body.put("instances", insts);
      JSONObject response = new JSONObject();
      response.put("page", body);
      return response.toString();
    } finally {
      closeQuietly(client);
    }
  }

  /** How many instances a page of ordered results holds. */
  static final int ORDERED_PAGE_SIZE = 20;

  /**
   * Instances ordered across the whole set, then paged.
   *
   * <p>
   * The manager pages but cannot order, so ordering means reading the set and
   * arranging it here. Every ordered instance is then pageable: a sort
   * rearranges the table, it does not shorten it, so the page count is the
   * one the unordered view shows and the last page holds the same instances,
   * at the other end of the order.
   * </p>
   */
  private String orderedInstances(WorkflowManagerClient client, String status,
      String workflow, String sort, String dir, int pageNum) throws Exception {
    List all = client.getWorkflowInstances();
    List<WorkflowInstance> matched = new ArrayList<WorkflowInstance>();
    if (all != null) {
      for (int i = 0; i < all.size(); i++) {
        Object item = all.get(i);
        if (!(item instanceof WorkflowInstance)) {
          continue;
        }
        WorkflowInstance inst = (WorkflowInstance) item;
        if (workflow != null && workflow.trim().length() > 0
            && !matchesWorkflow(inst, workflow.trim())) {
          continue;
        }
        if (status != null && status.trim().length() > 0
            && !"ALL".equalsIgnoreCase(status.trim())
            && !status.trim().equals(statusOf(inst))) {
          continue;
        }
        matched.add(inst);
      }
    }

    Set executing = executingIds(client);
    List<Map<String, Object>> encoded = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < matched.size(); i++) {
      encoded.add(encodeInstance(matched.get(i), executing));
    }
    Collections.sort(encoded,
        InstanceOrder.by(sort, dir, System.currentTimeMillis()));

    int totalPages = (encoded.size() + ORDERED_PAGE_SIZE - 1)
        / ORDERED_PAGE_SIZE;
    if (totalPages < 1) {
      totalPages = 1;
    }
    if (pageNum > totalPages) {
      pageNum = totalPages;
    }
    int from = (pageNum - 1) * ORDERED_PAGE_SIZE;
    int to = Math.min(from + ORDERED_PAGE_SIZE, encoded.size());
    List<Map<String, Object>> page = from < to
        ? encoded.subList(from, to)
        : new ArrayList<Map<String, Object>>();

    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("status", status);
    if (workflow != null && workflow.trim().length() > 0) {
      body.put("workflow", workflow.trim());
    }
    body.put("sort", sort);
    body.put("dir", "desc".equalsIgnoreCase(dir) ? "desc" : "asc");
    body.put("page", Integer.valueOf(pageNum));
    body.put("totalPages", Integer.valueOf(totalPages));
    body.put("pageSize", Integer.valueOf(page.size()));
    // What the order was taken over. Everything ordered is reachable, so
    // this is also what the pages add up to.
    body.put("total", Integer.valueOf(matched.size()));
    body.put("shown", Integer.valueOf(encoded.size()));
    body.put("truncated", Boolean.FALSE);
    body.put("instances", page);
    JSONObject response = new JSONObject();
    response.put("page", body);
    return response.toString();
  }

  private static String statusOf(WorkflowInstance inst) {
    return inst.getState() == null ? "" : inst.getState().getName();
  }

  private String instancesForWorkflow(WorkflowManagerClient client, String status,
      String workflow) throws Exception {
    // Not wrapped in a catch that yields an empty list. The repository and the
    // RPC layer both return an empty list for an empty repository now, so
    // nothing here has to paper over a null -- and treating any failure as
    // "no instances" would render an unreachable workflow manager exactly like
    // a healthy one with nothing in it. This method already declares throws.
    List all = client.getWorkflowInstances();
    List<WorkflowInstance> matched = new ArrayList<WorkflowInstance>();
    if (all != null) {
      for (int i = 0; i < all.size(); i++) {
        Object item = all.get(i);
        if (!(item instanceof WorkflowInstance)) {
          continue;
        }
        WorkflowInstance inst = (WorkflowInstance) item;
        if (!matchesWorkflow(inst, workflow)) {
          continue;
        }
        if (status != null && status.trim().length() > 0
            && !"ALL".equalsIgnoreCase(status.trim())
            && (inst.getStatus() == null || !status.trim().equals(inst.getStatus()))) {
          continue;
        }
        matched.add(inst);
      }
    }
    Collections.sort(matched, START_DESC);
    int total = matched.size();
    boolean truncated = matched.size() > RECENT_INSTANCE_LIMIT;
    if (truncated) {
      matched = matched.subList(0, RECENT_INSTANCE_LIMIT);
    }
    Set executing = executingIds(client);
    List<Map<String, Object>> insts = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < matched.size(); i++) {
      insts.add(encodeInstance(matched.get(i), executing));
    }
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("status", status);
    body.put("workflow", workflow);
    body.put("page", Integer.valueOf(1));
    body.put("totalPages", Integer.valueOf(1));
    body.put("pageSize", Integer.valueOf(insts.size()));
    // This path really does cap, so it says what it cut and what it kept --
    // the view renders the difference rather than leaving it to be guessed.
    body.put("total", Integer.valueOf(total));
    body.put("shown", Integer.valueOf(insts.size()));
    body.put("truncated", Boolean.valueOf(truncated));
    body.put("instances", insts);
    JSONObject response = new JSONObject();
    response.put("page", body);
    return response.toString();
  }

  static boolean matchesWorkflow(WorkflowInstance inst, String workflow) {
    if (inst == null || workflow == null || workflow.length() == 0) {
      return false;
    }
    if (inst.getWorkflow() == null) {
      return false;
    }
    String id = inst.getWorkflow().getId();
    String name = inst.getWorkflow().getName();
    return workflow.equals(id) || workflow.equalsIgnoreCase(name);
  }

  private static final Comparator<WorkflowInstance> START_DESC = new Comparator<WorkflowInstance>() {
    public int compare(WorkflowInstance a, WorkflowInstance b) {
      String as = a == null ? "" : nullToEmpty(a.getStartDateTimeIsoStr());
      String bs = b == null ? "" : nullToEmpty(b.getStartDateTimeIsoStr());
      return bs.compareTo(as);
    }
  };

  @GET
  @Path("instances/{id}")
  @Produces("application/json")
  public String instance(@PathParam("id") String id) throws Exception {
    WorkflowManagerClient client = wm();
    try {
      WorkflowInstance inst = client.getWorkflowInstanceById(id);
      if (inst == null) {
        throw new ResourceNotFoundException("No workflow instance [" + id + "]");
      }
      Metadata met = inst.getSharedContext();
      if (met == null || met.getAllKeys() == null || met.getAllKeys().isEmpty()) {
        try {
          met = client.getWorkflowInstanceMetadata(id);
        } catch (Exception e) {
          LOG.fine("No instance metadata for " + id + ": " + e.getLocalizedMessage());
        }
      }
      Map<String, Object> row = encodeInstanceDetail(inst, met, executingIds(client));
      if ((!row.containsKey("tasks") || ((List) row.get("tasks")).isEmpty())
          && inst.getWorkflow() != null && inst.getWorkflow().getId() != null
          && inst.getWorkflow().getId().length() > 0) {
        try {
          Workflow def = client.getWorkflowById(inst.getWorkflow().getId());
          if (def != null) {
            Map<String, Object> encoded = encodeWorkflow(def, true);
            if (encoded.get("tasks") != null) {
              row.put("tasks", encoded.get("tasks"));
            }
          }
        } catch (Exception e) {
          LOG.fine("No workflow definition tasks for " + id + ": " + e.getLocalizedMessage());
        }
      }
      try {
        row.put("wallClockMinutes", Double.valueOf(client.getWorkflowWallClockMinutes(id)));
      } catch (Exception e) {
        LOG.fine("No wall clock for " + id);
      }
      try {
        row.put("currentTaskWallClockMinutes",
            Double.valueOf(client.getWorkflowCurrentTaskWallClockMinutes(id)));
      } catch (Exception e) {
        LOG.fine("No current-task wall clock for " + id);
      }
      row.put("products", loadInstanceProducts(id));
      JSONObject response = new JSONObject();
      response.put("instance", row);
      return response.toString();
    } finally {
      closeQuietly(client);
    }
  }

  @GET
  @Path("tasks/{id}")
  @Produces("application/json")
  public String task(@PathParam("id") String id) throws Exception {
    WorkflowManagerClient client = wm();
    try {
      WorkflowTask task = client.getTaskById(id);
      if (task == null) {
        throw new ResourceNotFoundException("No task [" + id + "]");
      }
      JSONObject response = new JSONObject();
      response.put("task", encodeTask(task));
      return response.toString();
    } finally {
      closeQuietly(client);
    }
  }

  /**
   * The statuses this deployment's engine can report.
   *
   * <p>
   * A reader filtering instances by status has to be offered the statuses
   * that actually occur here. A list written into a client is a list for
   * whichever engine its author had in mind: against the other one it offers
   * states that never happen, omits the ones that do, and a status differing
   * only in case matches nothing at all.
   * </p>
   */
  @GET
  @Path("statuses")
  @Produces("application/json")
  public String statuses() throws Exception {
    WorkflowManagerClient client = wm();
    try {
      List<?> supported = client.getSupportedWorkflowStatuses();
      List<String> out = new ArrayList<String>();
      if (supported != null) {
        for (int i = 0; i < supported.size(); i++) {
          Object status = supported.get(i);
          if (status != null && !status.toString().equals("")) {
            out.add(status.toString());
          }
        }
      }
      return json("statuses", out);
    } finally {
      closeQuietly(client);
    }
  }

  @GET
  @Path("conditions/{id}")
  @Produces("application/json")
  public String condition(@PathParam("id") String id) throws Exception {
    WorkflowManagerClient client = wm();
    try {
      WorkflowCondition cond = client.getConditionById(id);
      if (cond == null) {
        throw new ResourceNotFoundException("No condition [" + id + "]");
      }
      JSONObject response = new JSONObject();
      response.put("condition", encodeCondition(cond));
      return response.toString();
    } finally {
      closeQuietly(client);
    }
  }

  static Set executingIds(WorkflowManagerClient client) {
    if (client == null) {
      return null;
    }
    try {
      List ids = client.getExecutingWorkflowInstanceIds();
      Set out = new HashSet();
      if (ids != null) {
        for (int i = 0; i < ids.size(); i++) {
          Object id = ids.get(i);
          if (id != null && String.valueOf(id).length() > 0) {
            out.add(String.valueOf(id));
          }
        }
      }
      return out;
    } catch (Exception e) {
      LOG.fine("No executing-instance list from the engine: " + e.getLocalizedMessage());
      return null;
    }
  }

  static boolean instanceLooksDone(WorkflowInstance inst) {
    if (inst == null) {
      return false;
    }
    String status = inst.getStatus();
    if (status != null) {
      String s = status.toUpperCase();
      if (s.equals("FINISHED") || s.equals("ERROR") || s.equals("FAILURE")
          || s.equals("RESULTSFAILURE") || s.equals("STOPPED")
          || s.equals("SUCCESS") || s.equals("EXECUTIONCOMPLETE")) {
        return true;
      }
    }
    String end = inst.getEndDateTimeIsoStr();
    return end != null && end.length() > 0 && !end.equals("null");
  }

  static Map<String, Object> encodeInstance(WorkflowInstance inst) {
    return encodeInstance(inst, null);
  }

  static Map<String, Object> encodeInstance(WorkflowInstance inst, Set executing) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    if (inst == null) {
      return row;
    }
    row.put("id", nullToEmpty(inst.getId()));
    row.put("status", nullToEmpty(inst.getStatus()));
    row.put("currentTaskId", nullToEmpty(inst.getCurrentTaskId()));
    if (inst.getWorkflow() != null) {
      row.put("workflowId", nullToEmpty(inst.getWorkflow().getId()));
      row.put("workflowName", nullToEmpty(inst.getWorkflow().getName()));
      try {
        if (inst.getCurrentTask() != null) {
          row.put("currentTaskName", nullToEmpty(inst.getCurrentTask().getTaskName()));
        }
      } catch (Exception e) {
        LOG.fine("No current task name for instance " + inst.getId());
      }
    }
    // How many times this was put off. On the detail page already; here so
    // the table can show it and be ordered by it, which is the question that
    // wants asking of a whole run rather than of one instance: what kept
    // being deferred.
    row.put("timesBlocked", Integer.valueOf(inst.getTimesBlocked()));
    row.put("startDateTime", nullToEmpty(inst.getStartDateTimeIsoStr()));
    row.put("endDateTime", nullToEmpty(inst.getEndDateTimeIsoStr()));
    row.put("currentTaskStartDateTime",
        nullToEmpty(inst.getCurrentTaskStartDateTimeIsoStr()));
    row.put("currentTaskEndDateTime",
        nullToEmpty(inst.getCurrentTaskEndDateTimeIsoStr()));
    String productName = firstMetadata(inst.getSharedContext(),
        "Filename", "ProductName", "CAS.ProductName");
    if (productName.length() > 0) {
      row.put("productName", productName);
    }
    Map<String, Object> progress = PgeProgressPeek.of(inst.getSharedContext());
    if (progress != null) {
      row.put("pgeProgress", progress);
    }
    if (executing != null && inst.getId() != null && inst.getId().length() > 0) {
      boolean running = executing.contains(inst.getId());
      row.put("running", Boolean.valueOf(running));
      row.put("abandoned", Boolean.valueOf(!running && !instanceLooksDone(inst)));
    }
    return row;
  }

  static List<Map<String, Object>> encodeInstanceProducts(List products, FileManagerUtils fm) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    if (products == null) {
      return out;
    }
    for (int i = 0; i < products.size(); i++) {
      Object item = products.get(i);
      if (item instanceof Product) {
        out.add(CatalogResource.encodeProduct((Product) item, fm));
      }
    }
    return out;
  }

  private List<Map<String, Object>> loadInstanceProducts(String instanceId) {
    FileManagerUtils fm = null;
    try {
      fm = new FileManagerUtils(PCSService.conf.getFmUrl());
      Pedigree pedigree = new Pedigree(fm, false, Collections.emptyList());
      return encodeInstanceProducts(pedigree.getWorkflowInstProds(instanceId), fm);
    } catch (Exception e) {
      LOG.fine("No catalog products for instance " + instanceId + ": "
          + e.getLocalizedMessage());
      return Collections.emptyList();
    } finally {
      if (fm != null) {
        try {
          fm.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  static String firstMetadata(Metadata met, String... keys) {
    if (met == null || keys == null) {
      return "";
    }
    for (int i = 0; i < keys.length; i++) {
      String value = met.getMetadata(keys[i]);
      if (value != null && value.length() > 0) {
        return value;
      }
    }
    return "";
  }

  static Map<String, Object> encodeInstanceDetail(WorkflowInstance inst, Metadata met) {
    return encodeInstanceDetail(inst, met, null);
  }

  static Map<String, Object> encodeInstanceDetail(WorkflowInstance inst, Metadata met, Set executing) {
    Map<String, Object> row = encodeInstance(inst, executing);
    if (inst != null) {
      row.put("timesBlocked", Integer.valueOf(inst.getTimesBlocked()));
      Priority priority = inst.getPriority();
      if (priority != null) {
        row.put("priority", nullToEmpty(priority.getName()));
        row.put("priorityValue", Double.valueOf(priority.getValue()));
      }
      if (met == null) {
        met = inst.getSharedContext();
      }
    }
    row.put("metadata", CatalogResource.encodeMetadata(met));
    Map<String, Object> progress = PgeProgressPeek.of(met != null ? met : (inst == null ? null : inst.getSharedContext()));
    if (progress != null) {
      row.put("pgeProgress", progress);
    }
    if (inst != null && inst.getWorkflow() != null && inst.getWorkflow().getTasks() != null) {
      List<Map<String, Object>> tasks = new ArrayList<Map<String, Object>>();
      List<WorkflowTask> list = inst.getWorkflow().getTasks();
      for (int i = 0; i < list.size(); i++) {
        tasks.add(encodeTask(list.get(i)));
      }
      row.put("tasks", tasks);
    }
    return row;
  }

  static Map<String, Object> encodeWorkflow(Workflow workflow, boolean withTasks) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    if (workflow == null) {
      return row;
    }
    row.put("id", nullToEmpty(workflow.getId()));
    row.put("name", nullToEmpty(workflow.getName()));
    // A workflow can carry conditions of its own, not just its tasks. The
    // packaged (wengine) dialect writes them straight onto the workflow --
    // PackagedWorkflowRepository does
    // graph.getParent().getWorkflow().getPreConditions().add(cond) -- so a
    // <conditions> block on a <workflow> lived only in the model and never
    // reached a caller. Reporting only task conditions was an assumption
    // carried over from the XML dialect, where tasks are the only place a
    // condition can hang. Both dialects report the same shape now; for a
    // workflow that declares none, these are simply empty.
    row.put("preConditions", encodeConditionList(workflow.getPreConditions()));
    row.put("postConditions", encodeConditionList(workflow.getPostConditions()));
    if (withTasks && workflow.getTasks() != null) {
      List<Map<String, Object>> tasks = new ArrayList<Map<String, Object>>();
      List<WorkflowTask> list = workflow.getTasks();
      for (int i = 0; i < list.size(); i++) {
        tasks.add(encodeTask(list.get(i)));
      }
      row.put("tasks", tasks);
    } else if (workflow.getTasks() != null) {
      row.put("taskCount", Integer.valueOf(workflow.getTasks().size()));
    }
    return row;
  }

  static Map<String, Object> encodeTask(WorkflowTask task) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    if (task == null) {
      return row;
    }
    row.put("id", nullToEmpty(task.getTaskId()));
    row.put("name", nullToEmpty(task.getTaskName()));
    row.put("className", nullToEmpty(task.getTaskInstanceClassName()));
    Map<String, String> properties = encodeTaskProperties(task.getTaskConfig());
    row.put("properties", properties);
    // StdPGETaskInstance (and any task that names a PgeConfig.xml) already
    // lists PGETask_ConfigFilePath in properties. Peek that file here so the
    // task page can show the <cmd> lines the engine will run, the way product
    // Peek shows the first bytes of a file.
    Map<String, Object> pgeConfig = PgeConfigPeek.of(properties);
    if (pgeConfig != null) {
      row.put("pgeConfig", pgeConfig);
    }
    row.put("requiredMetFields", encodeStringList(task.getRequiredMetFields()));
    row.put("preConditions", encodeConditionList(task.getPreConditions()));
    row.put("postConditions", encodeConditionList(task.getPostConditions()));
    return row;
  }

  static Map<String, Object> encodeCondition(WorkflowCondition cond) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    if (cond == null) {
      return row;
    }
    row.put("id", nullToEmpty(cond.getConditionId()));
    row.put("name", nullToEmpty(cond.getConditionName()));
    row.put("className", nullToEmpty(cond.getConditionInstanceClassName()));
    // A condition is configured, ordered and timed out, exactly as a task is
    // configured. Reporting only its class name says what code runs but not
    // what it was told to do -- and two conditions of the same class differ
    // only by their properties, so without these they read as duplicates.
    row.put("properties", encodeConditionProperties(cond.getCondConfig()));
    row.put("order", Integer.valueOf(cond.getOrder()));
    row.put("timeoutSeconds", Long.valueOf(cond.getTimeoutSeconds()));
    return row;
  }

  static Map<String, String> encodeConditionProperties(
      WorkflowConditionConfiguration config) {
    Map<String, String> out = new LinkedHashMap<String, String>();
    if (config == null || config.getProperties() == null) {
      return out;
    }
    Properties props = config.getProperties();
    List<String> names = new ArrayList<String>(props.stringPropertyNames());
    Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      out.put(name, nullToEmpty(props.getProperty(name)));
    }
    return out;
  }

  static Map<String, String> encodeTaskProperties(WorkflowTaskConfiguration config) {
    Map<String, String> out = new LinkedHashMap<String, String>();
    if (config == null || config.getProperties() == null) {
      return out;
    }
    Properties props = config.getProperties();
    List<String> names = new ArrayList<String>(props.stringPropertyNames());
    Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      out.put(name, nullToEmpty(props.getProperty(name)));
    }
    return out;
  }

  static List<Map<String, Object>> encodeConditionList(List<WorkflowCondition> conditions) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    if (conditions == null) {
      return out;
    }
    for (int i = 0; i < conditions.size(); i++) {
      out.add(encodeCondition(conditions.get(i)));
    }
    return out;
  }

  static List<String> encodeStringList(List raw) {
    List<String> out = new ArrayList<String>();
    if (raw == null) {
      return out;
    }
    for (int i = 0; i < raw.size(); i++) {
      Object item = raw.get(i);
      if (item != null) {
        out.add(String.valueOf(item));
      }
    }
    return out;
  }

  private static String json(String key, Object value) {
    JSONObject response = new JSONObject();
    response.put(key, value);
    return response.toString();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static void closeQuietly(WorkflowManagerClient client) {
    if (client == null) {
      return;
    }
    try {
      client.close();
    } catch (IOException e) {
      LOG.fine("Unable to close workflow client: " + e.getLocalizedMessage());
    }
  }
}
