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
package org.apache.oodt.cas.crawl.action;

//JDK imports
import java.io.File;
import java.net.URL;

//OODT imports
import org.apache.commons.lang.Validate;
import org.apache.oodt.cas.crawl.structs.exceptions.CrawlerActionException;
import org.apache.oodt.cas.filemgr.metadata.CoreMetKeys;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.metadata.util.PathUtils;
import org.apache.oodt.cas.workflow.system.WorkflowManagerClient;
import org.apache.oodt.cas.workflow.system.rpc.RpcCommunicationFactory;

//Spring imports
import org.springframework.beans.factory.annotation.Required;

/**
 * Updates the Workflow Manager and notifies it that the crawled {@link Product}
 * has been ingested successfully.
 * 
 * @author bfoster (Brian Foster)
 * @author mattmann (Chris Mattmann)
 */
public class WorkflowMgrStatusUpdate extends CrawlerAction implements
      CoreMetKeys {

   private String ingestSuffix;
   private String eventName;
   private String workflowMgrUrl;

   public WorkflowMgrStatusUpdate() {
      ingestSuffix = "Ingest";
   }

   public boolean performAction(File product, Metadata productMetadata)
         throws CrawlerActionException {
      try {
         WorkflowManagerClient wClient = RpcCommunicationFactory.createClient(new URL(this.workflowMgrUrl));
         return wClient.sendEvent(eventNameFor(productMetadata),
               productMetadata);
      } catch (Exception e) {
         throw new CrawlerActionException(
               "Failed to update workflow manager : " + e.getMessage(), e);
      }
   }

   /**
    * The event this ingest should fire.
    *
    * <p>By default the product type with a suffix, so ingesting an
    * <code>EmploymentStringChunk</code> fires
    * <code>EmploymentStringChunkIngest</code>. That convention assumes the
    * workflow repository declares events separately from workflows, which
    * the XML repository does and the packaged repository does not: there a
    * workflow's id <em>is</em> its event, so the derived name matches
    * nothing and the ingest quietly starts no workflow at all.
    *
    * <p>Setting <code>eventName</code> names the event outright, so a
    * crawler can start a packaged workflow by its id. Metadata references
    * in it are replaced, so <code>urn:x:[ProductType]Workflow</code> works
    * as well as a literal name.
    */
   protected String eventNameFor(Metadata productMetadata)
         throws CrawlerActionException {
      if (eventName != null && eventName.trim().length() > 0) {
         try {
            return PathUtils.doDynamicReplacement(eventName, productMetadata);
         } catch (Exception e) {
            throw new CrawlerActionException("Could not read eventName ["
                  + eventName + "]: " + e.getMessage(), e);
         }
      }
      return productMetadata.getMetadata(PRODUCT_TYPE) + ingestSuffix;
   }

   @Override
   public void validate() throws CrawlerActionException {
      super.validate();
      try {
         Validate.notNull(ingestSuffix, "Must specify ingestSuffix");
      } catch (Exception e) {
         throw new CrawlerActionException(e);
      }
   }

   public void setIngestSuffix(String ingestSuffix) {
      this.ingestSuffix = ingestSuffix;
   }

   /**
    * Name the event outright instead of deriving it from the product type.
    * Leave unset for the historic behaviour.
    */
   public void setEventName(String eventName) {
      this.eventName = eventName;
   }

   @Required
   public void setWorkflowMgrUrl(String workflowMgrUrl) {
      this.workflowMgrUrl = workflowMgrUrl;
   }
}
