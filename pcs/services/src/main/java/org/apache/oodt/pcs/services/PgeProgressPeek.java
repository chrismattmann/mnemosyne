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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.oodt.cas.metadata.Metadata;

/**
 * PGE progress for OPSUI. Prefers {@code JobDir/.progress} when that file
 * exists — it belongs to the current task. Metadata keys are a fallback
 * (watcher stamps, or a PGE whose JobDir was not persisted). A previous
 * task's {@code PGETask_*} keys otherwise hide a later task's bar.
 *
 * <p>Both sources come out of the instance's <em>shared</em> context, and
 * every task in a workflow writes its own {@code JobDir} into it, so the
 * value left there is the last task's, not the task being drawn. A finished
 * task then shows the bar of whatever ran after it: a completed
 * {@code IndexImageSpace} reporting {@code split 75 / 686}, which is
 * {@code IndexFGBG} counting.
 *
 * <p>The window fixes it. A {@code .progress} file written after a task
 * ended, or before it started, was not written by that task, and its
 * modification time says so. When the window is not known nothing is
 * excluded, which is the old behaviour.
 */
final class PgeProgressPeek {

  static final String FILE_NAME = ".progress";

  private PgeProgressPeek() {
  }

  static Map<String, Object> of(Metadata met) {
    return of(met, null, null);
  }

  /**
   * Progress for a task that ran between these two times.
   *
   * @param startedAt when the task began, or null if not known
   * @param endedAt   when it finished, or null if it has not
   */
  static Map<String, Object> of(Metadata met, Date startedAt, Date endedAt) {
    File dir = jobDir(met);
    Map<String, Object> fromFile = fromFile(dir);
    if (fromFile != null) {
      // Written outside the task's window: it is another task's file, reached
      // through a JobDir that task overwrote. Report nothing rather than
      // somebody else's count.
      return writtenWithin(dir, startedAt, endedAt) ? fromFile : null;
    }
    if (endedAt != null) {
      // Finished, and no file of its own. The PGETask_* keys in the shared
      // context are then the next task's for the same reason, so they are no
      // safer than the file would have been.
      return null;
    }
    return fromKeys(met);
  }

  /**
   * Whether the {@code .progress} in this directory was written while the
   * task was running.
   *
   * <p>The tolerance is one second, not more: the task that follows starts
   * within moments of this one ending, and a generous window would let its
   * first write count as this one's last.
   */
  static boolean writtenWithin(File dir, Date startedAt, Date endedAt) {
    if (dir == null || (startedAt == null && endedAt == null)) {
      return true;
    }
    long modified = new File(dir, FILE_NAME).lastModified();
    if (modified <= 0L) {
      return true;
    }
    long slack = 1000L;
    if (startedAt != null && modified < startedAt.getTime() - slack) {
      return false;
    }
    return endedAt == null || modified <= endedAt.getTime() + slack;
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
