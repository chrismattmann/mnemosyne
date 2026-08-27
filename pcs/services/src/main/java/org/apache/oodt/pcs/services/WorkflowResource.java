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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import net.sf.json.JSONObject;

import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;
import org.apache.oodt.cas.workflow.system.rpc.RpcCommunicationFactory;

/**
 * JSON workflow browse for the Vue OPSUI. Same data the Wicket monitor
 * pulled over Avro.
 */
@Path("workflow")
public class WorkflowResource extends PCSService {

  private static final long serialVersionUID = 1L;
  private static final Logger LOG = Logger.getLogger(WorkflowResource.class.getName());

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
      @QueryParam("page") @DefaultValue("1") int pageNum) throws Exception {
    if (pageNum < 1) {
      pageNum = 1;
    }
    WorkflowManagerClient client = wm();
    try {
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
      List<Map<String, Object>> insts = new ArrayList<Map<String, Object>>();
      if (page != null && page.getPageWorkflows() != null) {
        List list = page.getPageWorkflows();
        for (int i = 0; i < list.size(); i++) {
          Object item = list.get(i);
          if (item instanceof WorkflowInstance) {
            insts.add(encodeInstance((WorkflowInstance) item));
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

  static Map<String, Object> encodeInstance(WorkflowInstance inst) {
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
    row.put("startDateTime", nullToEmpty(inst.getStartDateTimeIsoStr()));
    row.put("endDateTime", nullToEmpty(inst.getEndDateTimeIsoStr()));
    row.put("currentTaskStartDateTime",
        nullToEmpty(inst.getCurrentTaskStartDateTimeIsoStr()));
    row.put("currentTaskEndDateTime",
        nullToEmpty(inst.getCurrentTaskEndDateTimeIsoStr()));
    return row;
  }

  static Map<String, Object> encodeWorkflow(Workflow workflow, boolean withTasks) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    if (workflow == null) {
      return row;
    }
    row.put("id", nullToEmpty(workflow.getId()));
    row.put("name", nullToEmpty(workflow.getName()));
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
    return row;
  }

  static Map<String, Object> encodeCondition(WorkflowCondition cond) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    if (cond == null) {
      return row;
    }
    row.put("name", nullToEmpty(cond.getConditionName()));
    row.put("className", nullToEmpty(cond.getConditionInstanceClassName()));
    return row;
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
