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

import junit.framework.TestCase;
import org.apache.oodt.cas.resource.jobrepo.JobRepository;
import org.apache.oodt.cas.resource.jobrepo.MemoryJobRepository;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.exceptions.JobQueueException;
import org.apache.oodt.cas.resource.structs.exceptions.JobRepositoryException;

/**
 * What the queues do at their limit.
 *
 * <p>Both queues carry a maximum, and both got it wrong in the same two
 * ways. They tested the size for equality, so a queue one job over its
 * limit never tripped the check again and the cap stopped existing rather
 * than holding -- and requeue is a path that puts a queue over, because a
 * returning job is added without asking.</p>
 *
 * <p>JobStack also wrote the job to the repository before testing capacity,
 * so a rejected job stayed in the repository: counted by every report,
 * belonging to no queue, and run by nothing.</p>
 */
public class TestQueueCapacity extends TestCase {

  /** A repository that only counts, so "was it written" is answerable. */
  private static final class CountingRepository extends MemoryJobRepository {
    private int added;

    @Override
    public String addJob(JobSpec spec) throws JobRepositoryException {
      added++;
      return super.addJob(spec);
    }
  }

  private JobSpec spec(String id, String queueName) {
    Job job = new Job();
    job.setId(id);
    job.setName(id);
    job.setQueueName(queueName);
    return new JobSpec(null, job);
  }

  // ---- JobStack --------------------------------------------------------

  public void testJobStackRefusesBeyondItsMaximum() throws Exception {
    JobStack stack = new JobStack(2, new MemoryJobRepository());
    stack.addJob(spec("a", "q"));
    stack.addJob(spec("b", "q"));
    try {
      stack.addJob(spec("c", "q"));
      fail("a full stack must refuse the next job");
    } catch (JobQueueException expected) {
      assertTrue(expected.getMessage().contains("max queue size"));
    }
  }

  public void testARefusedJobIsNeverWrittenToTheRepository() throws Exception {
    CountingRepository repo = new CountingRepository();
    JobStack stack = new JobStack(1, repo);
    stack.addJob(spec("kept", "q"));
    try {
      stack.addJob(spec("refused", "q"));
      fail("expected the second job to be refused");
    } catch (JobQueueException expected) {
      // Capacity was tested after the write, so the job outlived its own
      // rejection: in the repository, in no queue, run by nothing.
    }
    assertEquals("only the accepted job may reach the repository",
        1, repo.added);
  }

  public void testTheCapStillHoldsAfterARequeuePutsItOver() throws Exception {
    // requeue admits a returning job at capacity by design. What must not
    // happen is the size check ceasing to hold afterwards.
    JobStack stack = new JobStack(1, new MemoryJobRepository());
    stack.addJob(spec("a", "q"));
    stack.requeueJob(spec("b", "q"));
    try {
      stack.addJob(spec("c", "q"));
      fail("over its limit, a stack must still refuse new jobs");
    } catch (JobQueueException expected) {
      // On == this passed silently and the queue grew without bound.
    }
  }

  // ---- FifoMappedJobQueue ---------------------------------------------

  private FifoMappedJobQueue fifo(int max) throws JobQueueException {
    FifoMappedJobQueue q = new FifoMappedJobQueue(max, new MemoryJobRepository());
    q.addQueue("translate");
    q.addQueue("managers");
    return q;
  }

  public void testFifoRefusesBeyondItsMaximum() throws Exception {
    FifoMappedJobQueue q = fifo(2);
    q.addJob(spec("a", "translate"));
    q.addJob(spec("b", "translate"));
    try {
      q.addJob(spec("c", "translate"));
      fail("a full queue must refuse the next job");
    } catch (JobQueueException expected) {
      assertTrue(expected.getMessage().contains("full"));
    }
  }

  public void testEachQueueCarriesItsOwnCapacity() throws Exception {
    // The reason to prefer this queue: a flood of one stage must not be able
    // to starve another. Under a single global stack, enough translate work
    // fills the only queue there is and the join that finishes the run can
    // never be submitted.
    FifoMappedJobQueue q = fifo(1);
    q.addJob(spec("t", "translate"));
    q.addJob(spec("m", "managers"));
    assertEquals(2, q.getQueuedJobs().size());
  }

  public void testFifoCapStillHoldsAfterARequeuePutsItOver() throws Exception {
    FifoMappedJobQueue q = fifo(1);
    q.addJob(spec("a", "translate"));
    q.requeueJob(spec("b", "translate"));
    try {
      q.addJob(spec("c", "translate"));
      fail("over its limit, a queue must still refuse new jobs");
    } catch (JobQueueException expected) {
      // On == this passed silently.
    }
  }
}
