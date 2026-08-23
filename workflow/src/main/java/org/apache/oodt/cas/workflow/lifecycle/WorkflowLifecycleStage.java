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

//JDK imports
import java.util.List;
import java.util.Vector;

/**
 * 
 * A particular step (or Stage) in a {@link WorkflowLifecycle}
 * 
 * @author mattmann
 * @version $Revision$
 * 
 */
public class WorkflowLifecycleStage {

  private String name;

  private int order;

  /**
   * Tie-break weight when more than one state is eligible to be entered.
   * Higher wins. Zero, the default, means the stage expresses no preference.
   */
  private int priority;

  private List<WorkflowState> states;

  /**
   * Default Constructor.
   * 
   */
  public WorkflowLifecycleStage() {
    states = new Vector<WorkflowState>();
    priority = 0;
  }

  /**
   * Constructs a new WorkflowLifecycleSage with the given parameters.
   * 
   * @param name
   *          The name of the WorkflowLifeCycleStage.
   * @param states
   *          The {@link List} of String states that are part of this particular
   *          stage.
   * 
   * @param order
   *          The ordering of this State in a {@List} of States that make
   *          up a {@link WorkflowLifeCycle}.
   */
  public WorkflowLifecycleStage(String name, List<WorkflowState> states,
      int order) {
    this(name, states, order, 0);
  }

  /**
   * Constructs a new WorkflowLifecycleStage with an explicit priority.
   *
   * @param name
   *          The name of the WorkflowLifecycleStage.
   * @param states
   *          The {@link List} of states that are part of this stage.
   * @param order
   *          Where this stage falls in the lifecycle, used for reporting how
   *          far along a workflow is.
   * @param priority
   *          How strongly this stage is preferred when several states are
   *          eligible at once. Separate from order on purpose: reordering the
   *          stages of a lifecycle changes what percent complete means, and
   *          should not silently change which transition the engine takes.
   */
  public WorkflowLifecycleStage(String name, List<WorkflowState> states,
      int order, int priority) {
    this.name = name;
    this.states = states;
    this.order = order;
    this.priority = priority;
  }

  /**
   * @return the name
   */
  public String getName() {
    return name;
  }

  /**
   * @param name
   *          the name to set
   */
  public void setName(String name) {
    this.name = name;
  }

  /**
   * @return the states
   */
  public List<WorkflowState> getStates() {
    return states;
  }

  /**
   * @param states
   *          the states to set
   */
  public void setStates(List<WorkflowState> states) {
    this.states = states;
  }

  /**
   * The tie-break weight used when more than one state is eligible to be
   * entered at the same moment. Higher wins; the default is zero.
   *
   * @return the priority
   */
  public int getPriority() {
    return priority;
  }

  /**
   * @param priority
   *          the priority to set
   */
  public void setPriority(int priority) {
    this.priority = priority;
  }

  /**
   * @return the order
   */
  public int getOrder() {
    return order;
  }

  /**
   * @param order
   *          the order to set
   */
  public void setOrder(int order) {
    this.order = order;
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.lang.Object#hashCode()
   */
  public int hashCode() {
    // Name alone, to stay consistent with equals. Mixing in the order meant
    // two stages that compared equal could hash apart.
    return this.name != null ? this.name.hashCode() : 0;
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.lang.Object#equals(java.lang.Object)
   */
  @Override
  public boolean equals(Object stage) {
    if (!(stage instanceof WorkflowLifecycleStage)) {
      return false;
    }
    String otherName = ((WorkflowLifecycleStage) stage).getName();
    if (this.name == null) {
      return otherName == null;
    }
    return this.name.equals(otherName);
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.lang.Object#toString()
   */
  @Override
  public String toString() {
    return this.name;
  }

}
