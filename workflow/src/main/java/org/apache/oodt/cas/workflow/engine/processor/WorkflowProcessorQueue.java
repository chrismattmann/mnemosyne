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

package org.apache.oodt.cas.workflow.engine.processor;

//OODT imports
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycle;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.repository.WorkflowRepository;
import org.apache.oodt.cas.workflow.structs.*;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;
import org.apache.oodt.cas.workflow.structs.exceptions.RepositoryException;

//JDK imports
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 
 * The queue of available {@link WorkflowTask}s, that will be fed into the
 * {@link org.apache.oodt.cas.workflow.engine.TaskQuerier}.
 * 
 * @author mattmann
 * @version $Revision$
 * 
 */
public class WorkflowProcessorQueue {

  private static final Logger LOG = Logger
      .getLogger(WorkflowProcessorQueue.class.getName());

  private WorkflowInstanceRepository repo;

  private WorkflowRepository modelRepo;

  private WorkflowLifecycleManager lifecycle;

  private Map<String, WorkflowProcessor> processorCache;

  protected static final String SEQUENTIAL = "sequential";

  protected static final String PARALLEL = "parallel";

  /**
   * Execution type given to a workflow whose graph does not specify one.
   *
   * A workflow that arrived from a repository with no notion of graphs has no
   * execution type, and the engine has to decide how to run it. Sequential is
   * the safe reading of an ordered task list; setting
   * org.apache.oodt.cas.workflow.wengine.packagedRepo.parallelProcessors to
   * true says the tasks are independent and the graph should be left to run
   * them in parallel.
   */
  private final String defaultExecutionType;

  public WorkflowProcessorQueue(WorkflowInstanceRepository repo,
      WorkflowLifecycleManager lifecycle, WorkflowRepository modelRepo) {
    this.repo = repo;
    this.lifecycle = lifecycle;
    this.modelRepo = modelRepo;
    this.processorCache = new ConcurrentHashMap<String, WorkflowProcessor>();
    this.defaultExecutionType = Boolean.parseBoolean(System.getProperty(
        "org.apache.oodt.cas.workflow.wengine.packagedRepo.parallelProcessors",
        "false")) ? PARALLEL : SEQUENTIAL;
  }

  /**
   * Supplies the graph detail the engine needs but the workflow did not carry.
   *
   * The alternative was to make every repository produce graph-shaped
   * workflows, which pushes one engine's model into code the other engine also
   * uses. Doing it here keeps the repositories unaware of graphs and leaves
   * this engine able to run workflows from any of them.
   *
   * Without this the engine fell back to inspecting the workflow id for
   * "task-workflow", "pre-cond" and "post-cond" prefixes to guess whether it
   * held a composite. Those prefixes are still honoured below, because they
   * are structural markers this engine creates itself, but a user's workflow
   * is no longer classified by the shape of its id.
   */
  private void ensureExecutionType(WorkflowInstance instance) {
    ParentChildWorkflow workflow = instance.getParentChildWorkflow();
    if (workflow == null || workflow.getGraph() == null) {
      return;
    }
    String executionType = workflow.getGraph().getExecutionType();
    if (executionType == null || executionType.equals("")) {
      workflow.getGraph().setExecutionType(this.defaultExecutionType);
      LOG.log(Level.FINE, "Workflow instance: [" + instance.getId()
          + "] carried no execution type; defaulting to ["
          + this.defaultExecutionType + "]");
    }
  }

