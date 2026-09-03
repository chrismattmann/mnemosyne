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
package org.apache.oodt.cas.workflow.cli.action;

import org.apache.oodt.cas.cli.exception.CmdLineActionException;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;

/**
 * Asks the workflow manager to remove every instance it holds.
 *
 * <p>
 * The manager owns whichever repository was configured, so this works the
 * same whether a deployment keeps its instances in a database, a Lucene index
 * or memory -- and without stopping the manager to reach them, which is what
 * a caller had to do before there was a way to ask.
 * </p>
 *
 * <p>
 * The manager refuses unless force is set, and refuses while any instance is
 * executing.
 * </p>
 */
public class ClearWorkflowInstancesCliAction extends WorkflowCliAction {

   /** Set by the --force option's handler. */
   static final String FORCE_PROPERTY = "org.apache.oodt.cas.workflow.cli.force";

   private boolean force;

   public void setForce(boolean force) {
      this.force = force;
   }

   private boolean forced() {
      return this.force
            || System.getProperty(FORCE_PROPERTY) != null;
   }

   @Override
   public void execute(ActionMessagePrinter printer)
         throws CmdLineActionException {
      try (WorkflowManagerClient client = getClient()) {
         boolean cleared = client.clearWorkflowInstances(forced());
         printer.println(cleared
               ? "Cleared every workflow instance"
               : "The workflow manager reported nothing was cleared");
      } catch (Exception e) {
         throw new CmdLineActionException(
               "Unable to clear workflow instances: " + e.getMessage(), e);
      }
   }
}
