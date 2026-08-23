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
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskInstance;
import org.apache.oodt.cas.workflow.structs.exceptions.WorkflowTaskInstanceException;

//JDK imports
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;

import org.junit.Ignore;

/**
 * A task that records that it ran.
 *
 * The engine reports state, and state can look right while nothing has
 * actually executed. This records the fact of execution itself, so an
 * end-to-end test can assert on work done rather than on status alone.
 *
 * @author mattmann
 */
@Ignore
public class RecordingTask implements WorkflowTaskInstance {

  /** Names recorded by tasks that have run, in the order they ran. */
  private static final List<String> RECORDED =
      Collections.synchronizedList(new ArrayList<String>());

  /** Set to fail every task, to exercise the failure path. */
  private static volatile boolean failEverything = false;

  public static void reset() {
    RECORDED.clear();
    SEEN.clear();
    failEverything = false;
  }

  public static List<String> recorded() {
    synchronized (RECORDED) {
      return new ArrayList<String>(RECORDED);
    }
  }

  public static void failEverything(boolean fail) {
    failEverything = fail;
  }

  /** What each task saw in the shared context when it ran. */
  private static final java.util.Map<String, java.util.List<String>> SEEN =
      java.util.Collections.synchronizedMap(
          new java.util.LinkedHashMap<String, java.util.List<String>>());

  /**
   * The keys the named task found in the shared context it was handed.
   *
   * This is how Brian's first objection on the umbrella issue gets checked:
   * whether metadata actually flows through to the tasks that follow.
   */
  public static List<String> keysSeenBy(String name) {
    java.util.List<String> keys = SEEN.get(name);
    return keys != null ? new ArrayList<String>(keys)
        : new ArrayList<String>();
  }

  @Override
  public void run(Metadata metadata, WorkflowTaskConfiguration config)
      throws WorkflowTaskInstanceException {
    String name = config != null ? config.getProperty("RecordAs") : null;
    name = name != null ? name : "unnamed";
    RECORDED.add(name);

    if (metadata != null) {
      SEEN.put(name, new ArrayList<String>(metadata.getAllKeys()));
      // Leave a mark for whatever runs next.
      metadata.replaceMetadata("ranBy-" + name, "true");
    } else {
      SEEN.put(name, new ArrayList<String>());
    }

    if (failEverything) {
      throw new WorkflowTaskInstanceException("failing on purpose");
    }
  }
}
