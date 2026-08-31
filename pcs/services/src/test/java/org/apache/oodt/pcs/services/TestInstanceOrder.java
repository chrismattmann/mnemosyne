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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

public class TestInstanceOrder extends TestCase {

  private static final long NOW = 1750000000000L;

  public void testOnlyKnownColumnsOrder() {
    assertTrue(InstanceOrder.isSortable("wall"));
    assertTrue(InstanceOrder.isSortable("start"));
    assertFalse(InstanceOrder.isSortable("id"));
    assertFalse(InstanceOrder.isSortable(null));
    assertNull(InstanceOrder.by("id", "asc", NOW));
    assertNull(InstanceOrder.by("", "asc", NOW));
    assertNull(InstanceOrder.by(null, "asc", NOW));
  }

  public void testWallClockOfAFinishedInstanceIsTheSpanItRan() {
    Map<String, Object> inst = inst("Success",
        "2026-08-28T10:00:00.000Z", "2026-08-28T10:02:30.000Z");
    assertEquals(Long.valueOf(150000L), InstanceOrder.wallClockMillis(inst, NOW));
  }

  public void testWallClockOfARunningInstanceRunsToNow() {
    Map<String, Object> inst = inst("PGE EXEC", "2026-08-28T10:00:00.000Z", null);
    inst.put("running", Boolean.TRUE);
    long start = 0L;
    try {
      start = org.apache.oodt.commons.util.DateConvert
          .isoParse("2026-08-28T10:00:00.000Z").getTime();
    } catch (Exception e) {
      fail(e.getMessage());
    }
    Long ms = InstanceOrder.wallClockMillis(inst, start + 5000L);
    assertEquals(Long.valueOf(5000L), ms);
  }

  public void testAnInstanceThatEndedWithoutSayingSoHasNoWallClock() {
    // Measuring it to now would make it climb for ever and take the top of a
    // descending wall clock sort, which is exactly the wrong answer.
    Map<String, Object> inst = inst("Success", "2026-08-28T10:00:00.000Z", null);
    assertNull(InstanceOrder.wallClockMillis(inst, NOW));
  }

  public void testAnAbandonedInstanceHasNoWallClock() {
    Map<String, Object> inst = inst("PGE EXEC", "2026-08-28T10:00:00.000Z", null);
    inst.put("abandoned", Boolean.TRUE);
    assertNull(InstanceOrder.wallClockMillis(inst, NOW));
  }

  public void testDescendingWallClockPutsTheLongestFirst() {
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    rows.add(named("short", "2026-08-28T10:00:00.000Z", "2026-08-28T10:00:10.000Z"));
    rows.add(named("long", "2026-08-28T10:00:00.000Z", "2026-08-28T11:00:00.000Z"));
    rows.add(named("middle", "2026-08-28T10:00:00.000Z", "2026-08-28T10:05:00.000Z"));
    Collections.sort(rows, InstanceOrder.by("wall", "desc", NOW));
    assertEquals("long", rows.get(0).get("workflowName"));
    assertEquals("middle", rows.get(1).get("workflowName"));
    assertEquals("short", rows.get(2).get("workflowName"));
  }

  public void testUnknownsSortLastWhicheverWayTheColumnPoints() {
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    rows.add(named("known", "2026-08-28T10:00:00.000Z", "2026-08-28T10:00:10.000Z"));
    rows.add(named("unknown", "2026-08-28T10:00:00.000Z", null));
    rows.get(1).put("status", "Success");
    Collections.sort(rows, InstanceOrder.by("wall", "asc", NOW));
    assertEquals("known", rows.get(0).get("workflowName"));
    Collections.sort(rows, InstanceOrder.by("wall", "desc", NOW));
    assertEquals("known", rows.get(0).get("workflowName"));
  }

  public void testNamesOrderIgnoringCaseAndBlanksSortLast() {
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    rows.add(row("workflowName", "beta"));
    rows.add(row("workflowName", ""));
    rows.add(row("workflowName", "Alpha"));
    Collections.sort(rows, InstanceOrder.by("workflow", "asc", NOW));
    assertEquals("Alpha", rows.get(0).get("workflowName"));
    assertEquals("beta", rows.get(1).get("workflowName"));
    assertEquals("", rows.get(2).get("workflowName"));
  }

  public void testAWorkflowWithNoNameFallsBackToItsId() {
    Map<String, Object> inst = new HashMap<String, Object>();
    inst.put("workflowId", "urn:drat:MimePartitioner");
    assertEquals("urn:drat:mimepartitioner",
        InstanceOrder.sortKey(inst, "workflow", NOW));
  }

  public void testATaskColumnFallsBackToItsId() {
    Map<String, Object> inst = new HashMap<String, Object>();
    inst.put("currentTaskId", "urn:drat:RatAudit");
    assertEquals("urn:drat:rataudit",
        InstanceOrder.sortKey(inst, "task", NOW));
  }

  public void testStartOrdersByTheStampNotItsText() {
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    rows.add(named("second", "2026-08-28T10:00:00.000Z", null));
    rows.add(named("first", "2026-08-27T23:59:59.000Z", null));
    Collections.sort(rows, InstanceOrder.by("start", "asc", NOW));
    assertEquals("first", rows.get(0).get("workflowName"));
  }

  public void testAnUnparseableStampIsUnknownRatherThanZero() {
    Map<String, Object> inst = inst("Success", "not a date", null);
    assertNull(InstanceOrder.sortKey(inst, "start", NOW));
  }

  private static Map<String, Object> row(String key, Object value) {
    Map<String, Object> inst = new HashMap<String, Object>();
    inst.put(key, value);
    return inst;
  }

  private static Map<String, Object> inst(String status, String start, String end) {
    Map<String, Object> row = new HashMap<String, Object>();
    row.put("status", status);
    row.put("startDateTime", start);
    if (end != null) {
      row.put("endDateTime", end);
    }
    return row;
  }

  private static Map<String, Object> named(String name, String start, String end) {
    Map<String, Object> row = inst("PGE EXEC", start, end);
    row.put("workflowName", name);
    return row;
  }
}
