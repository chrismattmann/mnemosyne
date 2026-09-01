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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.oodt.cas.metadata.Metadata;

/**
 * PGE progress for OPSUI. Prefers {@code JobDir/.progress} when that file
 * exists — it belongs to the current task. Metadata keys are a fallback
 * (watcher stamps, or a PGE whose JobDir was not persisted). A previous
 * task's {@code PGETask_*} keys otherwise hide a later task's bar.
 */
final class PgeProgressPeek {

  static final String FILE_NAME = ".progress";

  private PgeProgressPeek() {
  }

  static Map<String, Object> of(Metadata met) {
    Map<String, Object> fromFile = fromFile(jobDir(met));
    if (fromFile != null) {
      return fromFile;
    }
    return fromKeys(met);
  }

  static Map<String, Object> fromKeys(Metadata met) {
    if (met == null) {
      return null;
    }
    String done = first(met, "PGETask_Done", "PGETask/Done");
    String total = first(met, "PGETask_Total", "PGETask/Total");
    String message = first(met, "PGETask_Progress", "PGETask/Progress");
    if (done.length() == 0 && total.length() == 0 && message.length() == 0) {
      return null;
    }
    return row(parseInt(done), parseInt(total), message);
  }

  static Map<String, Object> fromFile(File dir) {
    if (dir == null || !dir.isDirectory()) {
      return null;
    }
    File file = new File(dir, FILE_NAME);
    if (!file.isFile() || file.length() <= 0 || file.length() > 8192) {
      return null;
    }
    Integer done = null;
    Integer total = null;
    String message = "";
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.length() == 0 || trimmed.charAt(0) == '#') {
          continue;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String name = trimmed.substring(0, eq).trim().toLowerCase();
        String value = trimmed.substring(eq + 1).trim();
        if ("done".equals(name)) {
          done = parseInt(value);
        } else if ("total".equals(name)) {
          total = parseInt(value);
        } else if ("msg".equals(name) || "message".equals(name)) {
          message = value;
        }
      }
    } catch (Exception e) {
      return null;
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (Exception ignored) {
        }
      }
    }
    if (done == null && total == null && message.length() == 0) {
      return null;
    }
    return row(done, total, message);
  }

  private static Map<String, Object> row(Integer done, Integer total, String message) {
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    if (done != null) {
      out.put("done", done);
    }
    if (total != null) {
      out.put("total", total);
    }
    if (message != null && message.length() > 0) {
      out.put("message", message);
    }
    return out.isEmpty() ? null : out;
  }

  private static File jobDir(Metadata met) {
    String path = first(met, "JobDir", "JobOutputDir");
    if (path.length() == 0) {
      return null;
    }
    File dir = new File(path);
    return dir.isDirectory() ? dir : dir.getParentFile();
  }

  private static String first(Metadata met, String... keys) {
    if (met == null) {
      return "";
    }
    for (int i = 0; i < keys.length; i++) {
      String value = met.getMetadata(keys[i]);
      if (value != null && value.trim().length() > 0) {
        return value.trim();
      }
    }
    return "";
  }

  private static Integer parseInt(String raw) {
    if (raw == null || raw.length() == 0) {
      return null;
    }
    try {
      return Integer.valueOf(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
