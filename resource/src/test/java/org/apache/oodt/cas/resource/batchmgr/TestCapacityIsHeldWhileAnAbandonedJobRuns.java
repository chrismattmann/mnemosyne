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
package org.apache.oodt.cas.resource.batchmgr;

import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.NameValueJobInput;
import org.apache.oodt.cas.resource.structs.ResourceNode;
import org.apache.oodt.cas.resource.system.extern.AvroRpcBatchStub;
import org.apache.oodt.commons.rpc.RequestTimeout;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A node's capacity is not free while it is still running the job.
 *
 * <p>
 * Executing a job is a blocking call that returns when the job is done, so a
 * client timeout on it says nothing about the job. The batch manager treated
 * running out of time as a failure: it recorded the job failed and, in the
 * same finally block, gave the node's capacity back. The node kept running the
 * task, the scheduler placed another on the strength of the freed slot, and
 * every time the bound elapsed again it did so once more.
 * </p>
 *
 * <p>
 * Measured on a two node run: the CPU machine reached thirty concurrent
 * translations against a capacity of eight and was still climbing, while the
 * GPU machine -- fast enough that its jobs never outlasted the bound -- sat at
 * exactly eight. Same code, same capacity, correct only because it was quick.
 * Jobs that finished perfectly were recorded as failures, so instance state
 * and actual output disagreed for the whole run.
 * </p>
 */
public class TestCapacityIsHeldWhileAnAbandonedJobRuns {

    private static final int STUB_PORT = 62031;

    /** Long enough that the call is abandoned well before the job ends. */
    private static final int JOB_SECONDS = 5;
    private static final long GIVE_UP_AFTER_MILLIS = 800L;

    private static AvroRpcBatchStub stub;
    private String previousTimeout;
    private String previousPoll;

    @BeforeClass
    public static void startStub() throws Exception {
        stub = new AvroRpcBatchStub(STUB_PORT);
    }

    @AfterClass
    public static void stopStub() {
        stub = null;
    }

    @Before
    public void tightenTheBounds() {
        previousTimeout = System.getProperty(RequestTimeout.TIMEOUT_PROPERTY);
        previousPoll = System.getProperty(
                AvroRpcBatchMgrProxy.ABANDONED_POLL_PROPERTY);
        System.setProperty(RequestTimeout.TIMEOUT_PROPERTY,
                String.valueOf(GIVE_UP_AFTER_MILLIS));
        System.setProperty(AvroRpcBatchMgrProxy.ABANDONED_POLL_PROPERTY, "200");
    }

    @After
    public void restoreTheBounds() {
        restore(RequestTimeout.TIMEOUT_PROPERTY, previousTimeout);
        restore(AvroRpcBatchMgrProxy.ABANDONED_POLL_PROPERTY, previousPoll);
    }

    private void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** Records when the node's capacity was handed back. */
    private static class RecordingBatchMgr extends AvroRpcBatchMgr {
        private final AtomicLong releasedAt = new AtomicLong(0L);

        @Override
        protected void notifyMonitor(ResourceNode node, JobSpec spec) {
            releasedAt.compareAndSet(0L, System.currentTimeMillis());
        }

        @Override
        protected void jobSuccess(JobSpec spec) {
        }

        @Override
        protected void jobFailure(JobSpec spec) {
        }

        @Override
        protected void jobExecuting(JobSpec spec) {
        }
    }

    /**
     * The bug, at the point it does damage: the capacity must not come back
     * while the node is still working.
     */
    @Test
    public void testTheSlotIsNotFreedUntilTheJobLeavesTheNode() throws Exception {
        RecordingBatchMgr parent = new RecordingBatchMgr();
        AvroRpcBatchMgrProxy proxy = new AvroRpcBatchMgrProxy(
                longJob("held-job", JOB_SECONDS), nodeAt(STUB_PORT), parent);

        long started = System.currentTimeMillis();
        proxy.run();
        long releasedAfter = parent.releasedAt.get() - started;

        assertTrue("capacity was released, but never", parent.releasedAt.get() > 0);
        assertTrue("the slot came back after " + releasedAfter + "ms, while the "
                + "node was still running a " + JOB_SECONDS + "s job. That is "
                + "the freed slot the scheduler fills on top of running work.",
                releasedAfter >= (JOB_SECONDS * 1000L) - 1500L);
    }

    /**
     * And it does come back. Holding it until the node says so is only correct
     * if the node stops saying so.
     */
    @Test
    public void testTheSlotIsFreedOnceTheJobIsDone() throws Exception {
        RecordingBatchMgr parent = new RecordingBatchMgr();
        AvroRpcBatchMgrProxy proxy = new AvroRpcBatchMgrProxy(
                longJob("released-job", 2), nodeAt(STUB_PORT), parent);

        proxy.run();

        assertTrue("the slot was never given back at all",
                parent.releasedAt.get() > 0);
        assertFalse("the node should no longer report a finished job",
                proxy.jobsOnNode().contains("released-job"));
    }

