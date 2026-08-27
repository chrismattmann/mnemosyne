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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.JobStatus;
import org.apache.oodt.cas.resource.structs.NameValueJobInput;
import org.apache.oodt.cas.resource.structs.exceptions.JobQueueException;

/**
 * Queueing properties of {@link JobStack}.
 *
 * <p>The existing unit test queues three jobs and pops them. These properties
 * state the same contract over any run of queue operations, because the thing
 * an operator relies on is not that three jobs come back but that <em>every</em>
 * job submitted comes back, once, in the order it was submitted, and that the
 * queue's advertised capacity is a limit rather than a suggestion.
 */
class JobStackPropertyTest {

  private static JobSpec spec(String name) {
    Job job = new Job(null, name, "SomeInstance", NameValueJobInput.class.getName(), "high", 1);
    return new JobSpec(new NameValueJobInput(), job);
  }

  /** The names of the jobs currently sitting in the queue, in queue order. */
  private static List<String> namesIn(JobStack queue) {
    List<String> names = new ArrayList<>();
    for (Object o : queue.getQueuedJobs()) {
      names.add(((JobSpec) o).getJob().getName());
    }
    return names;
  }

  /**
   * Every job added below the queue's capacity comes back out, once each, in
   * the order it went in. This is the whole promise of a job queue.
   */
  @HegelTest
  void jobsComeBackOutInTheOrderTheyWentIn(TestCase tc) throws Exception {
    int capacity = tc.draw(integers().min(1).max(20), "capacity");
    int count = tc.draw(integers().min(0).max(capacity), "count");
    JobStack queue = new JobStack(capacity, new CountingJobRepository());

    List<String> submitted = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String name = "job" + i;
      submitted.add(name);
      queue.addJob(spec(name));
    }

    assertEquals(count, queue.getSize(), "queue size does not match what was added");

    List<String> dequeued = new ArrayList<>();
    while (!queue.isEmpty()) {
      dequeued.add(queue.getNextJob().getJob().getName());
    }

