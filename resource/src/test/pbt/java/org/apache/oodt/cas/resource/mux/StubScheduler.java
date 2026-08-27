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

package org.apache.oodt.cas.resource.mux;

import org.apache.oodt.cas.resource.batchmgr.Batchmgr;
import org.apache.oodt.cas.resource.jobqueue.JobQueue;
import org.apache.oodt.cas.resource.monitor.Monitor;
import org.apache.oodt.cas.resource.scheduler.QueueManager;
import org.apache.oodt.cas.resource.scheduler.Scheduler;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.ResourceNode;

/**
 * A {@link Scheduler} that records the factory properties in force when it was
 * built.
 *
 * <p>{@link XmlBackendRepository} communicates a queue's chosen monitor and
 * batch manager to the scheduler it is about to construct by setting two system
 * properties around the construction. That handover is invisible from the
 * outside — the only witness is the scheduler itself — so this scheduler reads
 * both properties in its constructor and keeps them.
 */
public class StubScheduler implements Scheduler {

  private final String monitorFactory;
  private final String batchmgrFactory;

  public StubScheduler() {
    this.monitorFactory = System.getProperty("resource.monitor.factory");
    this.batchmgrFactory = System.getProperty("resource.batchmgr.factory");
  }

  /** The monitor factory property as it stood while this scheduler was built. */
  public String getObservedMonitorFactory() {
    return monitorFactory;
  }

  /** The batchmgr factory property as it stood while this scheduler was built. */
  public String getObservedBatchmgrFactory() {
    return batchmgrFactory;
  }

  @Override
  public void run() {}

  @Override
  public boolean schedule(JobSpec spec) {
    return false;
  }

  @Override
  public ResourceNode nodeAvailable(JobSpec spec) {
    return null;
  }

  @Override
  public Monitor getMonitor() {
    return null;
  }

  @Override
  public Batchmgr getBatchmgr() {
    return null;
  }

  @Override
  public JobQueue getJobQueue() {
    return null;
  }

  @Override
  public QueueManager getQueueManager() {
    return null;
  }
}
