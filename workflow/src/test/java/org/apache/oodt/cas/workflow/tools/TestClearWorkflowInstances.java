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

package org.apache.oodt.cas.workflow.tools;

import junit.framework.TestCase;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Emptying a JDBC workflow instance repository.
 */
public class TestClearWorkflowInstances extends TestCase {

  private String url;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    File dir = Files.createTempDirectory("winstdb").toFile();
    this.url = "jdbc:hsqldb:file:" + new File(dir, "winst").getAbsolutePath();
    Connection c = connect();
    Statement s = c.createStatement();
    s.execute("CREATE TABLE WORKFLOW_INSTANCES ("
        + "workflow_instance_id INTEGER, workflow_instance_status VARCHAR(255))");
    s.execute("CREATE TABLE WORKFLOW_INSTANCE_METADATA ("
        + "workflow_instance_id INTEGER, workflow_met_key VARCHAR(255))");
    for (int i = 1; i <= 3; i++) {
      s.execute("INSERT INTO WORKFLOW_INSTANCES VALUES (" + i + ", 'Success')");
      s.execute("INSERT INTO WORKFLOW_INSTANCE_METADATA VALUES (" + i + ", 'k')");
    }
    s.execute("SHUTDOWN");
    c.close();
  }

  public void testEveryInstanceIsRemoved() throws Exception {
    assertEquals("three instances and three metadata rows", 6,
        ClearWorkflowInstances.clear(url, "sa", ""));

    assertEquals(0, countIn("WORKFLOW_INSTANCES"));
    assertEquals(0, countIn("WORKFLOW_INSTANCE_METADATA"));
  }

  /**
   * The rows are what a reset is about, and the tables are not. Deleting the
   * files instead takes the schema with them, and the workflow manager then
   * starts against a database with no WORKFLOW_INSTANCES table and fails
   * every query it is asked -- which reads as a broken manager rather than
   * an empty one.
   */
  public void testTheSchemaSurvives() throws Exception {
    ClearWorkflowInstances.clear(url, "sa", "");

    // Reachable, and still a table: querying it is the proof.
    assertEquals(0, countIn("WORKFLOW_INSTANCES"));

    Connection c = connect();
    Statement s = c.createStatement();
    s.execute("INSERT INTO WORKFLOW_INSTANCES VALUES (9, 'Queued')");
    s.execute("SHUTDOWN");
    c.close();
    assertEquals("the table still accepts instances", 1,
        countIn("WORKFLOW_INSTANCES"));
  }

  /** Clearing an already empty repository is not an error. */
  public void testClearingTwiceIsHarmless() throws Exception {
    ClearWorkflowInstances.clear(url, "sa", "");

    assertEquals("nothing left to delete", 0,
        ClearWorkflowInstances.clear(url, "sa", ""));
  }

  /**
   * A repository without the metadata table is still cleared. Reporting the
   * absence beats failing a reset over a table that was never there.
   */
  public void testAmissingTableIsSkippedRatherThanFatal() throws Exception {
    Connection c = connect();
    Statement s = c.createStatement();
    s.execute("DROP TABLE WORKFLOW_INSTANCE_METADATA");
    s.execute("SHUTDOWN");
    c.close();

    assertEquals("the instances still went", 3,
        ClearWorkflowInstances.clear(url, "sa", ""));
  }

  /** An unreachable repository is reported, not silently treated as empty. */
  public void testAnunreachableRepositoryThrows() {
    try {
      ClearWorkflowInstances.clear("jdbc:hsqldb:hsql://localhost:1/nothing",
          "sa", "");
      fail("expected the failure to be reported");
    } catch (SQLException expected) {
      assertNotNull(expected.getMessage());
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(url, "sa", "");
  }

  private int countIn(String table) throws SQLException {
    Connection c = connect();
    try {
      Statement s = c.createStatement();
      ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table);
      rs.next();
      int count = rs.getInt(1);
      s.execute("SHUTDOWN");
      return count;
    } finally {
      c.close();
    }
  }
}
