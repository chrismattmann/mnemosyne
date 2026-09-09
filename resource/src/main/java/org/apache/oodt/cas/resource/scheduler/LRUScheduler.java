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


package org.apache.oodt.cas.resource.scheduler;

//JDKimports
import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports
import org.apache.oodt.cas.resource.jobqueue.JobQueue;
import org.apache.oodt.cas.resource.monitor.Monitor;
import org.apache.oodt.cas.resource.batchmgr.Batchmgr;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.ResourceNode;
import org.apache.oodt.cas.resource.structs.exceptions.JobExecutionException;
import org.apache.oodt.cas.resource.structs.exceptions.MonitorException;
import org.apache.oodt.cas.resource.structs.exceptions.SchedulerException;

/**
 * 
 * @author woollard
 * @author bfoster
 * @version $Revision$
 * 
 * <p>
 * An implementation of a {@link Scheduler} that uses a <a
 * href="http://en.wikipedia.org/wiki/Cache_algorithms">least-recently-used</a>
 * algorithm for scheduling {@link Job}s.
 * </p>
 */
public class LRUScheduler implements Scheduler {

    /* our log stream */
    private static final Logger LOG = Logger.getLogger(LRUScheduler.class
            .getName());
    public static final double DOUBLE = 1000.0;

    private LRUQueueManager queueManager;
    
    /* the monitor we'll use to check the status of the resources */
    private Monitor myMonitor;

    /* the batch mgr we'll use to execute jobs */
    private Batchmgr myBatchmgr;

    /* our job queue */
    private JobQueue myJobQueue;

    /* our wait time between checking the queue */
    private double waitTime = -1;

