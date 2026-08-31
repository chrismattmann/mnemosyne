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

package org.apache.oodt.cas.workflow.instrepo;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import junit.framework.TestCase;

import org.apache.oodt.commons.database.DatabaseConnectionBuilder;

/**
 * Letting go of the database when the manager stops.
 *
 * <p>
 * An embedded HSQLDB file database opens on the first connection and stays
 * open until it is told to shut down. Closing connections does not do it, and
 * neither does stopping everything above it: the database keeps a thread of
 * its own, which holds the process alive and goes on writing the heartbeat in
 * its lock file.
 * </p>
 */
public class TestDataSourceRepositoryRelease extends TestCase {

  private String url;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    File dir = Files.createTempDirectory("winstdb-release").toFile();
    this.url = "jdbc:hsqldb:file:" + new File(dir, "winst").getAbsolutePath();
    Connection c = connect();
    Statement s = c.createStatement();
    s.execute("CREATE TABLE WORKFLOW_INSTANCES ("
        + "workflow_instance_id INTEGER, workflow_instance_status VARCHAR(255))");
    s.execute("SHUTDOWN");
    c.close();
  }

  /**
   * After release, the database is closed. A closed HSQLDB file database can
   * be opened again by another process; an open one refuses.
   */
  public void testReleaseClosesTheDatabase() throws Exception {
    DataSourceWorkflowInstanceRepository repo = repository();
    // Open it, the way any use of the repository would.
    repo.getNumWorkflowInstances();
    assertTrue("the database should be open before release", isOpen());

    repo.release();

    assertFalse("release left the database open", isOpen());
  }

  /** Releasing something already closed is not an error. */
  public void testReleaseTwiceIsHarmless() throws Exception {
    DataSourceWorkflowInstanceRepository repo = repository();
    repo.getNumWorkflowInstances();
    repo.release();
    repo.release();
  }

  /** The default is to do nothing, for a repository holding nothing. */
  public void testReleaseIsOptionalForARepository() {
    WorkflowInstanceRepository holdsNothing = new MemoryWorkflowInstanceRepository(20);
    holdsNothing.release();
  }

  private DataSourceWorkflowInstanceRepository repository() {
    DataSource ds = DatabaseConnectionBuilder.buildDataSource("sa", "",
        "org.hsqldb.jdbc.JDBCDriver", url);
    return new DataSourceWorkflowInstanceRepository(ds, true, 20);
  }

  /**
   * Whether the database is still held open by this process. HSQLDB lets a
   * second in-process connection in when the database is open and reopens it
   * from disk when it is not, so the lock file is what actually answers.
   */
  private boolean isOpen() {
    File lock = new File(url.substring("jdbc:hsqldb:file:".length()) + ".lck");
    return lock.exists();
  }

  private Connection connect() throws Exception {
    Class.forName("org.hsqldb.jdbc.JDBCDriver");
    return java.sql.DriverManager.getConnection(url, "sa", "");
  }
}