  /**
   * Builds a processor that runs one condition.
   *
   * A condition executes as a task: ConditionTaskInstance evaluates it and
   * throws when the answer is false, which the runner records as a failure.
   * The instance is persisted like any other, so the querier finds it and runs
   * it; what makes it a gate rather than just more work is that the processors
   * it governs hold a reference to it.
   *
   * @param parent
   *          The instance the condition belongs to.
   * @param cond
   *          The condition to run.
   * @param idPrefix
   *          Distinguishes the generated workflow id.
   * @return The processor for that condition.
   */
  private WorkflowProcessor buildConditionProcessor(WorkflowInstance parent,
      WorkflowCondition cond, String idPrefix) {
    WorkflowInstance instance = new WorkflowInstance();
    instance.setState(lifecycle.getDefaultLifecycle().createState("Null",
        "initial", "Condition created for workflow instance: ["
            + parent.getId() + "]"));
    instance.setPriority(parent.getPriority());
    shareContext(parent, instance);

    WorkflowTask conditionTask = toConditionTask(cond);
    instance.setCurrentTaskId(conditionTask.getTaskId());
    Graph condGraph = new Graph();
    condGraph.setExecutionType("condition");
    condGraph.setCond(cond);
    condGraph.setTask(conditionTask);
    ParentChildWorkflow workflow = new ParentChildWorkflow(condGraph);
    workflow.setId(idPrefix + cond.getConditionId() + "-"
        + parent.getParentChildWorkflow().getId());
    workflow.setName("Condition Workflow-" + cond.getConditionName());
    workflow.getTasks().add(conditionTask);
    instance.setParentChildWorkflow(workflow);
    this.addToModelRepo(workflow);
    persist(instance);

    WorkflowProcessor condProcessor = fromWorkflowInstance(instance);
    synchronized (processorCache) {
      processorCache.put(instance.getId(), condProcessor);
    }
    return condProcessor;
  }

  /**
   * Gives a sub-instance the context its parent is carrying.
   *
   * A workflow's tasks are run as sub-instances built here, and each one used
   * to be constructed with a fresh, empty Metadata. Nothing ever copied the
   * parent's context into them, so metadata handed to startWorkflow never
   * reached a task, and nothing a task wrote was visible to the task after it:
   * every task ran against an empty context and its output went nowhere.
   *
   * The same object is shared rather than copied, which is what a shared
   * context means and matches how the ThreadPool engine has always carried one
   * Metadata for the life of an instance. Writes therefore flow in both
   * directions, which is what makes a sequential workflow able to pass work
   * along.
   *
   * @param parent
   *          The instance the sub-instance was derived from.
   * @param child
   *          The sub-instance being built.
   */
  private void shareContext(WorkflowInstance parent, WorkflowInstance child) {
    if (parent.getSharedContext() == null) {
      parent.setSharedContext(new Metadata());
    }
    child.setSharedContext(parent.getSharedContext());
  }

  /**
   * Should return the list of available, Queued, {@link WorkflowProcessor}s.
   * 
   * @return the list of available, Queued, {@link WorkflowProcessor}s.
   */
  public synchronized List<WorkflowProcessor> getProcessors() {
    // Ask the repository for the instances that are not done, rather than
    // paging through everything and discarding the finished ones here. Once a
    // deployment has run for a while the done instances outnumber the live
    // ones, and a page of them yields no runnable work at all.
    List<WorkflowInstance> instances;
    try {
      instances = (List<WorkflowInstance>) (List<?>) repo
          .getWorkflowInstancesNotByCategory("done");
    } catch (Exception e) {
      LOG.log(Level.SEVERE, e.getMessage());
      LOG.log(Level.WARNING, "Unable to load workflow processors: Message: "
          + e.getMessage());
      return null;
    }

    List<WorkflowProcessor> processors = new Vector<WorkflowProcessor>(
        instances != null ? instances.size() : 0);
    for (WorkflowInstance inst : instances) {
      // Retained as a guard: an instance with no state cannot be categorised,
      // and repositories are free to return those from the excluding query.
      if (inst.getState() != null && inst.getState().getCategory() != null
          && !inst.getState().getCategory().getName().equals("done")) {
        WorkflowProcessor processor;
        try {
          processor = fromWorkflowInstance(inst);
        } catch (Exception e) {
          LOG.log(Level.SEVERE, e.getMessage());
          LOG.log(Level.WARNING,
              "Unable to convert workflow instance: [" + inst.getId()
                  + "] into WorkflowProcessor: Message: " + e.getMessage());
          continue;
        }
        if (processor != null) {
          processors.add(processor);
        }
      }
    }

    return processors;
  }
  