    public LRUScheduler(Monitor m, Batchmgr b, JobQueue q, LRUQueueManager qm) {

    	queueManager = qm;
        myMonitor = m;
        myBatchmgr = b;
        myJobQueue = q;

        String waitStr = System.getProperty(
                "org.apache.oodt.cas.resource.scheduler.wait.seconds", "20");
        waitTime = Double.parseDouble(waitStr);
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Runnable#run()
     */
    public void run() {
        for (;;) {

            try {
            	long sleepTime = (long)(waitTime * DOUBLE);
                Thread.currentThread().sleep(sleepTime);
            } catch (Exception ignore) {}

            drainQueue();
        }

    }

    /**
     * Place as many queued jobs as there is room for, then go back to sleep.
     *
     * <p>This used to take exactly one job per cycle. With the wait at twenty
     * seconds that is one job every twenty seconds however much capacity is
     * free: twenty chunks across two eight-slot nodes spent about seven
     * minutes being handed out, and a four hundred and fifty eight chunk
     * corpus would spend some two and a half hours in scheduling alone. It
     * grew worse with each node added, because the capacity grew and the tap
     * did not.</p>
     *
     * <p>It also left a fast node idle. Work was handed out evenly by count,
     * so the quicker machine finished its share and then waited for a cycle
     * to offer it more, while the slower one was still working. Filling every
     * free slot as it appears is what keeps it fed.</p>
     *
     * <p>The loop stops as soon as a job cannot be placed, which is the
     * signal that no node has room, and is bounded by the queue size taken at
     * the start. schedule() puts a job it cannot place back on the queue
     * itself, so without that bound an unplaceable job would be pulled and
     * requeued forever.</p>
     */
    // Package-private so a test can drive one pass without the endless loop.
    void drainQueue() {
        int budget;
        try {
            budget = myJobQueue.getQueuedJobs().size();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Unable to size the job queue: Message: "
                    + e.getMessage());
            return;
        }

        while (budget-- > 0 && !myJobQueue.isEmpty()) {
            JobSpec exec;
            try {
                exec = myJobQueue.getNextJob();
                LOG.log(Level.INFO, "Obtained Job: ["
                        + exec.getJob().getId()
                        + "] from Queue: Scheduling for execution");
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                        "Error getting next job from JobQueue: Message: "
                                + e.getMessage());
                return;
            }

            try {
                if (!schedule(exec)) {
                    // No node has room. schedule() has already put it back;
                    // the rest of the queue can wait for the next cycle
                    // rather than being pulled and requeued behind it.
                    return;
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error scheduling job: ["
                        + exec.getJob().getId() + "]: Message: "
                        + e.getMessage());
                // place the job spec back on the queue
                try {
                    myJobQueue.requeueJob(exec);
                } catch (Exception ignore) {
                }
                return;
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see gov.nasa.jpl.oodt.cas.resource.scheduler.Scheduler#schedule(gov.nasa.jpl.oodt.cas.resource.structs.JobSpec)
     */
    public synchronized boolean schedule(JobSpec spec)
            throws SchedulerException {
        String queueName = spec.getJob().getQueueName();
        int load = spec.getJob().getLoadValue();

        ResourceNode node = nodeAvailable(spec);

        if (node != null) {
            try {
                myMonitor.assignLoad(node, load);
                queueManager.usedNode(queueName, node.getNodeId());
                
                // assign via batch system
                LOG.log(Level.INFO, "Assigning job: ["
                        + spec.getJob().getName() + "] to node: ["
                        + node.getNodeId() + "]");
                try {
                    myBatchmgr.executeRemotely(spec, node);
                } catch (JobExecutionException e) {
                    LOG.log(Level.WARNING, "Exception executing job: ["
                            + spec.getJob().getId() + "] to node: ["
                            + node.getIpAddr() + "]: Message: "
                            + e.getMessage());
                    try {
                        // queue the job back up
                        LOG.log(Level.INFO, "Requeueing job: ["
                                + spec.getJob().getId() + "]");
                        myJobQueue.requeueJob(spec);

                        // make sure to decrement the load
                        myMonitor.reduceLoad(node, load);
                    } catch (Exception ignore) {
                    }
                }
            } catch (MonitorException e) {
                LOG.log(Level.WARNING, "Exception assigning load to resource "
                        + "node: [" + node.getNodeId() + "]: load: [" + load
                        + "]: Message: " + e.getMessage());
                throw new SchedulerException(e.getMessage());
            }
        } else {
            // could not find resource, push onto JobQueue
            try {
                myJobQueue.requeueJob(spec);
            } catch (Exception ignore) {
            }
        }
        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see gov.nasa.jpl.oodt.cas.resource.scheduler.Scheduler#getBatchmgr()
     */
    public Batchmgr getBatchmgr() {
        return myBatchmgr;
    }

    /*
     * (non-Javadoc)
     * 
     * @see gov.nasa.jpl.oodt.cas.resource.scheduler.Scheduler#getMonitor()
     */
    public Monitor getMonitor() {
        return myMonitor;
    }

    /*
     * (non-Javadoc)
     * 
     * @see gov.nasa.jpl.oodt.cas.resource.scheduler.Scheduler#getJobQueue()
     */
    public JobQueue getJobQueue() {
        return myJobQueue;
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see gov.nasa.jpl.oodt.cas.resource.scheduler.Scheduler#getQueueManager()
     */
    public QueueManager getQueueManager() {
    	return this.queueManager;
    }

    /*
     * (non-Javadoc)
     * 
     * @see gov.nasa.jpl.oodt.cas.resource.scheduler.Scheduler#nodeAvailable(gov.nasa.jpl.oodt.cas.resource.structs.JobSpec)
     */
    public synchronized ResourceNode nodeAvailable(JobSpec spec)
            throws SchedulerException {
        try {
	    	String queueName = spec.getJob().getQueueName();
	        int load = spec.getJob().getLoadValue();
	
	        for (String nodeId : queueManager.getNodes(queueName)) {
	            int nodeLoad;
	            ResourceNode resNode = null;
	
	            try {
	                resNode = myMonitor.getNodeById(nodeId);
	                nodeLoad = myMonitor.getLoad(resNode);
	            } catch (MonitorException e) {
	                LOG
	                        .log(Level.WARNING, "Exception getting load on "
	                                + "node: [" + (resNode != null ? resNode.getNodeId() : null)
	                                + "]: Message: " + e.getMessage());
	                throw new SchedulerException(e.getMessage());
	            }
	
	            if (load <= nodeLoad) {
	                return resNode;
	            }
	        }
	
	        return null;
        }catch (Exception e) {
        	throw new SchedulerException("Failed to find available node for job spec : " + e.getMessage(), e);
        }
    }

}
