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
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;

//JDK imports
import java.util.Date;
import java.util.List;
import java.util.Vector;

/**
 * 
 * The state of a WorkflowProcessor.
 *
 * A state knows its name, the stage (category) it belongs to, and, when the
 * lifecycle declares them, which states may follow it and what has to be true
 * before it can be entered. The last two are what let a lifecycle file describe
 * a state machine rather than a list of labels; a lifecycle that declares
 * neither behaves exactly as it always has.
 * 
 * @author bfoster
 * @author mattmann
 * @version $Revision$
 * 
 */
public class WorkflowState {

  private String name;
  private String description;
	private String message;
	private Date startTime;
	private WorkflowLifecycleStage category;
	private WorkflowState prevState;

	/** Names of the states reachable from this one; empty when undeclared. */
	private List<String> nextStateNames;

	/** Guards that must all be met before this state can be entered. */
	private List<AttachedPreCondition> preConditions;

	public WorkflowState(){
	  this.startTime = null;
	  this.name = null;
	  this.description = null;
	  this.message = null;
	  this.category = null;
	  this.prevState = null;
	  this.nextStateNames = new Vector<String>();
	  this.preConditions = new Vector<AttachedPreCondition>();
	}
	
	public WorkflowState(String message) {
	  this();
		this.message = message;
		this.startTime = new Date();
	}
	
	public void setMessage(String message){
	  this.message = message;
	}
	
	public void setStartTime(Date startTime){
	  this.startTime = startTime;
	}
	
	public String getMessage() {
		return this.message;
	}
	
	public Date getStartTime() {
		return this.startTime;
	}

	/**
	 * States are identified by name, and a name is the only thing two copies of
	 * the same state are guaranteed to share: the lifecycle hands out copies
	 * carrying whatever message the engine set at the time.
	 */
	public boolean equals(Object obj) {
		if (!(obj instanceof WorkflowState)) {
		  return false;
		}
		String otherName = ((WorkflowState) obj).getName();
		if (this.name == null) {
		  return otherName == null;
		}
		return this.name.equals(otherName);
	}
		
	public String toString() {
		return this.getName() + " ["+this.getCategory()+"] : " + this.getMessage();
	}

  /**
   * @return the name
   */
  public String getName() {
    return name;
  }

  /**
   * @param name the name to set
   */
  public void setName(String name) {
    this.name = name;
  }

  /**
   * @return the description
   */
  public String getDescription() {
    return description;
  }

  /**
   * @param description the description to set
   */
  public void setDescription(String description) {
    this.description = description;
  }

  /**
   * @return the category
   */
  public WorkflowLifecycleStage getCategory() {
    return category;
  }

  /**
   * @param category the category to set
   */
  public void setCategory(WorkflowLifecycleStage category) {
    this.category = category;
  }

  /**
   * @return the prevState
   */
  public WorkflowState getPrevState() {
    return prevState;
  }

  /**
   * @param prevState the prevState to set
   */
  public void setPrevState(WorkflowState prevState) {
    this.prevState = prevState;
  }

  /**
   * The names of the states that this state is allowed to transition to, in
   * the order the lifecycle declared them.
   *
   * An empty list means the lifecycle says nothing about what follows this
   * state, which leaves the decision to whatever the engine did before.
   *
   * @return the declared next state names, never null
   */
  public List<String> getNextStateNames() {
    return nextStateNames;
  }

  /**
   * @param nextStateNames the next state names to set
   */
  public void setNextStateNames(List<String> nextStateNames) {
    this.nextStateNames = nextStateNames != null ? nextStateNames
        : new Vector<String>();
  }

  /**
   * Adds a state name to the set of states reachable from this one.
   *
   * @param stateName the name of the reachable state
   */
  public void addNextStateName(String stateName) {
    if (stateName != null && !stateName.equals("")
        && !this.nextStateNames.contains(stateName)) {
      this.nextStateNames.add(stateName);
    }
  }

  /**
   * The guards that must all be met before this state can be entered.
   *
   * A state with no preconditions can always be entered, so declaring a next
   * state without preconditions makes that transition unconditional.
   *
   * @return the attached preconditions, never null
   */
  public List<AttachedPreCondition> getPreConditions() {
    return preConditions;
  }

  /**
   * @param preConditions the preconditions to set
   */
  public void setPreConditions(List<AttachedPreCondition> preConditions) {
    this.preConditions = preConditions != null ? preConditions
        : new Vector<AttachedPreCondition>();
  }

  /**
   * @param preCondition a precondition to attach to this state
   */
  public void addPreCondition(AttachedPreCondition preCondition) {
    if (preCondition != null) {
      this.preConditions.add(preCondition);
    }
  }

  /**
   * Whether every precondition attached to this state is met for the given
   * instance.
   *
   * All of them have to pass, which is how preconditions already read
   * everywhere else in OODT: a task runs when its preconditions are satisfied,
   * not when one of them is. A state with no preconditions is trivially
   * enterable.
   *
   * @param instance
   *          The instance being considered for transition into this state.
   * @return True if the state may be entered.
   */
  public boolean preConditionsMet(WorkflowInstance instance) {
    for (AttachedPreCondition preCondition : this.preConditions) {
      if (!preCondition.isMet(this, instance)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Consistent with {@link #equals(Object)}, which compares names alone. The
   * previous implementation mixed in the message and start time, so two states
   * that compared equal could hash differently and a state used as a map key
   * would not be found again once the engine set a message on it.
   */
  @Override
  public int hashCode() {
	return name != null ? name.hashCode() : 0;
  }

  /**
   * A {@link StatePreCondition} together with the configuration declared
   * alongside it in the lifecycle file.
   *
   * The precondition itself is stateless and shared by every workflow using
   * the lifecycle; the configuration is what makes two attachments of the same
   * class behave differently.
   */
  public static class AttachedPreCondition {

    private final String className;

    private final StatePreCondition preCondition;

    private final WorkflowConditionConfiguration configuration;

    public AttachedPreCondition(String className,
        StatePreCondition preCondition,
        WorkflowConditionConfiguration configuration) {
      this.className = className;
      this.preCondition = preCondition;
      this.configuration = configuration != null ? configuration
          : new WorkflowConditionConfiguration();
    }

    /**
     * @return the class name the precondition was declared with, retained for
     *         logging and for reporting a lifecycle back out
     */
    public String getClassName() {
      return className;
    }

    /**
     * @return the precondition
     */
    public StatePreCondition getPreCondition() {
      return preCondition;
    }

    /**
     * @return the configuration, never null
     */
    public WorkflowConditionConfiguration getConfiguration() {
      return configuration;
    }

    /**
     * Evaluates the precondition.
     *
     * @param candidateState
     *          The state being considered.
     * @param instance
     *          The instance being transitioned.
     * @return The precondition's answer, or false if it could not be
     *         instantiated, since a guard that cannot run must not be treated
     *         as satisfied.
     */
    public boolean isMet(WorkflowState candidateState,
        WorkflowInstance instance) {
      return this.preCondition != null
          && this.preCondition.isMet(candidateState, instance,
              this.configuration);
    }

    @Override
    public String toString() {
      return this.className;
    }
  }
}
