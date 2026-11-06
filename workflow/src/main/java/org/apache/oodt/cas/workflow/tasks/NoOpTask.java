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

package org.apache.oodt.cas.workflow.tasks;

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.WorkflowTaskInstanceException;

/**
 * A task that does nothing, so that something else can.
 *
 * <p>Not an example, which is why it no longer lives in a package called
 * examples. Every workflow repository generates one of these per workflow that
 * has global conditions: the conditions need a task to hang off during
 * evaluation, and this is the task that carries no work of its own.
 * {@code XMLWorkflowRepository}, {@code PackagedWorkflowRepository} and
 * {@code DataSourceWorkflowRepository} each name it, so a deployment with
 * global conditions in its policy runs this class whether or not anyone chose
 * it.</p>
 *
 * <p>Deliberately empty. A no-op that logged, or counted, or checked anything
 * would make a workflow's global-conditions task do work that policy never
 * asked for.</p>
 *
 * @author mattmann
 * @version $Revision$
 */
public class NoOpTask implements WorkflowTaskInstance {

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance#run(org.apache
   * .oodt.cas.metadata.Metadata,
   * org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration)
   */
  @Override
  public void run(Metadata metadata, WorkflowTaskConfiguration config)
      throws WorkflowTaskInstanceException {

  }

}
