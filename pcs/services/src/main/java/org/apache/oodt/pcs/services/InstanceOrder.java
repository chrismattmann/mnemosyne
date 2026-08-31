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

package org.apache.oodt.pcs.services;

import org.apache.oodt.commons.util.DateConvert;

import java.util.Comparator;
import java.util.Map;

/**
 * Orders workflow instances across the whole set rather than a page of it.
 *
 * <p>
 * Sorting a page after it arrives sorts twenty rows the service chose for
 * unrelated reasons. A column header that does that looks like it sorts the
 * table: asking for the longest wall clock puts the longest of *those twenty*
 * at the top and says nothing about the rest, which is worse than not
 * offering the sort, because a wrong answer does not announce itself.
 * </p>
 *
 * <p>
 * The keys mirror what the browser used to compute, so a sort means the same
 * thing whoever performs it.
 * </p>
 */
final class InstanceOrder {

  /** Statuses that mean the instance is over. */
  private static final String[] FINISHED = {
      "SUCCESS", "FINISHED", "EXECUTIONCOMPLETE", "RESULTSSUCCESS"
  };

  private static final String[] FAILED = {
      "FAILURE", "RESULTSFAILURE", "STOPPED", "ERROR", "OFF"
  };

  private InstanceOrder() {
  }

  /**
   * @param field one of workflow, product, status, task, start, end, wall
   * @param dir   "desc" for descending, anything else ascending
   * @param now   the clock a running instance's elapsed time is measured to
   * @return the comparator, or null if the field is not one that is ordered
   */
  static Comparator<Map<String, Object>> by(final String field, String dir,
      final long now) {
    if (field == null || field.trim().length() == 0) {
      return null;
    }
    final String key = field.trim();
    if (!isSortable(key)) {
      return null;
    }
    final int sign = "desc".equalsIgnoreCase(dir) ? -1 : 1;

    return new Comparator<Map<String, Object>>() {
      public int compare(Map<String, Object> a, Map<String, Object> b) {
        Comparable left = sortKey(a, key, now);
        Comparable right = sortKey(b, key, now);
        // Nothing to compare sorts last whichever way the column points: an
        // instance with no end time is not "earliest", it is unknown, and
        // burying it under the answer being asked for is the honest place.
        if (left == null && right == null) {
          return 0;
        }
        if (left == null) {
          return 1;
        }
        if (right == null) {
          return -1;
        }
        return sign * left.compareTo(right);
      }
    };
  }

  static boolean isSortable(String field) {
    return "workflow".equals(field) || "product".equals(field)
        || "status".equals(field) || "task".equals(field)
        || "start".equals(field) || "end".equals(field)
        || "wall".equals(field);
  }

  @SuppressWarnings("rawtypes")
  static Comparable sortKey(Map<String, Object> inst, String field, long now) {
    if (inst == null) {
      return null;
    }
    if ("workflow".equals(field)) {
      return lower(first(inst, "workflowName", "workflowId"));
    }
    if ("product".equals(field)) {
      return lower(str(inst.get("productName")));
    }
    if ("status".equals(field)) {
      return lower(str(inst.get("status")));
    }
    if ("task".equals(field)) {
      return lower(first(inst, "currentTaskName", "currentTaskId"));
    }
    if ("start".equals(field)) {
      return millis(str(inst.get("startDateTime")));
    }
    if ("end".equals(field)) {
      return millis(str(inst.get("endDateTime")));
    }
    if ("wall".equals(field)) {
      return wallClockMillis(inst, now);
    }
    return null;
  }

  /**
   * How long an instance has been running, or ran for.
   *
   * <p>
   * Derived rather than stored, and deliberately absent in two cases: an
   * instance that finished without recording an end, and one the engine is
   * not running. Both would otherwise measure to now and climb for ever,
   * which reads as the longest-running work in the deployment.
   * </p>
   */
  static Long wallClockMillis(Map<String, Object> inst, long now) {
    Long start = millis(str(inst.get("startDateTime")));
    if (start == null) {
      return null;
    }
    Long end = millis(str(inst.get("endDateTime")));
    if (end != null) {
      long elapsed = end.longValue() - start.longValue();
      return Long.valueOf(elapsed < 0 ? 0 : elapsed);
    }
    if (isTerminal(str(inst.get("status")))) {
      return null;
    }
    if (Boolean.TRUE.equals(inst.get("abandoned"))) {
      return null;
    }
    long elapsed = now - start.longValue();
    return Long.valueOf(elapsed < 0 ? 0 : elapsed);
  }

  static boolean isTerminal(String status) {
    String value = status == null ? "" : status.trim().toUpperCase();
    for (int i = 0; i < FINISHED.length; i++) {
      if (FINISHED[i].equals(value)) {
        return true;
      }
    }
    for (int i = 0; i < FAILED.length; i++) {
      if (FAILED[i].equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static Long millis(String iso) {
    if (iso == null || iso.trim().length() == 0) {
      return null;
    }
    try {
      // The same parser the rest of the codebase uses for these stamps,
      // rather than javax.xml.bind, which has not been in the JDK since 11
      // and would only be there by accident of a transitive dependency.
      return Long.valueOf(DateConvert.isoParse(iso.trim()).getTime());
    } catch (Exception e) {
      return null;
    }
  }

  private static String first(Map<String, Object> inst, String a, String b) {
    String value = str(inst.get(a));
    return value.length() > 0 ? value : str(inst.get(b));
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }

  /**
   * Lower-cased for a case-insensitive order, and null when there is nothing
   * there -- a blank product column is missing, not first alphabetically, so
   * it sorts with the other unknowns rather than ahead of every real name.
   */
  private static String lower(String value) {
    if (value == null || value.trim().length() == 0) {
      return null;
    }
    return value.toLowerCase();
  }
}
