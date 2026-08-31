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

//JDK imports
import java.util.List;

//OODT imports
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;
import org.apache.oodt.cas.workflow.util.Pagination;

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * Describe your class here
 * </p>.
 */
public interface WorkflowInstanceRepository extends Pagination {

    String X_POINT_ID = WorkflowInstanceRepository.class
            .getName();

    /**
     * Persists the specified {@link WorkflowInstance} to the instance
     * repository.
     * 
     * @param wInst
     *            The workflow instance to persist.
     * @throws InstanceRepositoryException
     *             If any error occurs.
     */
    void addWorkflowInstance(WorkflowInstance wInst)
            throws InstanceRepositoryException;

    /**
     * Updates and persists the specified {@link WorkflowInstance} to the
     * instance repository.
     * 
     * @param wInst
     *            The workflow instance to update and persist.
     * @throws InstanceRepositoryException
     *             If any error occurs.
     */
    void updateWorkflowInstance(WorkflowInstance wInst)
            throws InstanceRepositoryException;

    /**
     * Removes the specified {@link WorkflowInstance} from the instance
     * repository.
     * 
     * @param wInst
     *            The workflow instance to remove.
     * @throws InstanceRepositoryException
     *             If any error occurs.
     */
    void removeWorkflowInstance(WorkflowInstance wInst)
            throws InstanceRepositoryException;

    /**
     * <p>
     * Returns the {@link WorkflowInstance}s with the specified
     * <code>workflowInstId</code>.
     * </p>
     * 
     * @param workflowInstId
     *            The ID of the {@link WorkflowInstance} to return.
     * @return The specified {@link WorkflowInstance}.
     * @throws InstanceRepositoryException
     *             If any error occurs.
     */
    WorkflowInstance getWorkflowInstanceById(String workflowInstId)
            throws InstanceRepositoryException;

    /**
     * @return A {@link List} of {@link WorkflowInstance}s that this
     *         {@link WorkflowEngine} is managing.
     * @throws InstanceRepositoryException
     *             If any error occurs.
     */
    List getWorkflowInstances() throws InstanceRepositoryException;

    /**
     * <p>
     * Returns a {@link List} of {@link WorkflowInstance}s, with the specified
     * <code>status</code> String.
     * </p>
     * 
     * @param status
     *            A string representation of the status of the
     *            {@link WorkflowInstance}.
     * @return A {@link List} of {@link WorkflowInstance}s, with the specified
     *         <code>status</code> String.
     * @throws InstanceRepositoryException
     *             If there is any error that occurs.
     */
    List getWorkflowInstancesByStatus(String status)
            throws InstanceRepositoryException;

    /**
     * Gets the {@link WorkflowInstance}s whose current state belongs to the
     * given lifecycle <code>category</code>.
     *
     * Categories group states by what the engine can do with them, so this is
     * the question a scheduler actually asks: it wants the instances that are
     * still workable, not every instance ever recorded. Filtering after
     * retrieval means a repository full of finished work is paged through and
     * discarded on every pass, which is what this exists to avoid.
     *
     * Implementations backed by a queryable store should override this and
     * push the filter down. The default is correct but reads everything.
     *
     * @param category the lifecycle category name, for example "done"
     * @return the matching {@link WorkflowInstance}s, never null
     * @throws InstanceRepositoryException If there is any error that occurs.
     */
    default List getWorkflowInstancesByCategory(String category)
            throws InstanceRepositoryException {
        return filterByCategory(category, true);
    }

    /**
     * Gets the {@link WorkflowInstance}s whose current state does NOT belong to
     * the given lifecycle <code>category</code>.
     *
     * The complement of {@link #getWorkflowInstancesByCategory(String)}, and
     * the direction a scheduler needs: "everything not done".
     *
     * @param category the lifecycle category name to exclude, for example "done"
     * @return the matching {@link WorkflowInstance}s, never null
     * @throws InstanceRepositoryException If there is any error that occurs.
     */
    default List getWorkflowInstancesNotByCategory(String category)
            throws InstanceRepositoryException {
        return filterByCategory(category, false);
    }

    /**
     * Correct-but-unoptimised fallback shared by the two category queries.
     * An instance with no state cannot be in a category, so it is only
     * returned by the excluding form.
     */
    default List filterByCategory(String category, boolean matching)
            throws InstanceRepositoryException {
        List<WorkflowInstance> results = new java.util.Vector<WorkflowInstance>();
        List all = getWorkflowInstances();
        if (all == null) {
            return results;
        }
        for (Object each : all) {
            WorkflowInstance inst = (WorkflowInstance) each;
            boolean inCategory = inst.getState() != null
                    && inst.getState().getCategory() != null
                    && category.equals(inst.getState().getCategory().getName());
            if (inCategory == matching) {
                results.add(inst);
            }
        }
        return results;
    }

    /**
     * Gets the number of {@link WorkflowInstances} with any <code>status</code>
     * being managed by this WorkflowInstanceRepository.
     * 
     * @return The number of {@link WorkflowInstances} associated with any
     *         <code>status</code> being managed by this
     *         WorkflowInstanceRepository.
     */
    int getNumWorkflowInstances() throws InstanceRepositoryException;

    /**
     * Gets the number of {@link WorkflowInstances} with the given
     * <code>status</code> being managed by this WorkflowInstanceRepository.
     * 
     * @param status
     *            The status to obtain the number of {@link WorkflowInstance}s
     *            for.
     * @return The number of {@link WorkflowInstance}s with the given
     *         <code>status</code>.
     * @throws InstanceRepositoryException If there is any error that occurs.
     */
    int getNumWorkflowInstancesByStatus(String status) throws InstanceRepositoryException;
    
    /**
     * Clears the instance repository of all workflows. 
     * @return False if there was any error (logged), and True otherwise.
     * @throws InstanceRepositoryException If there was some IO or other error deleting
     * workflow instances that was unrecoverable from.
     */
    public boolean clearWorkflowInstances() throws InstanceRepositoryException;


    /**
     * Let go of whatever this repository is holding.
     *
     * <p>
     * Most repositories hold nothing between calls and have nothing to do
     * here, which is why this does nothing unless a repository says
     * otherwise. One that keeps a store open for the life of the process has
     * to be told when that life is over: an embedded database goes on running
     * its own threads and holding its own file lock long after the manager
     * that opened it has stopped serving, and nothing else will ever close
     * it.
     * </p>
     */
    default void release() {
    }
}