  public synchronized void persist(WorkflowInstance inst) {
    try {
      if (inst.getId() == null || (inst.getId().equals(""))) {
        // we have to persist it by adding it
        // rather than updating it
        repo.addWorkflowInstance(inst);
      } else {
        // persist by update
        repo.updateWorkflowInstance(inst);
      }
    } catch (InstanceRepositoryException e) {
      LOG.log(Level.SEVERE, e.getMessage());
      LOG.log(Level.WARNING,
          "Unable to update workflow instance: [" + inst.getId()
              + "] with status: [" + inst.getState().getName() + "]: Message: "
              + e.getMessage());
    }
  }  

  private WorkflowProcessor fromWorkflowInstance(WorkflowInstance inst) {
    WorkflowProcessor processor = null;
    if (processorCache.containsKey(inst.getId())) {
      return processorCache.get(inst.getId());
    } else {
      // Convert here, at this engine's boundary, rather than requiring the
      // repository to have produced a graph.
      ensureExecutionType(inst);
      if (inst.getParentChildWorkflow().getGraph() == null) {
        LOG.log(Level.SEVERE,
            "Unable to process Graph for workflow instance: [" + inst.getId()
                + "]");
        return null;
      }

      if (isCompositeProcessor(inst)) {
        processor = getProcessorFromInstanceGraph(inst, lifecycle);
        WorkflowState processorState = getLifecycle(
            inst.getParentChildWorkflow()).createState(
            "Loaded",
            "initial",
            "Sequential Workflow instance with id: [" + inst.getId()
                + "] loaded by processor queue.");
        inst.setState(processorState);
        persist(inst);

        // handle its pre-conditions
        // Collected as they are built so that the tasks they guard can be
        // told what governs them. A task is discovered from the instance
        // repository on its own, so its parent is not on the path when the
        // querier decides whether to run it.
        List<WorkflowProcessor> gatingConditions =
            new Vector<WorkflowProcessor>();
        for (WorkflowCondition cond : inst.getParentChildWorkflow()
            .getPreConditions()) {
          WorkflowInstance instance = new WorkflowInstance();
          WorkflowState condWorkflowState = lifecycle
              .getDefaultLifecycle()
              .createState(
                  "Null",
                  "initial",
                  "Sub Pre Condition Workflow created by Workflow Processor Queue for workflow instance: "
                      + "[" + inst.getId() + "]");
          instance.setState(condWorkflowState);
          instance.setPriority(inst.getPriority());
          shareContext(inst, instance);
          WorkflowTask conditionTask = toConditionTask(cond);
          instance.setCurrentTaskId(conditionTask.getTaskId());
          Graph condGraph = new Graph();
          condGraph.setExecutionType("condition");
          condGraph.setCond(cond);
          condGraph.setTask(conditionTask);
          ParentChildWorkflow workflow = new ParentChildWorkflow(condGraph);
          workflow.setId("pre-cond-workflow-"
              + inst.getParentChildWorkflow().getId());
          workflow.setName("Pre Condition Workflow-" + cond.getConditionName());
          workflow.getTasks().add(conditionTask);
          instance.setParentChildWorkflow(workflow);
          this.addToModelRepo(workflow);
          persist(instance);
          WorkflowProcessor subProcessor = fromWorkflowInstance(instance);
          // Every condition is persisted as an instance of its own and the
          // querier offers each non-done instance independently, so conditions
          // have always been evaluated all at once no matter what their block
          // asked for. A block that asked to be sequential gets each condition
          // gated on the one before it, using the same prerequisite mechanism
          // that gates a task on its conditions. A block that said nothing is
          // left exactly as it was.
          if (Workflow.SEQUENTIAL_CONDITIONS.equalsIgnoreCase(inst
              .getParentChildWorkflow().getPreConditionExecutionType())
              && !gatingConditions.isEmpty()) {
            subProcessor.setPrerequisites(Collections
                .singletonList(gatingConditions.get(gatingConditions.size() - 1)));
          }
          processor.getSubProcessors().add(subProcessor);
          gatingConditions.add(subProcessor);
          // The parent listens to the child, so a child finishing is acted on
          // at once instead of on the querier's next pass. The parent still
          // recomputes from all its children when it reacts, so a lost
          // notification costs latency rather than correctness.
          subProcessor.getListeners().add(processor);
          synchronized (processorCache) {
            processorCache.put(instance.getId(), subProcessor);
          }
        }

        // handle its tasks
        List<WorkflowProcessor> taskProcessors =
            new Vector<WorkflowProcessor>();
        for (WorkflowTask task : inst.getParentChildWorkflow().getTasks()) {
          WorkflowInstance instance = new WorkflowInstance();
          WorkflowState taskWorkflowState = lifecycle.getDefaultLifecycle()
              .createState(
                  "Null",
                  "initial",
                  "Sub Task Workflow created by Workflow Processor Queue for workflow instance: "
                      + "[" + inst.getId() + "]");
          instance.setState(taskWorkflowState);
          instance.setPriority(inst.getPriority());
          shareContext(inst, instance);
          instance.setCurrentTaskId(task.getTaskId());
          Graph taskGraph = new Graph();
          taskGraph.setExecutionType("task");
          taskGraph.setTask(task);
          ParentChildWorkflow workflow = new ParentChildWorkflow(taskGraph);
          workflow.setId("task-workflow-"
              + inst.getParentChildWorkflow().getId());
          workflow.setName("Task Workflow-" + task.getTaskName());
          workflow.getTasks().add(task);
          workflow.getGraph().setTask(task);
          instance.setParentChildWorkflow(workflow);
          this.addToModelRepo(workflow);
          persist(instance);
          WorkflowProcessor subProcessor = fromWorkflowInstance(instance);
          processor.getSubProcessors().add(subProcessor);
          // What the workflow's conditions say applies to its tasks. The
          // check is already written -- TaskProcessor will not offer itself
          // as runnable while passedPreConditions is false -- and until now
          // there was simply nothing for it to check, so a condition ran
          // beside the task it was supposed to guard and gated nothing.
          // Conditions written on the task itself gate it too, alongside the
          // workflow's. Nothing used to build these at all, which is why a
          // task-level condition was never so much as evaluated.
          List<WorkflowProcessor> taskGates =
              new Vector<WorkflowProcessor>(gatingConditions);
          for (Object taskCondObj : task.getConditions()) {
            WorkflowCondition taskCond = (WorkflowCondition) taskCondObj;
            WorkflowProcessor condProcessor = buildConditionProcessor(inst,
                taskCond, "task-cond-workflow-");
            processor.getSubProcessors().add(condProcessor);
            condProcessor.getListeners().add(processor);
            taskGates.add(condProcessor);
          }
          subProcessor.setPrerequisites(taskGates);
          taskProcessors.add(subProcessor);
          // The parent listens to the child, so a child finishing is acted on
          // at once instead of on the querier's next pass. The parent still
          // recomputes from all its children when it reacts, so a lost
          // notification costs latency rather than correctness.
          subProcessor.getListeners().add(processor);
          synchronized (processorCache) {
            processorCache.put(instance.getId(), subProcessor);
          }
        }

        // handle its post conditions
        // Gated on the tasks, so they run after the work rather than beside
        // it. A post-condition exists to judge what the tasks produced, so one
        // evaluated while they are still running is judging nothing.
        List<WorkflowProcessor> postConditions =
            new Vector<WorkflowProcessor>();
        boolean postSequential = Workflow.SEQUENTIAL_CONDITIONS
            .equalsIgnoreCase(inst.getParentChildWorkflow()
                .getPostConditionExecutionType());
        for (WorkflowCondition cond : inst.getParentChildWorkflow()
            .getPostConditions()) {
          WorkflowProcessor condProcessor = buildConditionProcessor(inst, cond,
              "post-cond-workflow-");
          // The tasks, plus -- when the block asked to be sequential -- the
          // post-condition before this one. See the note on the pre-conditions.
          List<WorkflowProcessor> gates =
              new Vector<WorkflowProcessor>(taskProcessors);
          if (postSequential && !postConditions.isEmpty()) {
            gates.add(postConditions.get(postConditions.size() - 1));
          }
          condProcessor.setPrerequisites(gates);
          postConditions.add(condProcessor);
          processor.getSubProcessors().add(condProcessor);
          condProcessor.getListeners().add(processor);
        }

      } else {
        // it's not a composite workflow, and it's either just a task processor
        // or a condition processor
        if (inst.getParentChildWorkflow().getGraph().getExecutionType()
            .equals("task")) {
          processor = new TaskProcessor(lifecycle, inst);
          WorkflowState taskProcessorState = getLifecycle(
              inst.getParentChildWorkflow()).createState(
              "Loaded",
              "initial",
              "Task Workflow instance with id: [" + inst.getId()
                  + "] loaded by processor queue.");
          inst.setState(taskProcessorState);

          // handle its pre-conditions
          for (WorkflowCondition cond : inst.getParentChildWorkflow()
              .getGraph().getTask().getPreConditions()) {
            WorkflowInstance instance = new WorkflowInstance();
            WorkflowState condWorkflowState = lifecycle
                .getDefaultLifecycle()
                .createState(
                    "Null",
                    "initial",
                    "Sub Pre Condition Workflow for Task created by Workflow Processor Queue for workflow instance: "
                        + "[" + inst.getId() + "]");
            instance.setState(condWorkflowState);
            instance.setPriority(inst.getPriority());
            WorkflowTask conditionTask = toConditionTask(cond);
            instance.setCurrentTaskId(conditionTask.getTaskId());
            Graph condGraph = new Graph();
            condGraph.setExecutionType("condition");
            condGraph.setCond(cond);
            condGraph.setTask(conditionTask);
            ParentChildWorkflow workflow = new ParentChildWorkflow(condGraph);
            workflow.setId("pre-cond-workflow-"
                + inst.getParentChildWorkflow().getGraph().getTask()
                    .getTaskId());
            workflow.setName("Task Pre Condition Workflow-"
                + cond.getConditionName());
            workflow.getTasks().add(conditionTask);
            instance.setParentChildWorkflow(workflow);
            this.addToModelRepo(workflow);
            persist(instance);
            WorkflowProcessor subProcessor = fromWorkflowInstance(instance);
            processor.getSubProcessors().add(subProcessor);
            synchronized (processorCache) {
              processorCache.put(instance.getId(), subProcessor);
            }
          }

          // handle its post-conditions
          for (WorkflowCondition cond : inst.getParentChildWorkflow()
              .getGraph().getTask().getPostConditions()) {
            WorkflowInstance instance = new WorkflowInstance();
            WorkflowState condWorkflowState = lifecycle
                .getDefaultLifecycle()
                .createState(
                    "Null",
                    "initial",
                    "Sub Post Condition Workflow for Task created by Workflow Processor Queue for workflow instance: "
                        + "[" + inst.getId() + "]");
            instance.setState(condWorkflowState);
            instance.setPriority(inst.getPriority());
            WorkflowTask conditionTask = toConditionTask(cond);
            instance.setCurrentTaskId(conditionTask.getTaskId());
            Graph condGraph = new Graph();
            condGraph.setExecutionType("condition");
            condGraph.setCond(cond);
            condGraph.setTask(conditionTask);
            ParentChildWorkflow workflow = new ParentChildWorkflow(condGraph);
            workflow.setId("post-cond-workflow-"
                + inst.getParentChildWorkflow().getGraph().getTask()
                    .getTaskId());
            workflow.setName("Task Post Condition Workflow-"
                + cond.getConditionName());
            workflow.getTasks().add(conditionTask);
            instance.setParentChildWorkflow(workflow);
            this.addToModelRepo(workflow);
            persist(instance);
            WorkflowProcessor subProcessor = fromWorkflowInstance(instance);
            processor.getSubProcessors().add(subProcessor);
            synchronized (processorCache) {
              processorCache.put(instance.getId(), subProcessor);
            }
          }

        } else if (inst.getParentChildWorkflow().getGraph().getExecutionType()
            .equals("condition")) {
          processor = new ConditionProcessor(lifecycle, inst);
          WorkflowState condProcessorState = getLifecycle(
              inst.getParentChildWorkflow()).createState(
              "Loaded",
              "initial",
              "Condition Workflow instance with id: [" + inst.getId()
                  + "] loaded by processor queue.");
          inst.setState(condProcessorState);
        }
        persist(inst);
      }

      synchronized (processorCache) {
        processorCache.put(inst.getId(), processor);
      }
      return processor;
    }

  }
  
