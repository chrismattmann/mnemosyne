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

import junit.framework.TestCase;
import org.apache.oodt.cas.resource.jobqueue.JobQueue;
import org.apache.oodt.cas.resource.jobqueue.JobStack;
import org.apache.oodt.cas.resource.jobrepo.MemoryJobRepository;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.exceptions.SchedulerException;

import java.util.ArrayList;
import java.util.List;

/**
 * How much the scheduler hands out per cycle.
 *
 * <p>It took exactly one job, whatever capacity was free. With the wait at
 * twenty seconds that is one job every twenty seconds: twenty chunks across
 * two eight-slot nodes spent about seven minutes being handed out, and a four
 * hundred and fifty eight chunk corpus would spend some two and a half hours
 * in scheduling alone. It also left the faster of two nodes idle, because
 * work was split evenly by count and the quick machine finished its share
 * then waited for a cycle to offer it more.</p>
 */
public class TestSchedulerDrainsQueue extends TestCase {

  /** Records what it was asked to place, and can start refusing. */
  private static class CountingScheduler extends LRUScheduler {
    private final List<String> placed = new ArrayList<String>();
    private int acceptUpTo = Integer.MAX_VALUE;

    CountingScheduler(JobQueue q) {
      super(null, null, q, null);
    }

    @Override
    public synchronized boolean schedule(JobSpec spec)
        throws SchedulerException {
      if (placed.size() >= acceptUpTo) {
        return false;
      }
      placed.add(spec.getJob().getId());
      return true;
    }
  }

  private JobQueue queueOf(int howMany) throws Exception {
    JobQueue q = new JobStack(1000, new MemoryJobRepository());
    for (int i = 0; i < howMany; i++) {
      Job job = new Job();
      job.setId("job-" + i);
      job.setName("job-" + i);
      job.setQueueName("translate");
      q.addJob(new JobSpec(null, job));
    }
    return q;
  }

  public void testOnePassPlacesEveryQueuedJob() throws Exception {
    JobQueue q = queueOf(20);
    CountingScheduler s = new CountingScheduler(q);

    s.drainQueue();

    assertEquals("a single cycle used to hand out one job and then sleep",
        20, s.placed.size());
    assertTrue("the queue should be empty afterwards", q.isEmpty());
  }

  public void testDrainStopsWhenNothingCanBePlaced() throws Exception {
    // No node has room. The rest of the queue waits for the next cycle
    // rather than being pulled and requeued behind an unplaceable job.
    JobQueue q = queueOf(10);
    CountingScheduler s = new CountingScheduler(q);
    s.acceptUpTo = 4;

    s.drainQueue();

    assertEquals(4, s.placed.size());
    assertFalse("jobs that could not be placed must still be queued",
        q.isEmpty());
  }

  public void testAnEmptyQueueIsNotAnError() throws Exception {
    CountingScheduler s = new CountingScheduler(queueOf(0));
    s.drainQueue();
    assertEquals(0, s.placed.size());
  }

  public void testDrainTerminatesWhenNothingCanEverBePlaced()
      throws Exception {
    // schedule() puts a job it cannot place back on the queue itself, so
    // without a bound this would pull and requeue the same job forever.
    JobQueue q = queueOf(5);
    CountingScheduler s = new CountingScheduler(q);
    s.acceptUpTo = 0;

    s.drainQueue();

    assertEquals(0, s.placed.size());
    assertFalse(q.isEmpty());
  }
}