    /**
     * A node that has stopped answering is not running anything for us, and
     * holding capacity for it would leak a slot per lost node.
     */
    @Test
    public void testAnUnreachableNodeDoesNotHoldCapacityForever() throws Exception {
        RecordingBatchMgr parent = new RecordingBatchMgr();
        AvroRpcBatchMgrProxy proxy = new AvroRpcBatchMgrProxy(
                longJob("lost-job", 2), nodeAt(1), parent);

        long started = System.currentTimeMillis();
        proxy.run();

        assertTrue("an unreachable node must not hold a slot",
                parent.releasedAt.get() > 0);
        assertTrue("it should fail fast rather than poll a node that is not there",
                System.currentTimeMillis() - started < 20000L);
    }

    /** Asking a node that is not there says "cannot tell", not "nothing". */
    @Test
    public void testJobsOnAnUnreachableNodeIsUnknownRatherThanEmpty()
            throws Exception {
        AvroRpcBatchMgrProxy proxy = new AvroRpcBatchMgrProxy(
                longJob("whatever", 1), nodeAt(1), new RecordingBatchMgr());

        AvroRpcBatchMgrProxy.NodeJobs answer = proxy.jobsOnNode();
        assertFalse("a node that cannot be asked has told us nothing, and an "
                + "empty answer would read as 'the job is finished' and "
                + "release a slot on a node that may still be working",
                answer.known());
        assertFalse("nor does it contain anything", answer.contains("whatever"));
    }

    /** A node that answered, and is running nothing, is a different thing. */
    @Test
    public void testANodeRunningNothingIsKnownAndEmpty() throws Exception {
        AvroRpcBatchMgrProxy proxy = new AvroRpcBatchMgrProxy(
                longJob("not-submitted", 1), nodeAt(STUB_PORT),
                new RecordingBatchMgr());

        AvroRpcBatchMgrProxy.NodeJobs answer = proxy.jobsOnNode();
        assertTrue("the stub answered, so this is known", answer.known());
        assertFalse(answer.contains("not-submitted"));
    }

    /**
     * The backstop. A node that keeps reporting a job forever must not pin a
     * slot and a dispatch thread for the life of the process, however sure it
     * sounds -- but reaching this means something is wrong on that node, so it
     * is logged at SEVERE rather than passed over.
     */
    @Test
    public void testASlotIsNotHeldForeverByANodeThatKeepsReportingAJob()
            throws Exception {
        // The stub never forgets a job it is still running, and this job runs
        // far longer than the hold allowed, so the backstop is what ends it.
        System.setProperty(AvroRpcBatchMgrProxy.ABANDONED_MAX_HOLD_PROPERTY,
                "600");
        try {
            RecordingBatchMgr parent = new RecordingBatchMgr();
            AvroRpcBatchMgrProxy proxy = new AvroRpcBatchMgrProxy(
                    longJob("pinned-job", 30), nodeAt(STUB_PORT), parent);

            long started = System.currentTimeMillis();
            proxy.run();
            long releasedAfter = System.currentTimeMillis() - started;

            assertTrue("the slot was never released", parent.releasedAt.get() > 0);
            assertTrue("the backstop should have ended the hold well before the "
                    + "30s job did, took " + releasedAfter + "ms",
                    releasedAfter < 20000L);
        } finally {
            System.clearProperty(
                    AvroRpcBatchMgrProxy.ABANDONED_MAX_HOLD_PROPERTY);
        }
    }

    /** And the backstop is far longer than any task by default. */
    @Test
    public void testTheBackstopIsNotATaskDeadline() throws Exception {
        System.clearProperty(AvroRpcBatchMgrProxy.ABANDONED_MAX_HOLD_PROPERTY);
        assertTrue("a default short enough to interrupt real work would make "
                + "this a task deadline rather than a backstop",
                AvroRpcBatchMgrProxy.abandonedMaxHoldMillis()
                        >= 4L * 60L * 60L * 1000L);
    }

    // ----------------------------------------------------------- helpers ---

    private static ResourceNode nodeAt(int port) throws Exception {
        return new ResourceNode("test-node", new URL("http://localhost:" + port), 8);
    }

    private static JobSpec longJob(String id, int seconds) {
        Job job = new Job();
        job.setId(id);
        job.setName("Long Job");
        job.setJobInstanceClassName(
                "org.apache.oodt.cas.resource.examples.LongJob");
        job.setJobInputClassName(NameValueJobInput.class.getCanonicalName());
        job.setLoadValue(1);
        job.setQueueName("quick");
        NameValueJobInput in = new NameValueJobInput();
        in.setNameValuePair("wait", String.valueOf(seconds));
        return new JobSpec(in, job);
    }
}