  private synchronized void addTaskToModelRepo(WorkflowTask task){
    if(modelRepo != null){
      try{
        modelRepo.addTask(task);
      }
      catch(RepositoryException e){
        LOG.log(Level.SEVERE, e.getMessage());
      }
    }
  }

  private synchronized void addToModelRepo(Workflow workflow) {
    if (modelRepo != null) {
      try {
        modelRepo.addWorkflow(workflow);
      } catch (RepositoryException e) {
        LOG.log(Level.SEVERE, e.getMessage());
      }
    }
  }

  private WorkflowLifecycle getLifecycle(Workflow workflow) {
    return lifecycle.getLifecycleForWorkflow(workflow) != null ? lifecycle
        .getLifecycleForWorkflow(workflow) : lifecycle.getDefaultLifecycle();
  }

  private boolean isCompositeProcessor(WorkflowInstance instance) {
    if (instance.getParentChildWorkflow().getGraph() != null
        && instance.getParentChildWorkflow().getGraph().getExecutionType() != null
        && !instance.getParentChildWorkflow().getGraph().getExecutionType()
            .equals("")) {
      return instance.getParentChildWorkflow().getGraph().getExecutionType()
          .equals("parallel")
          || instance.getParentChildWorkflow().getGraph().getExecutionType()
              .equals("sequential");
    } else {
      // we don't have a Graph to work with, so we'll default to whether or not
      // so we'll assume this is a workflow instance delivered to us by the
      // instRep
      // which doesn't understand Graphs yet (TODO: make instRep understand
      // graphs
      // and persist them)
      // so the simple solution is to check whether or not the ID starts with
      // task-workflow or pre-cond or post-cond
      return !(instance.getParentChildWorkflow().getId()
          .startsWith("task-workflow")
          || instance.getParentChildWorkflow().getId().startsWith("pre-cond") || instance
          .getParentChildWorkflow().getId().startsWith("post-cond"));
    }
  }

