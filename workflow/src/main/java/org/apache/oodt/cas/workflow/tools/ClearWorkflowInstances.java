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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Empties a {@link
 * org.apache.oodt.cas.workflow.instrepo.DataSourceWorkflowInstanceRepository}.
 *
 * <p>
 * A deployment resets by clearing the state a run leaves behind, and the
 * instances are part of that: without this a reset deployment comes back up
 * still holding the previous run's instances, mixed in with the new ones and
 * with nothing to say which run each belongs to.
 * </p>
 *
 * <p>
 * The rows are what a reset is about, and the tables are not. Deleting the
 * database files would be simpler and takes the schema with them: the
 * workflow manager then starts against a database with no WORKFLOW_INSTANCES
 * table and fails every query it is asked, which looks like a broken manager
 * rather than an empty one.
 * </p>
 *
 * <p>
 * Nothing may hold the database while this runs. A file-backed HSQLDB allows
 * one writer, so the manager has to be stopped first.
 * </p>
 *
 * <p>
 * The Lucene repository needs no equivalent: its instances are a directory,
 * which a caller can empty without help.
 * </p>
 */
public final class ClearWorkflowInstances {

  private static final Logger LOG = Logger
      .getLogger(ClearWorkflowInstances.class.getName());

  /** Children before parents, so no delete trips over a reference. */
  static final String[] TABLES = {
      "WORKFLOW_INSTANCE_METADATA", "WORKFLOW_INSTANCES"
  };

  private ClearWorkflowInstances() {
  }

  /**
   * Deletes every workflow instance, leaving the schema in place.
   *
   * @param url  the JDBC url of the instance repository
   * @param user the user to connect as
   * @param pass that user's password
   * @return how many rows were deleted in total
   * @throws SQLException if the repository cannot be reached or written
   */
  public static int clear(String url, String user, String pass)
      throws SQLException {
    Connection connection = null;
    int deleted = 0;
    try {
      connection = DriverManager.getConnection(url, user, pass);
      Statement statement = connection.createStatement();
      for (int i = 0; i < TABLES.length; i++) {
        try {
          int rows = statement.executeUpdate("DELETE FROM " + TABLES[i]);
          deleted += rows;
          LOG.log(Level.INFO, "Cleared " + rows + " rows from " + TABLES[i]);
        } catch (SQLException e) {
          // A repository that never held this table is already as empty as
          // it needs to be. Saying so beats failing the reset over it.
          LOG.log(Level.INFO, "Skipped " + TABLES[i] + ": " + e.getMessage());
        }
      }
      // Leaves the files consistent, so the manager does not meet a stale
      // lock the next time it starts.
      statement.execute("SHUTDOWN");
    } finally {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException ignored) {
          // nothing useful to do about a connection that will not close
        }
      }
    }
    return deleted;
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("usage: ClearWorkflowInstances <jdbcUrl> [user] [pass]");
      System.exit(2);
    }
    try {
      int deleted = clear(args[0], args.length > 1 ? args[1] : "sa",
          args.length > 2 ? args[2] : "");
      System.out.println("Cleared " + deleted + " workflow instance rows");
    } catch (SQLException e) {
      System.err.println("Unable to clear the workflow instance repository at ["
          + args[0] + "]: " + e.getMessage());
      System.exit(1);
    }
  }
}
