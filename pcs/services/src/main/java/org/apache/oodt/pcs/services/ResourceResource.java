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

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import net.sf.json.JSONObject;

import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.ResourceNode;
import org.apache.oodt.cas.resource.system.ResourceManagerClient;
import org.apache.oodt.cas.resource.system.rpc.ResourceManagerFactory;

/**
 * JSON Resource Manager browse for the Vue OPSUI: nodes, queues, and
 * queued jobs. Read-only; no submit or kill.
 */
@Path("resource")
public class ResourceResource extends PCSService {

  private static final long serialVersionUID = 1L;
  private static final Logger LOG = Logger.getLogger(ResourceResource.class.getName());
  private static final int CONNECT_TIMEOUT_MS = 2000;

  @GET
  @Path("overview")
  @Produces("application/json")
  public String overview() {
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    URL url = null;
    try {
      url = PCSService.conf.getRmUrl();
    } catch (Exception e) {
      body.put("error", "Resource Manager URL is not configured");
      return resourceJson(emptyOverview(body));
    }
    if (url == null) {
      body.put("error", "Resource Manager URL is not configured");
      return resourceJson(emptyOverview(body));
    }
    body.put("url", url.toString());
    if (!reachable(url)) {
      body.put("error", "Resource Manager is not reachable");
      return resourceJson(emptyOverview(body));
    }
    try {
      ResourceManagerClient client = ResourceManagerFactory.getResourceManagerClient(url);
      try {
        body.put("alive", Boolean.valueOf(client.isAlive()));
      } catch (Exception e) {
        body.put("alive", Boolean.FALSE);
      }
      try {
        body.put("queueSize", Integer.valueOf(client.getJobQueueSize()));
      } catch (Exception e) {
        LOG.fine("No job queue size: " + e.getLocalizedMessage());
      }
      try {
        body.put("queueCapacity", Integer.valueOf(client.getJobQueueCapacity()));
      } catch (Exception e) {
        LOG.fine("No job queue capacity: " + e.getLocalizedMessage());
      }
      body.put("nodes", encodeNodes(client));
      body.put("queues", encodeQueues(client));
      body.put("jobs", encodeJobs(client));
      return resourceJson(body);
    } catch (Exception e) {
      LOG.warning("Resource Manager overview failed: " + e.getLocalizedMessage());
      body.put("error", e.getMessage() == null ? "Resource Manager query failed" : e.getMessage());
      return resourceJson(emptyOverview(body));
    }
  }

  static Map<String, Object> encodeNode(ResourceNode node, String load, List<String> queues) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    if (node == null) {
      return row;
    }
    row.put("id", nullToEmpty(node.getNodeId()));
    row.put("url", node.getIpAddr() == null ? "" : node.getIpAddr().toString());
    row.put("capacity", Integer.valueOf(node.getCapacity()));
    row.put("load", load == null ? "" : load);
    row.put("queues", queues == null ? Collections.emptyList() : queues);
    return row;
  }

  static Map<String, Object> encodeJob(Job job, String node) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    if (job == null) {
      return row;
    }
    row.put("id", nullToEmpty(job.getId()));
    row.put("name", nullToEmpty(job.getName()));
    row.put("status", nullToEmpty(job.getStatus()));
    row.put("queue", nullToEmpty(job.getQueueName()));
    row.put("load", job.getLoadValue());
    row.put("className", nullToEmpty(job.getJobInstanceClassName()));
    row.put("node", node == null ? "" : node);
    return row;
  }

  private static List<Map<String, Object>> encodeNodes(ResourceManagerClient client) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    List nodes;
    try {
      nodes = client.getNodes();
    } catch (Exception e) {
      LOG.fine("No resource nodes: " + e.getLocalizedMessage());
      return out;
    }
    if (nodes == null) {
      return out;
    }
    for (int i = 0; i < nodes.size(); i++) {
      Object item = nodes.get(i);
      if (!(item instanceof ResourceNode)) {
        continue;
      }
      ResourceNode node = (ResourceNode) item;
      String load = "";
      List<String> queues = Collections.emptyList();
      try {
        load = client.getNodeLoad(node.getNodeId());
      } catch (Exception e) {
        LOG.fine("No load for node " + node.getNodeId());
      }
      try {
        List<String> found = client.getQueuesWithNode(node.getNodeId());
        if (found != null) {
          queues = found;
        }
      } catch (Exception e) {
        LOG.fine("No queues for node " + node.getNodeId());
      }
      out.add(encodeNode(node, load, queues));
    }
    return out;
  }

  private static List<Map<String, Object>> encodeQueues(ResourceManagerClient client) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    List<String> names;
    try {
      names = client.getQueues();
    } catch (Exception e) {
      LOG.fine("No queues: " + e.getLocalizedMessage());
      return out;
    }
    if (names == null) {
      return out;
    }
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      Map<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("name", nullToEmpty(name));
      List<String> nodeIds = Collections.emptyList();
      try {
        List<String> found = client.getNodesInQueue(name);
        if (found != null) {
          nodeIds = found;
        }
      } catch (Exception e) {
        LOG.fine("No nodes for queue " + name);
      }
      row.put("nodes", nodeIds);
      out.add(row);
    }
    return out;
  }

  private static List<Map<String, Object>> encodeJobs(ResourceManagerClient client) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    List jobs;
    try {
      jobs = client.getQueuedJobs();
    } catch (Exception e) {
      LOG.fine("No queued jobs: " + e.getLocalizedMessage());
      return out;
    }
    if (jobs == null) {
      return out;
    }
    for (int i = 0; i < jobs.size(); i++) {
      Object item = jobs.get(i);
      if (!(item instanceof Job)) {
        continue;
      }
      Job job = (Job) item;
      String node = "";
      try {
        if (job.getId() != null) {
          node = client.getExecutionNode(job.getId());
        }
      } catch (Exception e) {
        LOG.fine("No execution node for job " + job.getId());
      }
      out.add(encodeJob(job, node));
    }
    return out;
  }

  private static Map<String, Object> emptyOverview(Map<String, Object> body) {
    if (!body.containsKey("nodes")) {
      body.put("nodes", Collections.emptyList());
    }
    if (!body.containsKey("queues")) {
      body.put("queues", Collections.emptyList());
    }
    if (!body.containsKey("jobs")) {
      body.put("jobs", Collections.emptyList());
    }
    return body;
  }

  private static boolean reachable(URL url) {
    Socket probe = new Socket();
    try {
      probe.connect(new InetSocketAddress(url.getHost(), url.getPort()), CONNECT_TIMEOUT_MS);
      return true;
    } catch (Exception e) {
      return false;
    } finally {
      try {
        probe.close();
      } catch (Exception ignored) {
        // ignore
      }
    }
  }

  private static String resourceJson(Map<String, Object> body) {
    JSONObject response = new JSONObject();
    response.put("resource", body);
    return response.toString();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
