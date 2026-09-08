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

package org.apache.oodt.cas.resource.jobqueue;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.oodt.cas.metadata.util.PathUtils;
import org.apache.oodt.cas.resource.jobqueue.JobQueue;
import org.apache.oodt.cas.resource.jobqueue.JobQueueFactory;
import org.apache.oodt.cas.resource.jobrepo.JobRepository;
import org.apache.oodt.cas.resource.queuerepo.QueueRepository;
import org.apache.oodt.cas.resource.structs.exceptions.JobQueueException;
import org.apache.oodt.cas.resource.scheduler.QueueManager;
import org.apache.oodt.cas.resource.util.GenericResourceManagerObjectFactory;


/**
 * This factory class reads in properties set in the resource.properties file
 * and read in via the command line and uses those properties to create a
 * {@link FifoMappedJobQueue}.
 * 
 * @author resneck
 *
 */
public class FifoMappedJobQueueFactory implements JobQueueFactory {

	private int stackSize = -1;
	private JobRepository repo;
	
	private static final Logger LOG =
			Logger.getLogger(FifoMappedJobQueueFactory.class.getName());
	
	public FifoMappedJobQueueFactory() {
		try{
			String stackSizeStr = System.getProperty(
					"org.apache.oodt.cas.resource.jobqueue.fifomappedjobqueue.maxstacksize");
	
			if (stackSizeStr != null) {
				stackSize = Integer.parseInt(stackSizeStr);
			}
		    
			// The default was a class that has never existed here:
			// gov.nasa.smap.spdm.resource.jobrepo.SmapMemoryJobRepositoryFactory,
			// left behind with the property name above. A deployment that did
			// not set resource.jobrepo.factory got a ClassNotFoundException
			// rather than a job repository.
			String jobRepoFactoryClassStr = System.getProperty(
					"resource.jobrepo.factory",
					"org.apache.oodt.cas.resource.jobrepo.MemoryJobRepositoryFactory");
			this.repo = GenericResourceManagerObjectFactory.
					getJobRepositoryFromServiceFactory(jobRepoFactoryClassStr);
			if (this.repo == null) {
				throw new IllegalStateException("No job repository from ["
						+ jobRepoFactoryClassStr + "]");
			}
		}catch(Exception e){
			// Not swallowed. Logging and carrying on left repo null, and the
			// queue this factory then handed out threw NullPointerException on
			// the first job instead of naming the configuration that was wrong.
			LOG.log(Level.SEVERE, "An error occurred while creating a " +
					"FifoMappedJobQueue: " + e.getMessage(), e);
			throw new IllegalStateException(
					"Unable to create a FifoMappedJobQueue", e);
		}

	}
	
	/**
	 * @see org.apache.oodt.cas.resource.jobqueue.JobQueueFactory#createQueue()
	 */
	public JobQueue createQueue() {
		FifoMappedJobQueue queue = new FifoMappedJobQueue(stackSize, repo);
		// Seeded from the queue repository, because the queue rejects a name
		// it has not been told about and nothing else ever tells it. Built
		// bare, this factory produced a queue that answered every submission
		// with "An invalid queue name was given", whatever the name -- so it
		// could not have been in use. The repository is the same one the
		// scheduler reads, so the two agree on what the queues are.
		String queueRepoFactory = System.getProperty(
				"org.apache.oodt.cas.resource.queues.repo.factory",
				"org.apache.oodt.cas.resource.queuerepo.XmlQueueRepositoryFactory");
		QueueRepository queueRepository = GenericResourceManagerObjectFactory
				.getQueueRepositoryFromFactory(queueRepoFactory);
		if (queueRepository == null) {
			throw new IllegalStateException("No queue repository from ["
					+ queueRepoFactory + "]");
		}
		QueueManager queues = queueRepository.loadQueues();
		if (queues == null || queues.getQueues().isEmpty()) {
			throw new IllegalStateException("No queues defined by ["
					+ queueRepoFactory + "]; a mapped job queue has nowhere to "
					+ "put a job");
		}
		for (String queueName : queues.getQueues()) {
			try {
				queue.addQueue(queueName);
			} catch (JobQueueException e) {
				// createQueue cannot declare it, and a queue missing one of
				// its names refuses every job bound for that name, which is
				// not a state worth returning.
				throw new IllegalStateException(
						"Unable to add queue [" + queueName + "]", e);
			}
			LOG.log(Level.INFO, "Job queue serving [" + queueName + "]");
		}
		return queue;
	}
	
}