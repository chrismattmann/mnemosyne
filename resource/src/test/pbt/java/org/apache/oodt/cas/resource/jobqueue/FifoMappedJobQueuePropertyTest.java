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

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.JobStatus;
import org.apache.oodt.cas.resource.structs.NameValueJobInput;
import org.apache.oodt.cas.resource.structs.exceptions.JobQueueException;

/**
 * Queue-aware FIFO properties of {@link FifoMappedJobQueue}.
 *
 * <p>The existing unit test drives one fixed scenario. These properties state
 * the contract this class exists to provide — jobs leave a named queue in the
 * order they entered it, queues do not interfere with each other, and the
 * advertised per-queue capacity is enforced — over arbitrary runs of the same
 * operations an operator and the scheduler perform.
 */
class FifoMappedJobQueuePropertyTest {

  private static Generator<String> queueName() {
    return text().minSize(1).maxSize(6).categories("Lu", "Ll");
  }

  private static JobSpec spec(String name, String queue) {
    Job job = new Job(null, name, "SomeInstance", NameValueJobInput.class.getName(), queue, 1);
    return new JobSpec(new NameValueJobInput(), job);
  }

  /** Distinct queue names, as a resource manager's queues.xml defines them. */
  private static List<String> drawQueues(TestCase tc, FifoMappedJobQueue queue) throws Exception {
    int count = tc.draw(integers().min(1).max(3), "queueCount");
    Set<String> names = new LinkedHashSet<>();
    for (int i = 0; i < count; i++) {
      names.add(tc.draw(queueName(), "queue[" + i + "]"));
    }
    for (String name : names) {
      queue.addQueue(name);
    }
    return new ArrayList<>(names);
  }

  /**
   * Jobs leave a queue in the order they entered it, once each. That is the
   * only thing the word FIFO in the class name promises, and the reason an
   * operator can predict which of two submissions runs first.
   */
  @HegelTest
  void jobsLeaveAQueueInTheOrderTheyEnteredIt(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);
    String target = tc.draw(sampledFrom(queues), "target");

