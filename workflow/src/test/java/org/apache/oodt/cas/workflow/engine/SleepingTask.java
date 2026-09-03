/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements.  See the NOTICE.txt file distributed with this work for
 * additional information regarding copyright ownership.  The ASF licenses this
 * file to you under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.oodt.cas.workflow.engine;

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.WorkflowTaskInstanceException;

//JDK imports
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Ignore;

/**
 * A task that runs long enough to be stopped while it is running.
 *
 * <p>
 * Stopping is only observable against work that is still going: a task that
 * has already finished cannot be told apart from one that was stopped. This
 * announces that it has started and then waits, so a test can stop it at a
 * moment when there is genuinely something to stop.
 * </p>
 */
@Ignore
public class SleepingTask implements WorkflowTaskInstance {

  /** Counts down as soon as the task is actually executing. */
  public static volatile CountDownLatch started = new CountDownLatch(1);

  /** Whether the sleep was cut short by an interrupt. */
  public static final AtomicBoolean interrupted = new AtomicBoolean(false);

  /** Whether the task was left to run all the way to the end. */
  public static final AtomicBoolean ranToCompletion = new AtomicBoolean(false);

  public static void reset() {
    started = new CountDownLatch(1);
    interrupted.set(false);
    ranToCompletion.set(false);
  }

  public void run(Metadata metadata, WorkflowTaskConfiguration config)
      throws WorkflowTaskInstanceException {
    started.countDown();
    try {
      // Long enough that nothing finishes on its own inside a test.
      TimeUnit.SECONDS.sleep(30);
      ranToCompletion.set(true);
    } catch (InterruptedException e) {
      interrupted.set(true);
      Thread.currentThread().interrupt();
    }
  }
}
