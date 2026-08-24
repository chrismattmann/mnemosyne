/**
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

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionInstance;

//JDK imports
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Ignore;

/**
 * A condition a test can open and close, which records being asked.
 *
 * Whether a condition is consulted at all is as interesting as its answer: a
 * gate that is never evaluated looks identical to one that always passes,
 * right up until it is supposed to stop something.
 *
 * @author mattmann
 */
@Ignore
public class GateCondition implements WorkflowConditionInstance {

  private static volatile boolean open = true;

  private static final AtomicInteger EVALUATIONS = new AtomicInteger();

  public static void reset() {
    open = true;
    EVALUATIONS.set(0);
  }

  public static void open(boolean isOpen) {
    open = isOpen;
  }

  /** How many times the engine has asked. */
  public static int evaluations() {
    return EVALUATIONS.get();
  }

  @Override
  public boolean evaluate(Metadata metadata,
      WorkflowConditionConfiguration config) {
    EVALUATIONS.incrementAndGet();
    // Recorded in the same log the tasks write to, so a test can assert the
    // order the phases actually happened in rather than only that each did.
    String name = config != null ? config.getProperty("RecordAs") : null;
    if (name != null) {
      RecordingTask.record(name);
    }
    return open;
  }
}
