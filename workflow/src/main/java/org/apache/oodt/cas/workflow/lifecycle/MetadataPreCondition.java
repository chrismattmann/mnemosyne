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

package org.apache.oodt.cas.workflow.lifecycle;

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

//JDK imports
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * A {@link StatePreCondition} that reads the instance's shared metadata.
 *
 * This covers the case a lifecycle most often needs: a workflow branches on
 * something a task already put in the shared context, so which state comes next
 * is a question about metadata rather than about code. Shipping it means a
 * lifecycle can describe a branch without anyone writing Java for it.
 *
 * Configured with:
 * <ul>
 *   <li><code>key</code> - the metadata key to look at. Required; without it
 *       the precondition is never met, since a guard that does not know what
 *       it is guarding cannot be satisfied.</li>
 *   <li><code>value</code> - optional. When given, the key must carry this
 *       value; when omitted, the key merely has to be present.</li>
 *   <li><code>negate</code> - optional. When <code>true</code>, the test above
 *       is inverted, so the state is entered exactly when it would otherwise
 *       have been skipped.</li>
 * </ul>
 *
 * @author mattmann
 */
public class MetadataPreCondition implements StatePreCondition {

  private static final Logger LOG = Logger
      .getLogger(MetadataPreCondition.class.getName());

  public static final String KEY = "key";

  public static final String VALUE = "value";

  public static final String NEGATE = "negate";

  public boolean isMet(WorkflowState candidateState, WorkflowInstance instance,
      WorkflowConditionConfiguration config) {
    String key = config != null ? config.getProperty(KEY) : null;
    if (key == null || key.trim().equals("")) {
      LOG.log(Level.WARNING, "Metadata precondition on state: ["
          + (candidateState != null ? candidateState.getName() : "null")
          + "] declares no [" + KEY + "] property; treating it as unmet");
      return false;
    }

    boolean negate = "true".equalsIgnoreCase(config.getProperty(NEGATE));
    return holds(key.trim(), config.getProperty(VALUE), instance) != negate;
  }

  private boolean holds(String key, String requiredValue,
      WorkflowInstance instance) {
    Metadata context = instance != null ? instance.getSharedContext() : null;
    if (context == null) {
      return false;
    }

    if (requiredValue == null) {
      return context.containsKey(key);
    }

    // getAllMetadata rather than getMetadata: a key holding several values
    // satisfies the condition if any of them is the one asked for.
    java.util.List<String> values = context.getAllMetadata(key);
    return values != null && values.contains(requiredValue);
  }
}