    assertEquals(submitted, dequeued);
  }

  /** The queued job list is a faithful view of the queue at any point. */
  @HegelTest
  void theQueuedJobListMatchesTheQueue(TestCase tc) throws Exception {
    int capacity = tc.draw(integers().min(2).max(20), "capacity");
    int count = tc.draw(integers().min(0).max(capacity), "count");
    int taken = tc.draw(integers().min(0).max(count), "taken");
    JobStack queue = new JobStack(capacity, new CountingJobRepository());

    List<String> remaining = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      remaining.add("job" + i);
      queue.addJob(spec("job" + i));
    }
    for (int i = 0; i < taken; i++) {
      queue.getNextJob();
      remaining.remove(0);
    }

    assertEquals(remaining, namesIn(queue));
    assertEquals(remaining.size(), queue.getSize());
    assertEquals(remaining.isEmpty(), queue.isEmpty());
  }

  /**
   * A job is marked queued when it is accepted and scheduled when it is taken
   * off. An operator asking after a job is shown this status, and the batch
   * manager decides whether the job is still pending from it.
   */
  @HegelTest
  void aJobsStatusFollowsItsPlaceInTheQueue(TestCase tc) throws Exception {
    int capacity = tc.draw(integers().min(2).max(20), "capacity");
    JobStack queue = new JobStack(capacity, new CountingJobRepository());
    JobSpec job = spec(tc.draw(text().minSize(1).maxSize(8), "name"));

    queue.addJob(job);
    assertEquals(JobStatus.QUEUED, job.getJob().getStatus(), "an accepted job is not queued");

    JobSpec taken = queue.getNextJob();
    assertEquals(JobStatus.SCHEDULED, taken.getJob().getStatus(), "a dequeued job is not scheduled");
  }

  /** Purging empties the queue whatever state it was in. */
  @HegelTest
  void purgingEmptiesTheQueue(TestCase tc) throws Exception {
    int capacity = tc.draw(integers().min(1).max(20), "capacity");
    int count = tc.draw(integers().min(0).max(capacity), "count");
    JobStack queue = new JobStack(capacity, new CountingJobRepository());
    for (int i = 0; i < count; i++) {
      queue.addJob(spec("job" + i));
    }

    queue.purge();

    assertTrue(queue.isEmpty());
    assertEquals(0, queue.getSize());
    assertEquals(List.of(), namesIn(queue));
  }

  /**
   * A queue that is already at or above its capacity keeps refusing new work.
   * {@link JobQueue#getCapacity()} is documented as the "max number of jobs
   * allowed in queue at any given time", so once the queue is at that number
   * every further submission has to be refused — not just the submission that
   * arrives at exactly that number.
   *
   * <p>The queue is pushed to and past its capacity through the class's own
   * public API: {@code requeueJob}, which is how a job comes back after a
   * failed scheduling attempt and which deliberately does no capacity check.
   */
  @HegelTest
  void addJobKeepsRefusingWhileTheQueueIsFull(TestCase tc) throws Exception {
    int capacity = tc.draw(integers().min(1).max(6), "capacity");
    JobStack queue = new JobStack(capacity, new CountingJobRepository());
    for (int i = 0; i < capacity; i++) {
      queue.addJob(spec("job" + i));
    }
    int returning = tc.draw(integers().min(0).max(3), "returning");
    for (int i = 0; i < returning; i++) {
      queue.requeueJob(spec("returning" + i));
    }
    assertTrue(queue.getSize() >= queue.getCapacity(), "the queue is not full");

    assertThrows(JobQueueException.class, () -> queue.addJob(spec("overflow")));
    assertTrue(!namesIn(queue).contains("overflow"), "a refused job was queued anyway");
  }

  /** A full queue refuses new work rather than silently dropping it. */
  @HegelTest
  void aFullQueueRefusesNewJobs(TestCase tc) throws Exception {
    int capacity = tc.draw(integers().min(1).max(10), "capacity");
    JobStack queue = new JobStack(capacity, new CountingJobRepository());
    for (int i = 0; i < capacity; i++) {
      queue.addJob(spec("job" + i));
    }
    assertEquals(capacity, queue.getSize(), "the queue did not fill to its capacity");

    JobSpec rejected = spec("overflow");
    assertThrows(JobQueueException.class, () -> queue.addJob(rejected));
    assertTrue(!namesIn(queue).contains("overflow"), "a refused job was queued anyway");
  }

  /**
   * A requeued job goes behind the jobs already waiting, which is what
   * {@link JobQueue#requeueJob} promises: "Re-adds a JobSpec to the back of
   * the queue". A job the scheduler could not place therefore lets the jobs
   * behind it have a turn rather than being retried immediately.
   */
  @HegelTest
  void aRequeuedJobGoesToTheBack(TestCase tc) throws Exception {
    JobStack queue = new JobStack(100, new CountingJobRepository());
    int count = tc.draw(integers().min(1).max(8), "count");
    for (int i = 0; i < count; i++) {
      queue.addJob(spec("job" + i));
    }

    JobSpec taken = queue.getNextJob();
    queue.requeueJob(taken);

    List<String> names = namesIn(queue);
    assertEquals(count, names.size(), "requeueing changed the number of jobs");
    assertEquals("job0", names.get(names.size() - 1), "the requeued job is not at the back");
    assertEquals(JobStatus.QUEUED, taken.getJob().getStatus(), "a requeued job is not queued");
  }

  /** The repository the queue was built on is the one it reports. */
  @HegelTest
  void theQueueReportsItsRepository(TestCase tc) {
    CountingJobRepository repo = new CountingJobRepository();
    JobStack queue = new JobStack(tc.draw(integers().min(1).max(10), "capacity"), repo);

    assertEquals(repo, queue.getJobRepository());
  }
}
