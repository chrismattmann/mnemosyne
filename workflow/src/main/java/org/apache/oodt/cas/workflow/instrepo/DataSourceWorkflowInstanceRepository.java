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

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;
import org.apache.oodt.cas.workflow.util.DbStructFactory;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

//JDK imports

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * A {@link WorkflowInstanceRepository} that persists {@link WorkflowInstance}s
 * to a JDBC-accessible DBMS.
 * </p>.
 */
public class DataSourceWorkflowInstanceRepository extends
        AbstractPaginatibleInstanceRepository {

    /* our data source */
    private DataSource dataSource = null;

    /* our log stream */
    private static final Logger LOG = Logger
            .getLogger(DataSourceWorkflowInstanceRepository.class.getName());

    /* should we quote fields or not */
    private boolean quoteFields = false;

    public DataSourceWorkflowInstanceRepository(DataSource ds,
            boolean quoteFields, int pageSize) {
        this.dataSource = ds;
        this.quoteFields = quoteFields;
        this.pageSize = pageSize;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.workflow.engine.WorkflowInstanceRepository#addWorkflowInstance(org.apache.oodt.cas.workflow.structs.WorkflowInstance)
     */
    public synchronized void addWorkflowInstance(WorkflowInstance wInst)
            throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;
        PreparedStatement insert = null;
        ResultSet rs = null;

        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            statement = conn.createStatement();

            String startWorkflowSql;
            String taskIdField;
            String workflowIdField;

            // quoteFields decided whether to wrap these in quotes for the SQL
            // literal. They are bound parameters now, so the driver decides
            // how to render them and adding quotes here would store them.
            taskIdField = wInst.getWorkflow().getTasks().get(0).getTaskId();
            workflowIdField = wInst.getWorkflow().getId();

            startWorkflowSql = "INSERT INTO workflow_instances "
                    + "(workflow_instance_status, workflow_id, current_task_id,"
                    + "start_date_time, end_date_time, current_task_start_date_time,"
                    + "current_task_end_date_time, priority, times_blocked) " + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            // Bound, not concatenated: an apostrophe in any of these closed
            // the literal it sat in and took the statement with it.
            LOG.log(Level.FINE, "sql: Executing: " + startWorkflowSql);
            insert = conn.prepareStatement(startWorkflowSql,
                    Statement.RETURN_GENERATED_KEYS);
            insert.setString(1, wInst.getStatus());
            insert.setString(2, workflowIdField);
            insert.setString(3, taskIdField);
            insert.setString(4, wInst.getStartDateTimeIsoStr());
            insert.setString(5, wInst.getEndDateTimeIsoStr());
            insert.setString(6, wInst.getCurrentTaskStartDateTimeIsoStr());
            insert.setString(7, wInst.getCurrentTaskEndDateTimeIsoStr());
            insert.setDouble(8, wInst.getPriority().getValue());
            insert.setInt(9, wInst.getTimesBlocked());
            insert.execute();

            // The id comes back from the insert that generated it.
            //
            // It used to be read with SELECT MAX(workflow_instance_id), which
            // is the id of whichever row happens to be highest at that
            // moment, so two writers racing each other were handed the same
            // one and each went on to attach its metadata to the other's
            // instance. The synchronized block around it did nothing about
            // that: it locked on a String constant, which the JVM interns, so
            // every repository in the process contended on one lock and none
            // of them excluded a second process.
            String workflowInstId = "";
            rs = insert.getGeneratedKeys();
            if (rs != null && rs.next()) {
                workflowInstId = String.valueOf(rs.getInt(1));
            }
            if (workflowInstId.isEmpty()) {
                throw new InstanceRepositoryException(
                    "The database returned no id for the workflow instance it "
                    + "just stored; the workflow_instances table needs an "
                    + "identity column, as the shipped workflow.sql now "
                    + "declares");
            }

            conn.commit();
            wInst.setId(workflowInstId);

            // now add its metadata
            addWorkflowInstanceMetadata(wInst);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING, "Exception starting workflow. Message: "
                    + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback startWorkflow transaction. Message: "
                                + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {

            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }

            }

            if (insert != null) {
                try {
                    insert.close();
                } catch (SQLException ignore) {
                }
            }

            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

    }
    

    @Override
    public synchronized boolean clearWorkflowInstances() throws InstanceRepositoryException {
      Connection conn = null;
      Statement statement = null;

      try {
          conn = dataSource.getConnection();
          conn.setAutoCommit(false);
          statement = conn.createStatement();
          
          String deleteSql = "DELETE FROM workflow_instances";
          
          LOG.log(Level.FINE, "deleteSql: Executing: "
                  + deleteSql);
          statement.execute(deleteSql);
          conn.commit();

      } catch (Exception e) {
          LOG.log(Level.SEVERE, e.getMessage());
          LOG.log(Level.WARNING,
                  "Exception deleting all workflow instances. Message: "
                          + e.getMessage());
          try {
              if (conn != null) {
                  conn.rollback();
              }
          } catch (SQLException e2) {
              LOG.log(Level.SEVERE,
                      "Unable to rollback delete workflow instances "
                              + "transaction. Message: " + e2.getMessage());
          }
          throw new InstanceRepositoryException(e.getMessage());
      } finally {
          if (statement != null) {
              try {
                  statement.close();
              } catch (SQLException ignore) {
              }

          }

          if (conn != null) {
              try {
                  conn.close();

              } catch (SQLException ignore) {
              }

          }
      }
      return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.workflow.engine.WorkflowInstanceRepository#updateWorkflowInstance(org.apache.oodt.cas.workflow.structs.WorkflowInstance)
     */
    public synchronized void updateWorkflowInstance(WorkflowInstance wInst)
            throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;
        PreparedStatement update = null;
        String taskIdField, workflowIdField;

        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            statement = conn.createStatement();

            // Bound parameters, so quoteFields no longer applies: adding
            // quotes here would store them.
            taskIdField = wInst.getCurrentTaskId();
            workflowIdField = wInst.getWorkflow().getId();

            String updateStatusSql = "UPDATE workflow_instances SET "
                    + "workflow_instance_status=?, current_task_id=?, "
                    + "workflow_id=?, start_date_time=?, end_date_time=?, "
                    + "current_task_start_date_time=?, "
                    + "current_task_end_date_time=?, priority=?, "
                    + "times_blocked=? WHERE workflow_instance_id = ?";

            LOG.log(Level.FINE, "updateStatusSql: Executing: "
                    + updateStatusSql);
            update = conn.prepareStatement(updateStatusSql);
            update.setString(1, wInst.getStatus());
            update.setString(2, taskIdField);
            update.setString(3, workflowIdField);
            update.setString(4, wInst.getStartDateTimeIsoStr());
            update.setString(5, wInst.getEndDateTimeIsoStr());
            update.setString(6, wInst.getCurrentTaskStartDateTimeIsoStr());
            update.setString(7, wInst.getCurrentTaskEndDateTimeIsoStr());
            update.setDouble(8, wInst.getPriority().getValue());
            update.setInt(9, wInst.getTimesBlocked());
            update.setString(10, wInst.getId());
            update.execute();
            conn.commit();

            // now update its metadata
            removeWorkflowInstanceMetadata(wInst.getId());
            addWorkflowInstanceMetadata(wInst);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "Exception updating workflow instance. Message: "
                            + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback updateWorkflowInstanceStatus "
                                + "transaction. Message: " + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {
            if (update != null) {
                try {
                    update.close();
                } catch (SQLException ignore) {
                }
            }

            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.workflow.engine.WorkflowInstanceRepository#removeWorkflowInstance(org.apache.oodt.cas.workflow.structs.WorkflowInstance)
     */
    public synchronized void removeWorkflowInstance(WorkflowInstance wInst)
            throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;

        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            statement = conn.createStatement();

            String deleteSql = "DELETE FROM workflow_instances "
                    + "WHERE workflow_instance_id = " + wInst.getId();

            LOG.log(Level.FINE, "sql: Executing: " + deleteSql);
            statement.execute(deleteSql);
            conn.commit();

            // now remove its metadata
            removeWorkflowInstanceMetadata(wInst.getId());

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "Exception removing workflow instance. Message: "
                            + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback removeWorkflowInstance "
                                + "transaction. Message: " + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.workflow.engine.WorkflowInstanceRepository#getWorkflowInstanceById(java.lang.String)
     */
    public WorkflowInstance getWorkflowInstanceById(String workflowInstId)
            throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;
        ResultSet rs = null;

        WorkflowInstance workflowInst = null;

        try {
            conn = dataSource.getConnection();
            statement = conn.createStatement();

            String getWorkflowSql = "SELECT * from workflow_instances "
                    + "WHERE workflow_instance_id = " + workflowInstId;

            LOG.log(Level.FINE, "getWorkflowInstanceById: Executing: "
                    + getWorkflowSql);
            rs = statement.executeQuery(getWorkflowSql);

            while (rs.next()) {
                workflowInst = DbStructFactory.getWorkflowInstance(rs);
                // add its metadata
                workflowInst
                        .setSharedContext(getWorkflowInstanceMetadata(workflowInst
                                .getId()));
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "Exception getting workflow instance. Message: "
                            + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback getWorkflowInstanceById "
                                + "transaction. Message: " + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {

            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }

            }

            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

        return workflowInst;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.workflow.engine.WorkflowInstanceRepository#getWorkflowInstances()
     */
    public List getWorkflowInstances() throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;
        ResultSet rs = null;
        List workflowInsts = null;

        try {
            conn = dataSource.getConnection();
            statement = conn.createStatement();

            String getWorkflowSql = "SELECT * from workflow_instances "
                    + "ORDER BY workflow_instance_id DESC";

            LOG.log(Level.FINE, "getWorkflowInstances: Executing: "
                    + getWorkflowSql);
            rs = statement.executeQuery(getWorkflowSql);

            workflowInsts = new Vector();
            while (rs.next()) {
                WorkflowInstance workflowInst = DbStructFactory
                        .getWorkflowInstance(rs);
                // add its metadata
                workflowInst
                        .setSharedContext(getWorkflowInstanceMetadata(workflowInst
                                .getId()));
                workflowInsts.add(workflowInst);
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "Exception getting workflow instance. Message: "
                            + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback getWorkflowInstances "
                                + "transaction. Message: " + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {

            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }

            }

            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

        return workflowInsts;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.workflow.engine.WorkflowInstanceRepository#getWorkflowInstancesByStatus(java.lang.String)
     */
    public List getWorkflowInstancesByStatus(String status)
            throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;
        PreparedStatement select = null;
        ResultSet rs = null;

        List workflowInsts = null;

        try {
            conn = dataSource.getConnection();
            statement = conn.createStatement();

            String getWorkflowSql = "SELECT * from workflow_instances "
                    + "WHERE workflow_instance_status = ? "
                    + "ORDER BY workflow_instance_id DESC";

            LOG.log(Level.FINE, "getWorkflowInstancesByStatus: Executing: "
                    + getWorkflowSql);
            select = conn.prepareStatement(getWorkflowSql);
            select.setString(1, status);
            rs = select.executeQuery();

            workflowInsts = new Vector();
            while (rs.next()) {
                WorkflowInstance workflowInst = DbStructFactory
                        .getWorkflowInstance(rs);
                // add its metadata
                workflowInst
                        .setSharedContext(getWorkflowInstanceMetadata(workflowInst
                                .getId()));
                workflowInsts.add(workflowInst);
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "Exception getting workflow instance. Message: "
                            + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback getWorkflowInstancesByStatus "
                                + "transaction. Message: " + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {

            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }

            }

            if (select != null) {
                try {
                    select.close();
                } catch (SQLException ignore) {
                }
            }

            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

        return workflowInsts;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository#getNumWorkflowInstances()
     */
    public int getNumWorkflowInstances() throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;
        ResultSet rs = null;
        int numInsts = -1;

        try {
            conn = dataSource.getConnection();
            statement = conn.createStatement();

            String getWorkflowSql = "SELECT COUNT(workflow_instance_id) AS num_insts from workflow_instances";

            LOG.log(Level.FINE, "getNumWorkflowInstances: Executing: "
                    + getWorkflowSql);
            rs = statement.executeQuery(getWorkflowSql);

            while (rs.next()) {
                numInsts = rs.getInt("num_insts");
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "Exception getting num workflow instances. Message: "
                            + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback getNumWorkflowInstances "
                                + "transaction. Message: " + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {

            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }

            }

            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

        return numInsts;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository#getNumWorkflowInstancesByStatus(java.lang.String)
     */
    public int getNumWorkflowInstancesByStatus(String status)
            throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;
        PreparedStatement metInsert = null;
        PreparedStatement pagedSelect = null;
        PreparedStatement select = null;
        ResultSet rs = null;
        int numInsts = -1;

        try {
            conn = dataSource.getConnection();
            statement = conn.createStatement();

            String getWorkflowSql = "SELECT COUNT(workflow_instance_id) AS num_insts from workflow_instances "
                    + "WHERE workflow_instance_status = ?";

            LOG.log(Level.FINE, "getNumWorkflowInstancesByStatus: Executing: "
                    + getWorkflowSql);
            select = conn.prepareStatement(getWorkflowSql);
            select.setString(1, status);
            rs = select.executeQuery();

            while (rs.next()) {
                numInsts = rs.getInt("num_insts");
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "Exception getting num workflow instances by status. Message: "
                            + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback getNumWorkflowInstancesByStatus "
                                + "transaction. Message: " + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {

            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }

            }

            if (select != null) {
                try {
                    select.close();
                } catch (SQLException ignore) {
                }
            }

            if (pagedSelect != null) {
                try {
                    pagedSelect.close();
                } catch (SQLException ignore) {
                }
            }

            if (metInsert != null) {
                try {
                    metInsert.close();
                } catch (SQLException ignore) {
                }
            }

            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

        return numInsts;
    }

    protected List paginateWorkflows(int pageNum, String status)
            throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;
        PreparedStatement pagedSelect = null;
        ResultSet rs = null;

        List wInstIds = null;
        int numResults;

        if (status == null || (status.equals(""))) {
            numResults = getNumWorkflowInstances();
        } else {
            numResults = getNumWorkflowInstancesByStatus(status);
        }

        try {
            conn = dataSource.getConnection();
            statement = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);

            String getWorkflowSql = "SELECT workflow_instance_id FROM workflow_instances ";
            boolean filterByStatus = status != null && !status.equals("");
            if (filterByStatus) {
                getWorkflowSql += "WHERE workflow_instance_status = ? ";
            }

            getWorkflowSql += "ORDER BY workflow_instance_id DESC ";

            LOG.log(Level.FINE, "workflow instance paged query: executing: "
                    + getWorkflowSql);

            pagedSelect = conn.prepareStatement(getWorkflowSql,
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
            if (filterByStatus) {
                pagedSelect.setString(1, status);
            }
            rs = pagedSelect.executeQuery();
            wInstIds = new Vector();

            int startNum = (pageNum - 1) * pageSize;

            if (startNum > numResults) {
                startNum = 0;
            }

            // This used to call rs.next() to get a "relative cursor", count
            // that row on page 1 and not on later pages, then rs.relative()
            // from wherever it had landed. On page 2 and beyond the cursor
            // ended up ON the page's first row and the loop below then
            // called next() past it, so one instance was skipped at every
            // page boundary: with four instances at a page size of three,
            // the fourth appeared on no page at all and page 2 came back
            // empty while getTotalPages() still counted it.
            //
            // Positioning to the row *before* the page and letting the loop
            // advance onto the first one needs no special case for page 1,
            // where startNum is 0 and the cursor is already before the first
            // row.
            if (startNum > 0) {
                rs.absolute(startNum);
            }

            int numGrabbed = 0;
            while (numGrabbed < pageSize && rs.next()) {
                wInstIds.add(rs.getString("workflow_instance_id"));
                numGrabbed++;
            }

            if (wInstIds.size() == 0) {
                wInstIds = null;
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING, "Exception performing query. Message: "
                    + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback query transaction. Message: "
                                + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {

            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }

            }

            if (pagedSelect != null) {
                try {
                    pagedSelect.close();
                } catch (SQLException ignore) {
                }
            }

            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

        return wInstIds;
    }

    private Metadata getWorkflowInstanceMetadata(String workflowInstId)
            throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;
        ResultSet rs = null;

        Metadata met = new Metadata();

        try {
            conn = dataSource.getConnection();
            statement = conn.createStatement();

            String getWorkflowSql = "SELECT * from workflow_instance_metadata "
                    + "WHERE workflow_instance_id = " + workflowInstId;

            LOG.log(Level.FINE, "Executing: " + getWorkflowSql);
            rs = statement.executeQuery(getWorkflowSql);

            while (rs.next()) {
                met.addMetadata(rs.getString("workflow_met_key"), URLDecoder.decode(rs
                        .getString("workflow_met_val"), "UTF-8"));
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "Exception getting workflow instance metadata. Message: "
                            + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback getWorkflowInstancesMetadata "
                                + "transaction. Message: " + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {

            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }

            }

            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

        return met;
    }

    private synchronized void addWorkflowInstanceMetadata(WorkflowInstance inst)
            throws InstanceRepositoryException {

        if (inst.getSharedContext() != null
                && inst.getSharedContext().getMap().keySet().size() > 0) {
            for (String key : inst.getSharedContext().getMap().keySet()) {
                List vals = inst.getSharedContext().getAllMetadata(key);
                if (vals != null && vals.size() > 0) {
                    for (Object val1 : vals) {
                        String val = (String) val1;
                        if (val != null && !val.equals("")) {
                            addMetadataValue(inst.getId(), key, val);
                        }
                    }
                }

            }
        }

    }

    private synchronized void addMetadataValue(String wInstId, String key,
            String val) throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;
        PreparedStatement metInsert = null;

        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            statement = conn.createStatement();
            String addMetSql = "INSERT INTO workflow_instance_metadata"
                    + " (workflow_instance_id,workflow_met_key,workflow_met_val)"
                    + " VALUES (?, ?, ?)";

            // The value was URL-encoded and the key beside it was not, which
            // is how a shared-context key carrying an apostrophe broke the
            // statement. Both are bound now. The encoding stays because the
            // read path decodes, so dropping it would misread stored rows.
            LOG.log(Level.FINE, "sql: Executing: " + addMetSql);
            metInsert = conn.prepareStatement(addMetSql);
            metInsert.setString(1, wInstId);
            metInsert.setString(2, key);
            metInsert.setString(3, URLEncoder.encode(val, "UTF-8"));
            metInsert.execute();

            conn.commit();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING, "Exception adding metadata [" + key + "=>"
                    + val + "] to workflow inst: [" + wInstId + "]. Message: "
                    + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback addMetadataValue transaction. Message: "
                                + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {

            if (metInsert != null) {
                try {
                    metInsert.close();
                } catch (SQLException ignore) {
                }
            }

            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }

    }

    private synchronized void removeWorkflowInstanceMetadata(
            String workflowInstId) throws InstanceRepositoryException {
        Connection conn = null;
        Statement statement = null;

        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            statement = conn.createStatement();

            String deleteSql = "DELETE FROM workflow_instance_metadata "
                    + "WHERE workflow_instance_id = " + workflowInstId;

            LOG.log(Level.FINE, "sql: Executing: " + deleteSql);
            statement.execute(deleteSql);
            conn.commit();

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "Exception removing workflow instance metadata. Message: "
                            + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException e2) {
                LOG.log(Level.SEVERE,
                        "Unable to rollback removeWorkflowInstanceMetadata "
                                + "transaction. Message: " + e2.getMessage());
            }
            throw new InstanceRepositoryException(e.getMessage());
        } finally {
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignore) {
                }

            }

            if (conn != null) {
                try {
                    conn.close();

                } catch (SQLException ignore) {
                }

            }
        }
    }


    /**
     * Close the database, when it is one this process is running itself.
     *
     * <p>
     * An embedded HSQLDB file database is opened by the first connection and
     * stays open until it is told to shut down. Closing connections is not
     * enough and neither is stopping everything above it: the database keeps
     * a timer thread of its own, which holds the process alive and goes on
     * writing the heartbeat in the lock file. A workflow manager stopped this
     * way left a JVM listening on nothing, holding the instance database for
     * hours, and every later attempt to empty that database was refused with
     * "Database lock acquisition failure" against a lock whose owner had
     * already been asked to stop.
     * </p>
     *
     * <p>
     * Only for a database this process runs. A server somewhere else is not
     * ours to shut down, and the URL is what tells the two apart.
     * </p>
     */
    @Override
    public void release() {
        Connection conn = null;
        Statement statement = null;
        try {
            conn = dataSource.getConnection();
            String url = conn.getMetaData().getURL();
            if (url == null || !url.contains("hsqldb:file:")) {
                return;
            }
            statement = conn.createStatement();
            statement.execute("SHUTDOWN");
            LOG.log(Level.INFO, "Closed the workflow instance database at ["
                + url + "]");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Unable to close the workflow instance "
                + "database: " + e.getMessage());
        } finally {
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignored) {
                }
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }
}
