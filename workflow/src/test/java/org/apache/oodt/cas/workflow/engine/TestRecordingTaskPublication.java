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
package org.apache.oodt.cas.workflow.engine;

import junit.framework.TestCase;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;

/**
 * The recorder must not announce a task before publishing what it saw.
 *
 * <p>
 * Every test that uses {@link RecordingTask} waits for a name to appear in
 * {@code recorded()} and then reads {@code keysSeenBy(name)}. If the name is
 * added first, the waiter is released while the keys are still being written,
 * and the read comes back empty -- so the assertion fails on a test whose
 * subject worked perfectly.
 * </p>
 *
 * <p>
 * That window is nanoseconds on an idle machine and wide enough to lose on a
 * loaded CI runner, which is what made TestTaskMetadataIsStamped fail once and
 * then pass on a re-run. A test that fails for a reason unrelated to what it
 * tests is worse than no test, because the next real failure is read as noise
 * and re-run away.
 * </p>
 */
public class TestRecordingTaskPublication extends TestCase {

  private static final int ATTEMPTS = 2000;

  /**
   * Spun rather than slept, and repeated, because the window being closed is
   * a few instructions wide. One attempt would pass on the broken ordering
   * almost every time.
   */
  public void testTheKeysAreVisibleAsSoonAsTheNameIs() throws Exception {
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      RecordingTask.reset();

      final Metadata metadata = new Metadata();
      metadata.addMetadata("SuppliedAtStart", "yes");
      final WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
      config.addConfigProperty("RecordAs", "first");

      Thread recorder = new Thread(new Runnable() {
        public void run() {
          try {
            new RecordingTask().run(metadata, config);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      });
      recorder.start();

      // Exactly what awaitRecorded does: wait on the name, then read the keys.
      while (!RecordingTask.recorded().contains("first")) {
        Thread.yield();
      }
      assertTrue("attempt " + attempt + ": the name was announced before the "
          + "keys were published, so a waiter reads an empty list",
          RecordingTask.keysSeenBy("first").contains("SuppliedAtStart"));

      recorder.join();
    }
  }

  /** Nothing about the ordering change loses the recording itself. */
  public void testTheTaskIsStillRecorded() throws Exception {
    RecordingTask.reset();
    Metadata metadata = new Metadata();
    metadata.addMetadata("SuppliedAtStart", "yes");
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("RecordAs", "first");

    new RecordingTask().run(metadata, config);

    assertTrue(RecordingTask.recorded().contains("first"));
    assertTrue(RecordingTask.keysSeenBy("first").contains("SuppliedAtStart"));
    assertTrue("the mark for whatever runs next is still left",
        metadata.getAllKeys().contains("ranBy-first"));
  }
}
