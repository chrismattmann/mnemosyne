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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.JobStatus;
import org.apache.oodt.cas.resource.structs.NameValueJobInput;
import org.apache.oodt.cas.resource.structs.exceptions.JobRepositoryException;

/**
 * Persistence properties of {@link MemoryJobRepository}.
 *
 * <p>The class had no unit tests. It is the store behind the job queue: every
 * job the resource manager accepts is put here and later looked up by the id
 * this class hands back. So the contract a caller depends on is that the id
 * identifies the job — that {@code getJobById(addJob(spec))} is the job that
 * was added, for every job, not just the first.
 */
class MemoryJobRepositoryPropertyTest {

  private static JobSpec spec(String name) {
    Job job = new Job(null, name, "SomeInstance", NameValueJobInput.class.getName(), "high", 1);
    return new JobSpec(new NameValueJobInput(), job);
  }

  /** A single job is stored under the id it was given and reads back whole. */
  @HegelTest
  void aStoredJobReadsBackUnderItsId(TestCase tc) throws Exception {
    MemoryJobRepository repo = new MemoryJobRepository();
    JobSpec spec = spec("theJob");

    String id = repo.addJob(spec);

    assertNotNull(id, "no id was handed back");
    assertEquals(id, spec.getJob().getId(), "the job was not stamped with its id");
    assertEquals(spec, repo.getJobById(id));
  }

  /**
   * Every job submitted keeps its own identity: two jobs never end up sharing
   * an id, and every job added can still be found afterwards.
   *
   * <p>A resource manager accepts submissions as fast as a client sends them.
   * If two of them collide, one job silently replaces the other in the store,
   * and the client that submitted the first is told about the second's status
   * from then on.
   */
  @HegelTest
  void everySubmittedJobKeepsItsOwnIdentity(TestCase tc) throws Exception {
    MemoryJobRepository repo = new MemoryJobRepository();
    int count = tc.draw(integers().min(1).max(20), "count");

    List<String> ids = new ArrayList<>();
    List<JobSpec> specs = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      JobSpec spec = spec("job" + i);
      specs.add(spec);
      ids.add(repo.addJob(spec));
    }

    Set<String> distinct = new HashSet<>(ids);
    assertEquals(count, distinct.size(), "two jobs were given the same id");

    for (int i = 0; i < count; i++) {
      assertEquals(
          specs.get(i), repo.getJobById(ids.get(i)), "job " + i + " was displaced in the store");
    }
  }

  /** Updating a job replaces what is stored under its id. */
  @HegelTest
  void updatingAJobReplacesWhatIsStored(TestCase tc) throws Exception {
    MemoryJobRepository repo = new MemoryJobRepository();
    JobSpec spec = spec("theJob");
    String id = repo.addJob(spec);
    String status =
        tc.draw(
            sampledFrom(
                Arrays.asList(
                    JobStatus.QUEUED,
                    JobStatus.SCHEDULED,
                    JobStatus.EXECUTED,
                    JobStatus.SUCCESS,
                    JobStatus.FAILURE,
                    JobStatus.KILLED)),
            "status");

    spec.getJob().setStatus(status);
    repo.updateJob(spec);

    assertEquals(status, repo.getStatus(spec));
    assertEquals(status, repo.getJobById(id).getJob().getStatus());
  }

  /** A job counts as finished exactly when it succeeded or failed. */
  @HegelTest
  void aJobIsFinishedExactlyWhenItSucceededOrFailed(TestCase tc) throws Exception {
    MemoryJobRepository repo = new MemoryJobRepository();
    JobSpec spec = spec("theJob");
    repo.addJob(spec);
    String status =
        tc.draw(
            sampledFrom(
                Arrays.asList(
                    JobStatus.QUEUED,
                    JobStatus.SCHEDULED,
                    JobStatus.EXECUTED,
                    JobStatus.SUCCESS,
                    JobStatus.FAILURE,
                    JobStatus.KILLED)),
            "status");
    spec.getJob().setStatus(status);
    repo.updateJob(spec);

    boolean terminal = JobStatus.SUCCESS.equals(status) || JobStatus.FAILURE.equals(status);

    assertEquals(terminal, repo.jobFinished(spec));
  }

  /** A removed job is gone, and removing it twice is reported rather than ignored. */
  @HegelTest
  void aRemovedJobIsGone(TestCase tc) throws Exception {
    MemoryJobRepository repo = new MemoryJobRepository();
    JobSpec spec = spec("theJob");
    String id = repo.addJob(spec);

    repo.removeJob(spec);

    assertNull(repo.getJobById(id), "the job is still in the store");
    assertThrows(JobRepositoryException.class, () -> repo.removeJob(spec));
  }

  /**
   * A job with nothing to persist is refused rather than stored empty, and the
   * refusal leaves the jobs already in the store alone.
   */
  @HegelTest
  void aSpecWithNoJobIsRefused(TestCase tc) throws Exception {
    MemoryJobRepository repo = new MemoryJobRepository();
    JobSpec kept = spec("kept");
    String id = repo.addJob(kept);

    assertThrows(JobRepositoryException.class, () -> repo.addJob(new JobSpec()));
    assertEquals(kept, repo.getJobById(id), "a refused submission disturbed the store");
  }
}