    int count = tc.draw(integers().min(0).max(12), "count");
    List<String> submitted = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      // Jobs land on an arbitrary queue; only the target queue's order is
      // asserted, so the other queues stand in for concurrent traffic.
      String queue = tc.draw(sampledFrom(queues), "job[" + i + "].queue");
      if (queue.equals(target)) {
        submitted.add("job" + i);
      }
      jq.addJob(spec("job" + i, queue));
    }

    List<String> dequeued = new ArrayList<>();
    JobSpec next;
    while ((next = jq.getNextJob(target)) != null) {
      dequeued.add(next.getJob().getName());
    }

    assertEquals(submitted, dequeued);
  }

  /** Sizes agree: the total is the sum of the per-queue sizes. */
  @HegelTest
  void sizesAgreeAcrossQueues(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);

    int count = tc.draw(integers().min(0).max(12), "count");
    for (int i = 0; i < count; i++) {
      jq.addJob(spec("job" + i, tc.draw(sampledFrom(queues), "job[" + i + "].queue")));
    }

    int total = 0;
    for (String queue : queues) {
      int size = jq.getSize(queue);
      assertTrue(size >= 0);
      assertEquals(size == 0, jq.isEmpty(queue), "isEmpty disagrees with getSize for " + queue);
      total += size;
    }

    assertEquals(count, total, "jobs were lost between the queues");
    assertEquals(total, jq.getSize());
    assertEquals(total, jq.getQueuedJobs().size());
    assertEquals(total == 0, jq.isEmpty());
  }

  /** Taking a job from one queue leaves every other queue untouched. */
  @HegelTest
  void takingFromOneQueueDoesNotTouchAnother(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);
    tc.assume(queues.size() >= 2);
    String from = tc.draw(sampledFrom(queues), "from");
    String other = tc.draw(sampledFrom(queues), "other");
    tc.assume(!from.equals(other));

    int count = tc.draw(integers().min(1).max(6), "count");
    for (int i = 0; i < count; i++) {
      jq.addJob(spec("a" + i, from));
      jq.addJob(spec("b" + i, other));
    }

    List<String> otherBefore = namesIn(jq.getQueuedJobs(other));
    jq.getNextJob(from);

    assertEquals(otherBefore, namesIn(jq.getQueuedJobs(other)));
    assertEquals(count - 1, jq.getSize(from));
  }

  /** A job's status follows its place: queued when accepted, scheduled when taken. */
  @HegelTest
  void aJobsStatusFollowsItsPlaceInTheQueue(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);
    String queue = tc.draw(sampledFrom(queues), "queue");

    JobSpec job = spec("theJob", queue);
    jq.addJob(job);
    assertEquals(JobStatus.QUEUED, job.getJob().getStatus());

    assertEquals(JobStatus.SCHEDULED, jq.getNextJob(queue).getJob().getStatus());
  }

  /** Promoting a job moves it to the front of its queue and loses nothing. */
  @HegelTest
  void promotingMovesAJobToTheFront(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);
    String queue = tc.draw(sampledFrom(queues), "queue");

    int count = tc.draw(integers().min(1).max(8), "count");
    List<JobSpec> jobs = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      JobSpec job = spec("job" + i, queue);
      jobs.add(job);
      jq.addJob(job);
    }
    int index = tc.draw(integers().min(0).max(count - 1), "promoted");
    JobSpec promoted = jobs.get(index);

    jq.promoteJob(promoted);

    List<String> after = namesIn(jq.getQueuedJobs(queue));
    assertEquals(promoted.getJob().getName(), after.get(0), "the promoted job is not first");
    assertEquals(count, after.size(), "promotion changed the number of jobs");

    List<String> expectedRest = new ArrayList<>();
    for (JobSpec job : jobs) {
      if (job != promoted) {
        expectedRest.add(job.getJob().getName());
      }
    }
    assertEquals(expectedRest, after.subList(1, after.size()), "promotion reordered the rest");
  }

  /** Removing a job takes that job out and no other. */
  @HegelTest
  void removingAJobTakesOutOnlyThatJob(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);
    String queue = tc.draw(sampledFrom(queues), "queue");

    int count = tc.draw(integers().min(1).max(8), "count");
    List<JobSpec> jobs = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      JobSpec job = spec("job" + i, queue);
      jobs.add(job);
      jq.addJob(job);
    }
    JobSpec removed = jobs.get(tc.draw(integers().min(0).max(count - 1), "removed"));

    jq.removeJob(removed);

    List<String> expected = new ArrayList<>();
    for (JobSpec job : jobs) {
      if (job != removed) {
        expected.add(job.getJob().getName());
      }
    }
    assertEquals(expected, namesIn(jq.getQueuedJobs(queue)));
  }

  /** Purging clears every queue but keeps the queues themselves. */
  @HegelTest
  void purgingClearsEveryQueueButKeepsThem(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);
    int count = tc.draw(integers().min(0).max(8), "count");
    for (int i = 0; i < count; i++) {
      jq.addJob(spec("job" + i, tc.draw(sampledFrom(queues), "job[" + i + "].queue")));
    }

    jq.purge();

    assertTrue(jq.isEmpty());
    assertEquals(0, jq.getSize());
    for (String queue : queues) {
      assertTrue(jq.isEmpty(queue));
      assertEquals(0, jq.getSize(queue));
    }
    assertEquals(new LinkedHashSet<>(queues), new LinkedHashSet<>(jq.getQueueNames()));
  }

  /** A queue that does not exist cannot be used, in either direction. */
  @HegelTest
  void anUnknownQueueIsRejected(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);
    String unknown = tc.draw(queueName(), "unknown");
    tc.assume(!queues.contains(unknown));

    assertThrows(JobQueueException.class, () -> jq.addJob(spec("orphan", unknown)));
    assertThrows(JobQueueException.class, () -> jq.getSize(unknown));
    assertThrows(JobQueueException.class, () -> jq.isEmpty(unknown));
    assertThrows(JobQueueException.class, () -> jq.getNextJob(unknown));
    assertThrows(JobQueueException.class, () -> jq.removeQueue(unknown));
    assertThrows(JobQueueException.class, () -> jq.addJob(null));
    assertFalse(jq.getQueueNames().contains(unknown));
  }

  /** Removing a queue removes its jobs from the totals with it. */
  @HegelTest
  void removingAQueueRemovesItsJobs(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);
    tc.assume(queues.size() >= 2);
    String removed = tc.draw(sampledFrom(queues), "removed");

    int count = tc.draw(integers().min(1).max(6), "count");
    for (int i = 0; i < count; i++) {
      jq.addJob(spec("job" + i, removed));
    }
    int totalBefore = jq.getSize();

    jq.removeQueue(removed);

    assertEquals(totalBefore - count, jq.getSize());
    assertFalse(jq.getQueueNames().contains(removed));
    assertThrows(JobQueueException.class, () -> jq.getSize(removed));
  }

  /**
   * A queue that is already at or above its capacity keeps refusing new work.
   * {@link FifoMappedJobQueue#getCapacity()} is documented as "the number of
   * jobs of each queue that can be queued", so once a queue holds that many
   * every further submission has to be refused — not just the one that arrives
   * at exactly that number.
   *
   * <p>The queue is pushed to and past its capacity through the class's own
   * public API: {@code requeueJob}, which is how a job comes back after a
   * failed scheduling attempt and which deliberately does no capacity check.
   */
  @HegelTest
  void addJobKeepsRefusingWhileAQueueIsFull(TestCase tc) throws Exception {
    int capacity = tc.draw(integers().min(1).max(5), "capacity");
    FifoMappedJobQueue jq = new FifoMappedJobQueue(capacity, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);
    String queue = tc.draw(sampledFrom(queues), "queue");

    for (int i = 0; i < capacity; i++) {
      jq.addJob(spec("job" + i, queue));
    }
    int returning = tc.draw(integers().min(0).max(3), "returning");
    for (int i = 0; i < returning; i++) {
      jq.requeueJob(spec("returning" + i, queue));
    }
    assertTrue(jq.getSize(queue) >= jq.getCapacity(), "the queue is not full");

    assertThrows(JobQueueException.class, () -> jq.addJob(spec("overflow", queue)));
    assertFalse(
        namesIn(jq.getQueuedJobs(queue)).contains("overflow"), "a refused job was queued anyway");
  }

  /**
   * Taking a job off and putting it back leaves the queue exactly as it was.
   * That is the scheduler's whole failure path — {@code LRUScheduler} dequeues
   * a job, finds no node with room, and requeues it — so a queue that came
   * back in a different order would let one unplaceable job reshuffle
   * everything behind it.
   *
   * <p>Note that this is deliberately <em>not</em> stated as "a requeued job
   * goes to the back", which is what {@link JobQueue#requeueJob} is documented
   * to do and what {@link JobStack} does. This implementation puts it back at
   * the front, and for the dequeue-then-requeue cycle above that is the
   * order-preserving choice; the disagreement between the two implementations
   * is real but is a documentation question, not a broken promise to this
   * caller.
   */
  @HegelTest
  void dequeueingThenRequeueingLeavesTheQueueUnchanged(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    List<String> queues = drawQueues(tc, jq);
    String queue = tc.draw(sampledFrom(queues), "queue");

    int count = tc.draw(integers().min(1).max(6), "count");
    for (int i = 0; i < count; i++) {
      jq.addJob(spec("job" + i, queue));
    }
    List<String> before = namesIn(jq.getQueuedJobs(queue));

    JobSpec taken = jq.getNextJob(queue);
    jq.requeueJob(taken);

    assertEquals(before, namesIn(jq.getQueuedJobs(queue)));
    assertEquals(JobStatus.QUEUED, taken.getJob().getStatus(), "a requeued job is not queued");
  }

  /**
   * With one queue configured, the queue-agnostic {@code getNextJob()} the
   * {@link JobQueue} interface defines agrees with the queue-aware one. A
   * scheduler written against the plain interface has to see the same FIFO
   * order as one that names the queue.
   */
  @HegelTest
  void theQueueAgnosticDequeueAgreesWithTheQueueAwareOne(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    String queue = tc.draw(queueName(), "queue");
    jq.addQueue(queue);

    int count = tc.draw(integers().min(1).max(8), "count");
    List<String> submitted = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      submitted.add("job" + i);
      jq.addJob(spec("job" + i, queue));
    }

    List<String> dequeued = new ArrayList<>();
    while (!jq.isEmpty()) {
      dequeued.add(jq.getNextJob().getJob().getName());
    }

    assertEquals(submitted, dequeued);
  }

  /**
   * A job flagged as not ready is passed over rather than scheduled, and stays
   * in the queue for later. The flag exists so a job whose inputs are not
   * staged yet does not get sent to a node.
   */
  @HegelTest
  void aJobThatIsNotReadyIsPassedOverButKept(TestCase tc) throws Exception {
    FifoMappedJobQueue jq = new FifoMappedJobQueue(100, new CountingJobRepository());
    String queue = tc.draw(queueName(), "queue");
    jq.addQueue(queue);

    int count = tc.draw(integers().min(1).max(8), "count");
    List<String> waiting = new ArrayList<>();
    List<String> ready = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      JobSpec job = spec("job" + i, queue);
      boolean isReady = tc.draw(booleans(), "job[" + i + "].ready");
      job.getJob().setReady(isReady);
      (isReady ? ready : waiting).add("job" + i);
      jq.addJob(job);
    }

    List<String> taken = new ArrayList<>();
    JobSpec next;
    while ((next = jq.getNextJob(queue)) != null) {
      taken.add(next.getJob().getName());
    }

    assertEquals(ready, taken, "the ready jobs did not come off in order");
    assertEquals(waiting, namesIn(jq.getQueuedJobs(queue)), "an unready job was lost");
  }

  private static List<String> namesIn(List<JobSpec> specs) {
    List<String> names = new ArrayList<>();
    for (JobSpec spec : specs) {
      names.add(spec.getJob().getName());
    }
    return names;
  }
}
