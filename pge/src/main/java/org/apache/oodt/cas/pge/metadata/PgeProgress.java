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
package org.apache.oodt.cas.pge.metadata;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.apache.oodt.cas.metadata.Metadata;

/**
 * How far a running PGE script has gotten. Scripts that opt in write
 * {@code .progress} in the execution directory (done / total / msg). Scripts
 * that do not write it simply report nothing — DRAT and the rest can grow
 * into this later.
 */
public final class PgeProgress {

  public static final String FILE_NAME = ".progress";

  private final Integer done;
  private final Integer total;
  private final String message;

  public PgeProgress(Integer done, Integer total, String message) {
    this.done = done;
    this.total = total;
    this.message = message == null ? "" : message.trim();
  }

  public Integer getDone() {
    return done;
  }

  public Integer getTotal() {
    return total;
  }

  public String getMessage() {
    return message;
  }

  public boolean isEmpty() {
    return done == null && total == null && message.length() == 0;
  }

  public boolean sameAs(PgeProgress other) {
    if (other == null) {
      return false;
    }
    return eq(done, other.done) && eq(total, other.total) && message.equals(other.message);
  }

  public Metadata toMetadata() {
    Metadata met = new Metadata();
    if (done != null) {
      putBoth(met, PgeTaskMetKeys.PROGRESS_DONE, String.valueOf(done));
    }
    if (total != null) {
      putBoth(met, PgeTaskMetKeys.PROGRESS_TOTAL, String.valueOf(total));
    }
    if (message.length() > 0) {
      putBoth(met, PgeTaskMetKeys.PROGRESS_MESSAGE, message);
    }
    return met;
  }

  public static PgeProgress fromMetadata(Metadata met) {
    if (met == null) {
      return null;
    }
    Integer done = parseInt(first(met, "PGETask_Done", "PGETask/Done"));
    Integer total = parseInt(first(met, "PGETask_Total", "PGETask/Total"));
    String message = first(met, "PGETask_Progress", "PGETask/Progress");
    PgeProgress progress = new PgeProgress(done, total, message);
    return progress.isEmpty() ? null : progress;
  }

  public static PgeProgress readFile(File file) {
    if (file == null || !file.isFile() || file.length() <= 0 || file.length() > 8192) {
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
    PgeProgress progress = new PgeProgress(done, total, message);
    return progress.isEmpty() ? null : progress;
  }

  private static void putBoth(Metadata met, PgeTaskMetKeys key, String value) {
    met.replaceMetadata(key.name, value);
    met.replaceMetadata(key.legacyName, value);
  }

  private static String first(Metadata met, String... keys) {
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

  private static boolean eq(Integer a, Integer b) {
    if (a == null) {
      return b == null;
    }
    return a.equals(b);
  }
}
