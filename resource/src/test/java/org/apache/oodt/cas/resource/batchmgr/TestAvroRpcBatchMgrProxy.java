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

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URL;

import static org.junit.Assert.*;

/**
 * The Avro batch manager proxy, against a real batch stub.
 *
 * It asked SpecificRequestor for a client of AvroRpcBatchStub -- the server
 * class -- rather than of AvroIntrBatchmgr, the protocol interface Avro
 * generates. getClient builds a java.lang.reflect.Proxy, which requires an
 * interface, so this threw "IllegalArgumentException: ...AvroRpcBatchStub is
 * not an interface" the moment a connection succeeded. Unchecked, and past
 * the IOException catch, so it came out of nodeAlive() and out of
 * AvroRpcBatchMgr.executeRemotely: the Avro batch manager could not dispatch
 * a job at all.
 *
 * There was no test here, which is how that survived alongside the
 * ClassCastException in AvroRpcBatchMgr.jobSuccess.
 */
public class TestAvroRpcBatchMgrProxy {

    private static final int STUB_PORT = 62021;

    private static AvroRpcBatchStub stub;

    @BeforeClass
    public static void startStub() throws Exception {
        stub = new AvroRpcBatchStub(STUB_PORT);
    }

    @AfterClass
    public static void stopStub() {
        stub = null;
    }

    private static ResourceNode nodeAt(int port) throws Exception {
        return new ResourceNode("test-node", new URL("http://localhost:" + port), 8);
    }

    private static JobSpec helloJob() {
        Job job = new Job();
        job.setId("test-job");
        job.setName("Test Job");
        job.setJobInstanceClassName(
            "org.apache.oodt.cas.resource.examples.HelloWorldJob");
        job.setJobInputClassName(NameValueJobInput.class.getCanonicalName());
        job.setLoadValue(1);
        job.setQueueName("quick");
        return new JobSpec(new NameValueJobInput(), job);
    }

    @Test
    public void testALiveNodeAnswers() throws Exception {
        AvroRpcBatchMgrProxy proxy = new AvroRpcBatchMgrProxy(
            helloJob(), nodeAt(STUB_PORT), new AvroRpcBatchMgr());

        assertTrue("the proxy could not reach a running batch stub",
            proxy.nodeAlive());
    }

    /**
     * A node that is not there is not alive. The connection failure used to
     * be logged and ignored, leaving the proxy null for the next line to
     * dereference, so an unreachable node produced a NullPointerException
     * rather than false.
     */
    @Test
    public void testAnUnreachableNodeIsNotAlive() throws Exception {
        AvroRpcBatchMgrProxy proxy = new AvroRpcBatchMgrProxy(
            helloJob(), nodeAt(1), new AvroRpcBatchMgr());

        assertFalse(proxy.nodeAlive());
    }

    /** and asking twice still works, which it would not if the first call
     *  left its transport behind. */
    @Test
    public void testTheProxyCanBeAskedRepeatedly() throws Exception {
        for (int i = 0; i < 5; i++) {
            AvroRpcBatchMgrProxy proxy = new AvroRpcBatchMgrProxy(
                helloJob(), nodeAt(STUB_PORT), new AvroRpcBatchMgr());
            assertTrue("call " + i + " failed", proxy.nodeAlive());
        }
    }
}
