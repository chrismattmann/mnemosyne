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

package org.apache.oodt.cas.resource.jobrepo;

import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.NameValueJobInput;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Job ids were the timestamp alone. isoFormat has millisecond resolution and
 * nothing checked for collisions, so two jobs submitted in the same
 * millisecond got the same id and jobMap.put silently overwrote the first:
 * that job was gone, never to be scheduled, and the id its client held
 * reported the second job's status.
 */
public class TestMemoryJobRepositoryIds {

    private static JobSpec jobNamed(String name) {
        Job job = new Job();
        job.setName(name);
        job.setJobInstanceClassName(
            "org.apache.oodt.cas.resource.examples.HelloWorldJob");
        job.setJobInputClassName(NameValueJobInput.class.getCanonicalName());
        job.setLoadValue(1);
        job.setQueueName("quick");
        return new JobSpec(new NameValueJobInput(), job);
    }

    /**
     * Two consecutive addJob calls reproduce it every time; a submitted batch
     * is the real-world trigger.
     */
    @Test
    public void testTwoJobsInTheSameMillisecondKeepSeparateIds() throws Exception {
        MemoryJobRepository repo = new MemoryJobRepository();

        String first = repo.addJob(jobNamed("first"));
        String second = repo.addJob(jobNamed("second"));

        assertNotEquals("both jobs were given the same id", first, second);
        assertEquals("first", repo.getJobById(first).getJob().getName());
        assertEquals("second", repo.getJobById(second).getJob().getName());
    }

    /** Nothing is lost from a batch. */
    @Test
    public void testABatchOfJobsAllSurvive() throws Exception {
        MemoryJobRepository repo = new MemoryJobRepository();

        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            ids.add(repo.addJob(jobNamed("job-" + i)));
        }

        assertEquals("jobs overwrote each other", 200, ids.size());
        for (String id : ids) {
            assertNotNull(repo.getJobById(id));
        }
    }

    /** The timestamp is still the prefix, so ids still sort chronologically. */
    @Test
    public void testTheIdStillBeginsWithATimestamp() throws Exception {
        String id = new MemoryJobRepository().addJob(jobNamed("a"));

        assertTrue("the id no longer starts with an ISO date: " + id,
                id.matches("^\\d{4}-\\d{2}-\\d{2}T.*"));
    }
}
