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

package org.apache.oodt.cas.resource.structs;

import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Properties of the status-to-name mapping in {@link JobStatusNames}.
 *
 * <p>Every status the resource manager can put on a job is one of the
 * {@link JobStatus} constants, and the command line prints the result of this
 * mapping. So the domain worth stating properties over is exactly those
 * constants, plus whatever else a caller might pass by mistake.
 */
class JobStatusNamesPropertyTest {

  private static final List<String> ALL_STATUSES =
      Arrays.asList(
          JobStatus.QUEUED,
          JobStatus.EXECUTED,
          JobStatus.SCHEDULED,
          JobStatus.SUCCESS,
          JobStatus.FAILURE,
          JobStatus.KILLED);

  /**
   * Every status the resource manager can set has a name to show a person. A
   * null here is a blank in the report where the job's state should be.
   */
  @HegelTest
  void everyStatusHasAReadableName(TestCase tc) {
    String status = tc.draw(sampledFrom(ALL_STATUSES), "status");

    assertNotNull(JobStatusNames.getReadableJobStatus(status), "no name for " + status);
  }

  /** Two different statuses never share a name, or the report is ambiguous. */
  @HegelTest
  void distinctStatusesGetDistinctNames(TestCase tc) {
    String a = tc.draw(sampledFrom(ALL_STATUSES), "a");
    String b = tc.draw(sampledFrom(ALL_STATUSES), "b");
    tc.assume(!a.equals(b));

    Set<String> names = new HashSet<>();
    names.add(JobStatusNames.getReadableJobStatus(a));
    names.add(JobStatusNames.getReadableJobStatus(b));

    assertEquals(2, names.size(), "two statuses share one name");
  }

  /** The mapping is a pure function of the status. */
  @HegelTest
  void theNameDependsOnlyOnTheStatus(TestCase tc) {
    String status = tc.draw(sampledFrom(ALL_STATUSES), "status");

    assertEquals(
        JobStatusNames.getReadableJobStatus(status),
        JobStatusNames.getReadableJobStatus(new String(status.toCharArray())));
  }

  /**
   * Anything that is not a status has no name, rather than being passed
   * through as if it were one. That is the documented behaviour and the reason
   * a caller can treat null as "unknown".
   */
  @HegelTest
  void anythingElseHasNoName(TestCase tc) {
    String notAStatus = tc.draw(text().maxSize(20), "notAStatus");
    tc.assume(!ALL_STATUSES.contains(notAStatus));

    assertNull(JobStatusNames.getReadableJobStatus(notAStatus));
  }

  /** A missing status is not an error; it simply has no name. */
  @HegelTest
  void aMissingStatusHasNoName(TestCase tc) {
    tc.note("null status");

    assertNull(JobStatusNames.getReadableJobStatus(null));
  }
}
