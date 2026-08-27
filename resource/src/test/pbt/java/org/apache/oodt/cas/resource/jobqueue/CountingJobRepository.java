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

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.oodt.cas.resource.jobrepo.JobRepository;
import org.apache.oodt.cas.resource.structs.JobSpec;
import org.apache.oodt.cas.resource.structs.JobStatus;
import org.apache.oodt.cas.resource.structs.exceptions.JobRepositoryException;

/**
 * An in-memory {@link JobRepository} for the job queue properties, handing out
 * a fresh id to every job it is given.
 *
 * <p>The production {@code MemoryJobRepository} derives its ids from the
 * current time and so cannot tell two jobs added in the same millisecond apart;
 * that is a property of its own (see
 * {@code MemoryJobRepositoryPropertyTest}) and would otherwise mask what the
 * queue properties are actually about.
 */
class CountingJobRepository implements JobRepository {

  private final Map<String, JobSpec> jobs = new LinkedHashMap<>();
  private int nextId = 0;

  @Override
  public String addJob(JobSpec spec) throws JobRepositoryException {
    if (spec.getJob() == null) {
      throw new JobRepositoryException("job is null");
    }
    String id = "job-" + (nextId++);
    spec.getJob().setId(id);
    jobs.put(id, spec);
    return id;
  }

  @Override
  public String getStatus(JobSpec spec) throws JobRepositoryException {
    return jobs.get(spec.getJob().getId()).getJob().getStatus();
  }

  @Override
  public boolean jobFinished(JobSpec spec) throws JobRepositoryException {
    String status = getStatus(spec);
    return JobStatus.SUCCESS.equals(status) || JobStatus.FAILURE.equals(status);
  }

  @Override
  public void removeJob(JobSpec spec) throws JobRepositoryException {
    if (jobs.remove(spec.getJob().getId()) == null) {
      throw new JobRepositoryException("not persisted: " + spec.getJob().getId());
    }
  }

  @Override
  public void updateJob(JobSpec spec) throws JobRepositoryException {
    jobs.put(spec.getJob().getId(), spec);
  }

  @Override
  public JobSpec getJobById(String jobId) throws JobRepositoryException {
    return jobs.get(jobId);
  }

  /** The number of jobs the repository is holding. */
  int size() {
    return jobs.size();
  }
}