  private WorkflowProcessor getProcessorFromInstanceGraph(
      WorkflowInstance instance, WorkflowLifecycleManager lifecycle) {
    Graph graph = instance.getParentChildWorkflow().getGraph();
    if (graph != null && graph.getExecutionType() != null
        && graph.getExecutionType().equals("sequential")) {
      return new SequentialProcessor(lifecycle, instance);
    } else {
      return new ParallelProcessor(lifecycle, instance);
    }
  }
  
  private synchronized WorkflowTask toConditionTask(WorkflowCondition cond){    
    String taskId = cond.getConditionId()+"-task"; // TODO: this is incompat with DataSourceWorkflowRepository
    WorkflowTask condTask = safeGetTaskById(taskId);
    if(condTask != null) {
      return condTask;
    }
    condTask = new WorkflowTask();
    condTask.setTaskId(taskId);
    condTask.setTaskInstanceClassName(ConditionTaskInstance.class.getCanonicalName());
    condTask.setTaskName(cond.getConditionName()+" Task");
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.getProperties().putAll(cond.getCondConfig().getProperties());
    // this one is a special one that will be removed by the ConditionTaskInstance class
    config.addConfigProperty("ConditionClassName", cond.getConditionInstanceClassName()); 
    condTask.setTaskConfig(config);
    this.addTaskToModelRepo(condTask);
    return condTask;
  }
  
  private WorkflowTask safeGetTaskById(String taskId){
    WorkflowTask task = null;
      try{
        if((task = this.modelRepo.getTaskById(taskId)) != null){
          return task;
        }
      }
      catch(RepositoryException e){
        LOG.log(Level.SEVERE, e.getMessage());
      }
    
    return null;
  }

}
