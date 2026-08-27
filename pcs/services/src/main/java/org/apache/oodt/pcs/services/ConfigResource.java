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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import net.sf.json.JSONObject;

import org.apache.oodt.cas.metadata.util.PathUtils;
import org.apache.oodt.pcs.services.config.PCSServiceConfMetKeys;

/**
 * Read-only dump of the File Manager, Workflow Manager, and Resource Manager
 * properties files for the Vue OPSUI daemon cards.
 */
@Path("config")
public class ConfigResource extends PCSService {

  private static final long serialVersionUID = 1L;

  @GET
  @Path("{component}")
  @Produces("application/json")
  public String component(@PathParam("component") String component) throws IOException {
    DaemonConfig spec = specFor(component);
    if (spec == null) {
      throw new ResourceNotFoundException("Unknown component [" + component + "]");
    }
    String path = PCSService.conf.resolvePath(spec.key, spec.defaultPath);
    File file = new File(path);
    if (!file.isFile()) {
      throw new ResourceNotFoundException("No properties file at [" + path + "]");
    }
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("id", spec.id);
    body.put("name", spec.name);
    body.put("path", file.getAbsolutePath());
    body.put("properties", readProperties(file));
    JSONObject response = new JSONObject();
    response.put("config", body);
    return response.toString();
  }

  static List<Map<String, String>> readProperties(File file) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(
        new FileInputStream(file), Charset.forName("ISO-8859-1")));
    try {
      List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
      String logical = null;
      String line;
      while ((line = reader.readLine()) != null) {
        boolean continued = endsWithContinuation(line);
        if (continued) {
          line = line.substring(0, line.length() - 1);
        }
        if (logical == null) {
          String trimmed = line.trim();
          if (trimmed.length() == 0 || trimmed.charAt(0) == '#' || trimmed.charAt(0) == '!') {
            continue;
          }
          if (trimmed.startsWith("include ") || trimmed.startsWith("!include")) {
            continue;
          }
          logical = line;
        } else {
          logical += stripLeadingWhite(line);
        }
        if (!continued) {
          Map<String, String> row = parseAssignment(logical);
          if (row != null) {
            rows.add(row);
          }
          logical = null;
        }
      }
      if (logical != null) {
        Map<String, String> row = parseAssignment(logical);
        if (row != null) {
          rows.add(row);
        }
      }
      return rows;
    } finally {
      reader.close();
    }
  }

  private static Map<String, String> parseAssignment(String logical) {
    String trimmed = logical.trim();
    if (trimmed.length() == 0) {
      return null;
    }
    int eq = indexOfSeparator(trimmed);
    if (eq <= 0) {
      return null;
    }
    String key = trimmed.substring(0, eq).trim();
    String value = trimmed.substring(eq + 1).trim();
    Map<String, String> row = new LinkedHashMap<String, String>();
    row.put("key", key);
    row.put("value", redact(key, PathUtils.replaceEnvVariables(value)));
    return row;
  }

  static boolean endsWithContinuation(String line) {
    int slashes = 0;
    for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
      slashes++;
    }
    return slashes % 2 == 1;
  }

  private static String stripLeadingWhite(String line) {
    int i = 0;
    while (i < line.length()) {
      char c = line.charAt(i);
      if (c != ' ' && c != '\t' && c != '\f') {
        break;
      }
      i++;
    }
    return line.substring(i);
  }

  static boolean isSecret(String key) {
    if (key == null) {
      return false;
    }
    String lower = key.toLowerCase(Locale.ENGLISH);
    return lower.indexOf("password") >= 0 || lower.indexOf("passwd") >= 0
        || lower.endsWith(".pass") || lower.indexOf("secret") >= 0
        || lower.indexOf("credential") >= 0 || lower.indexOf(".token") >= 0;
  }

  static String redact(String key, String value) {
    return isSecret(key) ? "••••" : (value == null ? "" : value);
  }

  private static int indexOfSeparator(String line) {
    int eq = line.indexOf('=');
    int colon = line.indexOf(':');
    if (eq < 0) {
      return colon;
    }
    if (colon < 0) {
      return eq;
    }
    return Math.min(eq, colon);
  }

  private static DaemonConfig specFor(String component) {
    if (component == null) {
      return null;
    }
    String id = component.trim().toLowerCase(Locale.ENGLISH);
    if ("filemgr".equals(id) || "fm".equals(id)) {
      return new DaemonConfig("filemgr", "File Manager",
          PCSServiceConfMetKeys.FM_PROPERTIES_PATH,
          "[FILEMGR_HOME]/etc/filemgr.properties");
    }
    if ("workflow".equals(id) || "wm".equals(id)) {
      return new DaemonConfig("workflow", "Workflow Manager",
          PCSServiceConfMetKeys.WM_PROPERTIES_PATH,
          "[WORKFLOW_HOME]/etc/workflow.properties");
    }
    if ("resource".equals(id) || "rm".equals(id)) {
      return new DaemonConfig("resource", "Resource Manager",
          PCSServiceConfMetKeys.RM_PROPERTIES_PATH,
          "[RESMGR_HOME]/etc/resource.properties");
    }
    return null;
  }

  private static final class DaemonConfig {
    private final String id;
    private final String name;
    private final String key;
    private final String defaultPath;

    private DaemonConfig(String id, String name, String key, String defaultPath) {
      this.id = id;
      this.name = name;
      this.key = key;
      this.defaultPath = defaultPath;
    }
  }
}
