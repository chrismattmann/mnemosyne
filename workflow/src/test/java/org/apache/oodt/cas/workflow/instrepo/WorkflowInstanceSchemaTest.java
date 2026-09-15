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
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import junit.framework.TestCase;

/**
 * The schema bootstrap, against a real HSQLDB file database.
 *
 * <p>
 * This lived in three application repositories before it lived here, copied
 * between them, and one copy had quietly dropped a column. The tests that
 * matter are therefore that the one shipped schema loads, takes the URN ids every
 * that running the tool twice is not an error -- it runs on every startup.
 * </p>
 */
public class WorkflowInstanceSchemaTest extends TestCase {

  private File dir;

  protected void setUp() throws Exception {
    dir = File.createTempFile("winst", "");
    assertTrue(dir.delete());
    assertTrue(dir.mkdirs());
  }

  private File propertiesFor(String name) throws Exception {
    File props = new File(dir, name + ".properties");
    FileWriter out = new FileWriter(props);
    out.write("workflow.engine.instanceRep.factory = "
        + "org.apache.oodt.cas.workflow.instrepo."
        + "DataSourceWorkflowInstanceRepositoryFactory\n");
    out.write("org.apache.oodt.cas.workflow.instanceRep.datasource.jdbc.url="
        + "jdbc:hsqldb:file:" + new File(dir, name).getAbsolutePath() + "\n");
    out.write("org.apache.oodt.cas.workflow.instanceRep.datasource.jdbc.user=sa\n");
    out.write("org.apache.oodt.cas.workflow.instanceRep.datasource.jdbc.pass=\n");
    out.write("org.apache.oodt.cas.workflow.instanceRep.datasource.jdbc.driver="
        + "org.hsqldb.jdbc.JDBCDriver\n");
    out.close();
    return props;
  }

  private File sql(String name) {
    File f = new File("src/main/resources/" + name);
    assertTrue(name + " is not shipped", f.isFile());
    return f;
  }

  public void testTheSchemaLoads() throws Exception {
    assertTrue(WorkflowInstanceSchema.ensure(propertiesFor("one"), sql("workflow.sql")));
  }

  public void testThereIsOnlyOneSchemaToShip() throws Exception {
    // One file for every deployment. A second .sql beside it is how three
    // repositories ended up each transforming this one by hand.
    File resources = new File("src/main/resources");
    String[] found = resources.list(new java.io.FilenameFilter() {
      public boolean accept(File dir, String name) {
        // Not the Oracle sequence helper, which is a different artefact.
        return name.startsWith("workflow") && name.endsWith(".sql")
            && !name.contains("sequences");
      }
    });
    assertNotNull(found);
    assertEquals("expected only workflow.sql, found "
        + java.util.Arrays.toString(found), 1, found.length);
  }

  public void testRunningItTwiceIsNotAnError() throws Exception {
    File props = propertiesFor("twice");
    assertTrue(WorkflowInstanceSchema.ensure(props, sql("workflow.sql")));
    // bin/oodt runs this on every start, so the second run is the common case.
    assertTrue(WorkflowInstanceSchema.ensure(props, sql("workflow.sql")));
  }

  public void testStringIdVariantTakesUrnTaskIds() throws Exception {
    File props = propertiesFor("urns");
    assertTrue(WorkflowInstanceSchema.ensure(props, sql("workflow.sql")));
    Class.forName("org.hsqldb.jdbc.JDBCDriver");
    Connection c = DriverManager.getConnection(
        "jdbc:hsqldb:file:" + new File(dir, "urns").getAbsolutePath(), "sa", "");
    try {
      c.createStatement().executeUpdate(
          "INSERT INTO workflow_instances (workflow_instance_status, workflow_id, "
          + "current_task_id) VALUES ('QUEUED', 'urn:drat:Audit', "
          + "'urn:drat:MimePartitioner')");
      ResultSet rs = c.createStatement().executeQuery(
          "SELECT current_task_id FROM workflow_instances");
      assertTrue(rs.next());
      assertEquals("urn:drat:MimePartitioner", rs.getString(1));
    } finally {
      c.createStatement().execute("SHUTDOWN");
      c.close();
    }
  }

  public void testTheBundledSchemaIsUsedWhenNoFileIsGiven() throws Exception {
    // What every deployment gets: bin/oodt names no .sql and the copy inside
    // cas-workflow is applied. This is what lets an application repository
    // ship no schema file of its own.
    //
    // Only the mechanism is asserted here, not which schema arrived. Under
    // surefire, src/test/resources/workflow.sql shadows the main one on the
    // classpath -- it is a fixture with DROP TABLE statements, used by
    // TestWorkflowDataSourceRepository -- so the resource found in-test is
    // deliberately not the shipped one. The shipped schema's content is
    // covered by TestShippedSchemaAcceptsAnInstance, which reads it by path.
    assertTrue(WorkflowInstanceSchema.ensure(propertiesFor("bundled"), null));
    assertFalse("no statements were read from the bundled schema",
        WorkflowInstanceSchema.statements(null).isEmpty());
  }

  public void testTheBundledSchemaIsOnTheClasspath() throws Exception {
    // If this resource ever stops being packaged, the fallback above turns
    // into an exception at deployment start rather than at build time.
    assertNotNull("/workflow.sql is not in the jar",
        WorkflowInstanceSchema.class.getResourceAsStream(
            WorkflowInstanceSchema.BUNDLED_SQL));
  }

  public void testALuceneRepositoryIsLeftAlone() throws Exception {
    File props = new File(dir, "lucene.properties");
    FileWriter out = new FileWriter(props);
    out.write("workflow.engine.instanceRep.factory = "
        + "org.apache.oodt.cas.workflow.instrepo."
        + "LuceneWorkflowInstanceRepositoryFactory\n");
    out.close();
    // bin/oodt calls this unconditionally, so it has to no-op rather than fail.
    assertTrue(WorkflowInstanceSchema.ensure(props, sql("workflow.sql")));
  }
}
