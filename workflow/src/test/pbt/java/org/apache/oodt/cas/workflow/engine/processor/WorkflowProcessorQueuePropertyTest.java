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

package org.apache.oodt.cas.workflow.engine.processor;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.instrepo.MemoryWorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.repository.WorkflowRepository;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.Priority;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;

/**
 * Properties of {@link WorkflowProcessorQueue}, the piece of the engine that
 * turns persisted {@link WorkflowInstance}s into the tree of
 * {@link WorkflowProcessor}s the querier then schedules.
 *
 * <p>Everything the engine will ever run comes out of {@code getProcessors()}.
 * If a live instance does not yield a processor it is work that will never be
 * dispatched and never be reported as undispatched; if a task's guarding
 * conditions are not wired onto it as prerequisites the condition runs beside
 * the task instead of before it, and gates nothing.
 *
 * <p>The queue is driven entirely without the engine: a
 * {@link MemoryWorkflowInstanceRepository} holds the instances, a small map
 * standing in for a {@link WorkflowRepository} holds the models, and the
 * shipped {@code wengine-lifecycle.xml} supplies the states. Nothing here
 * starts a thread — the runner and querier loops are deliberately out of
 * scope — so every property is a statement about the queue's own selection and
 * wiring logic.
 */
class WorkflowProcessorQueuePropertyTest {

  private static final String LIFECYCLE =
      "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

  private static final String PARALLEL_PROCESSORS_PROPERTY =
      "org.apache.oodt.cas.workflow.wengine.packagedRepo.parallelProcessors";

  private static WorkflowLifecycleManager lifecycleManager;

  private static synchronized WorkflowLifecycleManager lifecycle()
      throws Exception {
    if (lifecycleManager == null) {
      lifecycleManager = new WorkflowLifecycleManager(LIFECYCLE);
    }
    return lifecycleManager;
  }

  /**
   * A {@link WorkflowRepository} that remembers what it is handed.
   *
   * <p>The queue writes the sub-workflows and condition tasks it manufactures
   * back into the model repository so that they can be read again, and reads
   * condition tasks back out of it to avoid rebuilding one per instance. Both
   * directions matter to the properties below, and none of the shipped
   * repositories can be pointed at an empty store without a file or a
   * database behind it.
   */
  private static final class MapWorkflowRepository
      implements WorkflowRepository {

    private final Map<String, Workflow> workflows =
        new LinkedHashMap<String, Workflow>();
    private final Map<String, WorkflowTask> tasks =
        new LinkedHashMap<String, WorkflowTask>();

    @Override
    public Workflow getWorkflowByName(String workflowName) {
      for (Workflow workflow : workflows.values()) {
        if (workflowName != null && workflowName.equals(workflow.getName())) {
          return workflow;
        }
      }
      return null;
    }

    @Override
    public Workflow getWorkflowById(String workflowId) {
      return workflows.get(workflowId);
    }

    @Override
    public List getWorkflows() {
      return new ArrayList<Workflow>(workflows.values());
    }

    @Override
    public List getTasksByWorkflowId(String workflowId) {
      Workflow workflow = workflows.get(workflowId);
      return workflow != null ? workflow.getTasks() : new Vector<WorkflowTask>();
    }

    @Override
    public List getTasksByWorkflowName(String workflowName) {
      Workflow workflow = getWorkflowByName(workflowName);
      return workflow != null ? workflow.getTasks() : new Vector<WorkflowTask>();
    }

    @Override
    public List getWorkflowsForEvent(String eventName) {
      return new Vector<Workflow>();
    }

    @Override
    public List getConditionsByTaskName(String taskName) {
      return new Vector<WorkflowCondition>();
    }

    @Override
    public List getConditionsByTaskId(String taskId) {
      WorkflowTask task = tasks.get(taskId);
      return task != null ? task.getConditions() : new Vector<WorkflowCondition>();
    }

    @Override
    public WorkflowTaskConfiguration getConfigurationByTaskId(String taskId) {
      WorkflowTask task = tasks.get(taskId);
      return task != null ? task.getTaskConfig() : new WorkflowTaskConfiguration();
    }

    @Override
    public WorkflowTask getWorkflowTaskById(String taskId) {
      return tasks.get(taskId);
    }

    @Override
    public WorkflowCondition getWorkflowConditionById(String conditionId) {
      return null;
    }

    @Override
    public List getRegisteredEvents() {
      return new Vector<String>();
    }

    @Override
    public String addWorkflow(Workflow workflow) {
      workflows.put(workflow.getId(), workflow);
      return workflow.getId();
    }

    @Override
    public List<WorkflowCondition> getConditionsByWorkflowId(String workflowId) {
      return new Vector<WorkflowCondition>();
    }

    @Override
    public String addTask(WorkflowTask task) {
      tasks.put(task.getTaskId(), task);
      return task.getTaskId();
    }

    @Override
    public WorkflowTask getTaskById(String taskId) {
      return tasks.get(taskId);
    }

    int workflowCount() {
      return workflows.size();
    }

    int taskCount() {
      return tasks.size();
    }
  }

  /** An instance repository that refuses to answer, to reach the error path. */
  private static final class FailingInstanceRepository
      extends MemoryWorkflowInstanceRepository {

    FailingInstanceRepository() {
      super(10);
    }

    @Override
    public List getWorkflowInstances() throws InstanceRepositoryException {
      throw new InstanceRepositoryException("the instance store is unavailable");
    }
  }

  private static WorkflowTask taskOf(String id, String name) {
    WorkflowTask task = new WorkflowTask();
    task.setTaskId(id);
    task.setTaskName(name);
    task.setTaskInstanceClassName(
        "org.apache.oodt.cas.workflow.examples.NoOpTask");
    task.setTaskConfig(new WorkflowTaskConfiguration());
    task.setPreConditions(new Vector<WorkflowCondition>());
    task.setPostConditions(new Vector<WorkflowCondition>());
    return task;
  }

  private static WorkflowCondition conditionOf(String id, String name) {
    WorkflowCondition condition = new WorkflowCondition();
    condition.setConditionId(id);
    condition.setConditionName(name);
    condition.setConditionInstanceClassName(
        "org.apache.oodt.cas.workflow.examples.TrueCondition");
    return condition;
  }

  /**
   * A composite instance: one workflow with the given number of tasks, the
   * given workflow-level pre- and post-conditions, and the given number of
   * conditions declared on each task.
   */
  private static WorkflowInstance compositeInstance(String id,
      String executionType, int taskCount, int preConditions,
      int postConditions, int conditionsPerTask) throws Exception {
    Graph graph = new Graph();
    graph.setExecutionType(executionType);
    ParentChildWorkflow workflow = new ParentChildWorkflow(graph);
    workflow.setId(id);
    workflow.setName("Workflow " + id);

    List<WorkflowTask> tasks = new Vector<WorkflowTask>();
    for (int i = 0; i < taskCount; i++) {
      WorkflowTask task = taskOf(id + "-task-" + i, "Task " + i);
      List<WorkflowCondition> taskConditions = new Vector<WorkflowCondition>();
      for (int c = 0; c < conditionsPerTask; c++) {
        taskConditions.add(
            conditionOf(id + "-task-" + i + "-cond-" + c, "TaskCond " + c));
      }
      task.setPreConditions(taskConditions);
      tasks.add(task);
    }
    workflow.setTasks(tasks);

    List<WorkflowCondition> pre = new Vector<WorkflowCondition>();
    for (int i = 0; i < preConditions; i++) {
      pre.add(conditionOf(id + "-pre-" + i, "Pre " + i));
    }
    workflow.setPreConditions(pre);

    List<WorkflowCondition> post = new Vector<WorkflowCondition>();
    for (int i = 0; i < postConditions; i++) {
      post.add(conditionOf(id + "-post-" + i, "Post " + i));
    }
    workflow.setPostConditions(post);

    WorkflowInstance instance = new WorkflowInstance();
    instance.setParentChildWorkflow(workflow);
    instance.setPriority(Priority.getDefault());
    instance.setState(lifecycle().getDefaultLifecycle()
        .createState("Null", "initial", "created by the property"));
    return instance;
  }

  private static Set<String> instanceIdsOf(List<WorkflowProcessor> processors) {
    Set<String> ids = new LinkedHashSet<String>();
    for (WorkflowProcessor processor : processors) {
      ids.add(processor.getWorkflowInstance().getId());
    }
    return ids;
  }

  private static Generator<String> workflowId() {
    return text().minSize(1).maxSize(6).categories("Ll", "Nd");
  }

  /**
   * Every instance the repository holds that is not done must come back as a
   * processor, and none of the done ones may.
   *
   * <p>This is the queue's whole job. An instance that yields no processor is
   * work the engine will never dispatch; a done instance that yields one is
   * work the engine will run again.
   */
  @HegelTest(testCases = 25)
  void everyLiveInstanceYieldsAProcessorAndNoDoneOneDoes(TestCase tc)
      throws Exception {
    int live = tc.draw(integers().min(0).max(5), "live");
    int done = tc.draw(integers().min(0).max(4), "done");
    String executionType =
        tc.draw(sampledFrom(List.of("sequential", "parallel")), "executionType");

    MemoryWorkflowInstanceRepository instanceRepo =
        new MemoryWorkflowInstanceRepository(20);
    MapWorkflowRepository modelRepo = new MapWorkflowRepository();
    WorkflowProcessorQueue queue =
        new WorkflowProcessorQueue(instanceRepo, lifecycle(), modelRepo);

    Set<String> liveIds = new LinkedHashSet<String>();
    Set<String> doneIds = new LinkedHashSet<String>();

    for (int i = 0; i < live; i++) {
      WorkflowInstance instance =
          compositeInstance("live" + i, executionType, 1, 0, 0, 0);
      instanceRepo.addWorkflowInstance(instance);
      liveIds.add(instance.getId());
    }
    for (int i = 0; i < done; i++) {
      WorkflowInstance instance =
          compositeInstance("done" + i, executionType, 1, 0, 0, 0);
      instance.setState(lifecycle().getDefaultLifecycle()
          .createState("Success", "done", "already finished"));
      instanceRepo.addWorkflowInstance(instance);
      doneIds.add(instance.getId());
    }

    List<WorkflowProcessor> processors = queue.getProcessors();
    assertNotNull(processors, "the queue produced no list at all");
    Set<String> produced = instanceIdsOf(processors);

    tc.note("live=" + liveIds + " done=" + doneIds + " produced=" + produced);

    assertTrue(produced.containsAll(liveIds),
        "the queue did not offer a processor for every live instance: it "
            + "offered " + produced + " for " + liveIds);
    for (String doneId : doneIds) {
      assertTrue(!produced.contains(doneId),
          "the queue offered a processor for the finished instance " + doneId);
    }
  }

  /**
   * An empty repository yields an empty queue rather than null or an
   * exception.
   *
   * <p>A workflow manager that has just started, or one whose work has all
   * finished, sits in exactly this state on every pass of the querier.
   */
  @HegelTest(testCases = 20)
  void anEmptyRepositoryYieldsAnEmptyQueue(TestCase tc) throws Exception {
    int pageSize = tc.draw(integers().min(1).max(20), "pageSize");
    WorkflowProcessorQueue queue = new WorkflowProcessorQueue(
        new MemoryWorkflowInstanceRepository(pageSize), lifecycle(),
        new MapWorkflowRepository());

    List<WorkflowProcessor> processors = queue.getProcessors();
    assertNotNull(processors, "an empty repository produced a null queue");
    assertEquals(0, processors.size(),
        "an empty repository produced " + processors.size() + " processors");
  }

  /**
   * A composite instance must be decomposed into exactly one sub-processor per
   * task, per workflow-level condition and per task-level condition.
   *
   * <p>The engine only ever runs leaves: the composite processor itself does
   * no work, it hands back sub-processors. A task with no sub-processor never
   * runs, and a condition with no sub-processor never gets evaluated, which
   * for a pre-condition means an unguarded task.
   */
  @HegelTest(testCases = 25)
  void aCompositeIsDecomposedIntoOneSubProcessorPerTaskAndCondition(
      TestCase tc) throws Exception {
    String id = tc.draw(workflowId(), "workflowId");
    int taskCount = tc.draw(integers().min(1).max(4), "taskCount");
    int preConditions = tc.draw(integers().min(0).max(3), "preConditions");
    int postConditions = tc.draw(integers().min(0).max(3), "postConditions");
    int conditionsPerTask = tc.draw(integers().min(0).max(2), "conditionsPerTask");
    String executionType =
        tc.draw(sampledFrom(List.of("sequential", "parallel")), "executionType");

    MemoryWorkflowInstanceRepository instanceRepo =
        new MemoryWorkflowInstanceRepository(20);
    MapWorkflowRepository modelRepo = new MapWorkflowRepository();
    WorkflowProcessorQueue queue =
        new WorkflowProcessorQueue(instanceRepo, lifecycle(), modelRepo);

    WorkflowInstance instance = compositeInstance("w" + id, executionType,
        taskCount, preConditions, postConditions, conditionsPerTask);
    instanceRepo.addWorkflowInstance(instance);
    String parentId = instance.getId();

    List<WorkflowProcessor> processors = queue.getProcessors();
    WorkflowProcessor parent = null;
    for (WorkflowProcessor processor : processors) {
      if (parentId.equals(processor.getWorkflowInstance().getId())) {
        parent = processor;
      }
    }
    assertNotNull(parent, "the composite instance produced no processor");

    int expected = taskCount + preConditions + postConditions
        + (taskCount * conditionsPerTask);
    tc.note("expected " + expected + " sub-processors, got "
        + parent.getSubProcessors().size());
    assertEquals(expected, parent.getSubProcessors().size(),
        "the composite was decomposed into the wrong number of sub-processors");

    assertEquals("sequential".equals(executionType),
        parent instanceof SequentialProcessor,
        "a " + executionType + " workflow produced a "
            + parent.getClass().getSimpleName());
  }

  /**
   * Every condition the workflow declares as a pre-condition must be wired
   * onto each of its tasks as a prerequisite, and every task must be wired
   * onto each post-condition.
   *
   * <p>A condition that is not a task's prerequisite is a condition that gates
   * nothing: the task is dispatched alongside it. The queue is the only place
   * this wiring happens, because a task instance is discovered from the
   * repository on its own and its parent is not on the path when the querier
   * decides whether to run it.
   */
  @HegelTest(testCases = 25)
  void conditionsGateTheTasksTheyWereDeclaredAround(TestCase tc)
      throws Exception {
    String id = tc.draw(workflowId(), "workflowId");
    int taskCount = tc.draw(integers().min(1).max(3), "taskCount");
    int preConditions = tc.draw(integers().min(1).max(3), "preConditions");
    int postConditions = tc.draw(integers().min(1).max(2), "postConditions");

    MemoryWorkflowInstanceRepository instanceRepo =
        new MemoryWorkflowInstanceRepository(20);
    MapWorkflowRepository modelRepo = new MapWorkflowRepository();
    WorkflowProcessorQueue queue =
        new WorkflowProcessorQueue(instanceRepo, lifecycle(), modelRepo);

    WorkflowInstance instance = compositeInstance("w" + id, "sequential",
        taskCount, preConditions, postConditions, 0);
    instanceRepo.addWorkflowInstance(instance);
    String parentId = instance.getId();

    WorkflowProcessor parent = null;
    for (WorkflowProcessor processor : queue.getProcessors()) {
      if (parentId.equals(processor.getWorkflowInstance().getId())) {
        parent = processor;
      }
    }
    assertNotNull(parent, "the composite instance produced no processor");

    List<WorkflowProcessor> preProcessors = new ArrayList<WorkflowProcessor>();
    List<WorkflowProcessor> taskProcessors = new ArrayList<WorkflowProcessor>();
    List<WorkflowProcessor> postProcessors = new ArrayList<WorkflowProcessor>();
    for (WorkflowProcessor sub : parent.getSubProcessors()) {
      String subWorkflowId = sub.getWorkflowInstance()
          .getParentChildWorkflow().getId();
      if (subWorkflowId.startsWith("pre-cond-workflow-")) {
        preProcessors.add(sub);
      } else if (subWorkflowId.startsWith("post-cond-workflow-")) {
        postProcessors.add(sub);
      } else if (subWorkflowId.startsWith("task-workflow-")) {
        taskProcessors.add(sub);
      }
    }

    assertEquals(preConditions, preProcessors.size(),
        "the workflow's pre-conditions did not all become sub-processors");
    assertEquals(taskCount, taskProcessors.size(),
        "the workflow's tasks did not all become sub-processors");
    assertEquals(postConditions, postProcessors.size(),
        "the workflow's post-conditions did not all become sub-processors");

    for (WorkflowProcessor task : taskProcessors) {
      assertTrue(task.getPrerequisites().containsAll(preProcessors),
          "a task was left without one of the workflow's pre-conditions "
              + "among its prerequisites, so the condition gates nothing");
    }
    for (WorkflowProcessor post : postProcessors) {
      assertTrue(post.getPrerequisites().containsAll(taskProcessors),
          "a post-condition was left without the tasks among its "
              + "prerequisites, so it can be evaluated before the work it is "
              + "meant to judge has run");
    }
  }

  /**
   * Every sub-instance the queue manufactures must share the parent's
   * metadata, by reference.
   *
   * <p>A workflow's tasks communicate through one shared context: what the
   * caller passed to {@code startWorkflow} has to reach the first task, and
   * what a task writes has to reach the next one. A sub-instance given a
   * context of its own runs against nothing and writes into nothing.
   */
  @HegelTest(testCases = 25)
  void subInstancesShareTheParentsContext(TestCase tc) throws Exception {
    String key = tc.draw(text().minSize(1).maxSize(6).categories("Lu", "Ll"),
        "key");
    String value = tc.draw(text().minSize(1).maxSize(6).categories("Ll", "Nd"),
        "value");
    int taskCount = tc.draw(integers().min(1).max(3), "taskCount");
    int preConditions = tc.draw(integers().min(0).max(2), "preConditions");

    MemoryWorkflowInstanceRepository instanceRepo =
        new MemoryWorkflowInstanceRepository(20);
    MapWorkflowRepository modelRepo = new MapWorkflowRepository();
    WorkflowProcessorQueue queue =
        new WorkflowProcessorQueue(instanceRepo, lifecycle(), modelRepo);

    WorkflowInstance instance =
        compositeInstance("shared", "sequential", taskCount, preConditions, 1, 0);
    Metadata context = new Metadata();
    context.addMetadata(key, value);
    instance.setSharedContext(context);
    instanceRepo.addWorkflowInstance(instance);
    String parentId = instance.getId();

    WorkflowProcessor parent = null;
    for (WorkflowProcessor processor : queue.getProcessors()) {
      if (parentId.equals(processor.getWorkflowInstance().getId())) {
        parent = processor;
      }
    }
    assertNotNull(parent, "the composite instance produced no processor");

    for (WorkflowProcessor sub : parent.getSubProcessors()) {
      Metadata subContext = sub.getWorkflowInstance().getSharedContext();
      assertNotNull(subContext, "a sub-instance was given no context at all");
      assertEquals(value, subContext.getMetadata(key),
          "a sub-instance cannot see what the caller put in the workflow's "
              + "context");
      assertSame(context, subContext,
          "a sub-instance was given a copy of the context rather than the "
              + "context, so nothing it writes will reach its siblings");
    }
  }

  /**
   * Asking the queue twice for the same instance returns the same processor
   * object, not a second one built from scratch.
   *
   * <p>The querier calls this on every pass. A processor rebuilt each time
   * would lose the state the previous pass recorded on it and would present
   * the same work as new for ever.
   */
  @HegelTest(testCases = 25)
  void theSameInstanceAlwaysYieldsTheSameProcessor(TestCase tc)
      throws Exception {
    int count = tc.draw(integers().min(1).max(4), "count");

    MemoryWorkflowInstanceRepository instanceRepo =
        new MemoryWorkflowInstanceRepository(20);
    MapWorkflowRepository modelRepo = new MapWorkflowRepository();
    WorkflowProcessorQueue queue =
        new WorkflowProcessorQueue(instanceRepo, lifecycle(), modelRepo);

    for (int i = 0; i < count; i++) {
      instanceRepo.addWorkflowInstance(
          compositeInstance("cached" + i, "sequential", 1, 0, 0, 0));
    }

    Map<String, WorkflowProcessor> first =
        new LinkedHashMap<String, WorkflowProcessor>();
    for (WorkflowProcessor processor : queue.getProcessors()) {
      first.put(processor.getWorkflowInstance().getId(), processor);
    }

    for (WorkflowProcessor processor : queue.getProcessors()) {
      String id = processor.getWorkflowInstance().getId();
      if (first.containsKey(id)) {
        assertSame(first.get(id), processor,
            "instance " + id + " was turned into a second, different "
                + "processor on the next pass of the queue");
      }
    }
  }

  /**
   * A workflow whose graph names no execution type is treated as sequential by
   * default, and as parallel when the deployment says its packaged workflows
   * hold independent tasks.
   *
   * <p>A repository with no notion of graphs hands the engine a workflow with
   * no execution type, and the engine still has to decide how to run it.
   * Sequential is the safe reading of an ordered task list; the property
   * pins both readings so that the switch cannot quietly stop working.
   */
  @HegelTest(testCases = 20)
  void anUnspecifiedExecutionTypeFollowsTheConfiguredDefault(TestCase tc)
      throws Exception {
    boolean parallel = tc.draw(booleans(), "parallel");
    String executionType = tc.draw(sampledFrom(List.of("", "none")), "declared");

    String previous = System.getProperty(PARALLEL_PROCESSORS_PROPERTY);
    System.setProperty(PARALLEL_PROCESSORS_PROPERTY, String.valueOf(parallel));
    try {
      MemoryWorkflowInstanceRepository instanceRepo =
          new MemoryWorkflowInstanceRepository(20);
      MapWorkflowRepository modelRepo = new MapWorkflowRepository();
      WorkflowProcessorQueue queue =
          new WorkflowProcessorQueue(instanceRepo, lifecycle(), modelRepo);

      WorkflowInstance instance = compositeInstance("default",
          "none".equals(executionType) ? null : "", 2, 0, 0, 0);
      instanceRepo.addWorkflowInstance(instance);
      String parentId = instance.getId();

      WorkflowProcessor parent = null;
      for (WorkflowProcessor processor : queue.getProcessors()) {
        if (parentId.equals(processor.getWorkflowInstance().getId())) {
          parent = processor;
        }
      }
      assertNotNull(parent,
          "a workflow with no declared execution type produced no processor");
      if (parallel) {
        assertTrue(parent instanceof ParallelProcessor,
            "with parallel processors configured the workflow became a "
                + parent.getClass().getSimpleName());
      } else {
        assertTrue(parent instanceof SequentialProcessor,
            "with the default configuration the workflow became a "
                + parent.getClass().getSimpleName());
      }
    } finally {
      if (previous == null) {
        System.clearProperty(PARALLEL_PROCESSORS_PROPERTY);
      } else {
        System.setProperty(PARALLEL_PROCESSORS_PROPERTY, previous);
      }
    }
  }

  /**
   * Every sub-workflow and every condition task the queue manufactures must be
   * filed in the model repository.
   *
   * <p>The sub-instances the queue persists refer to models by identifier;
   * anything reading them back — the monitor, a restarted engine — resolves
   * those identifiers through the model repository, and one that was never
   * filed resolves to nothing.
   */
  @HegelTest(testCases = 25)
  void manufacturedModelsAreFiledInTheModelRepository(TestCase tc)
      throws Exception {
    int taskCount = tc.draw(integers().min(1).max(3), "taskCount");
    int preConditions = tc.draw(integers().min(0).max(2), "preConditions");
    int postConditions = tc.draw(integers().min(0).max(2), "postConditions");

    MemoryWorkflowInstanceRepository instanceRepo =
        new MemoryWorkflowInstanceRepository(20);
    MapWorkflowRepository modelRepo = new MapWorkflowRepository();
    WorkflowProcessorQueue queue =
        new WorkflowProcessorQueue(instanceRepo, lifecycle(), modelRepo);

    instanceRepo.addWorkflowInstance(compositeInstance("filed", "sequential",
        taskCount, preConditions, postConditions, 0));
    queue.getProcessors();

    assertTrue(modelRepo.workflowCount() > 0,
        "the queue manufactured sub-workflows but filed none of them");
    assertEquals(preConditions + postConditions, modelRepo.taskCount(),
        "the queue did not file one condition task per condition");
  }

  /**
   * Every sub-instance the queue manufactures is persisted, and carries an
   * identifier.
   *
   * <p>The engine finds work by asking the instance repository, not by walking
   * down from a parent, so a sub-instance that was never persisted is work
   * that will never be dispatched however correctly it was wired.
   */
  @HegelTest(testCases = 25)
  void everyManufacturedSubInstanceIsPersisted(TestCase tc) throws Exception {
    int taskCount = tc.draw(integers().min(1).max(3), "taskCount");
    int preConditions = tc.draw(integers().min(0).max(2), "preConditions");

    MemoryWorkflowInstanceRepository instanceRepo =
        new MemoryWorkflowInstanceRepository(20);
    MapWorkflowRepository modelRepo = new MapWorkflowRepository();
    WorkflowProcessorQueue queue =
        new WorkflowProcessorQueue(instanceRepo, lifecycle(), modelRepo);

    WorkflowInstance instance =
        compositeInstance("persist", "sequential", taskCount, preConditions, 0, 0);
    instanceRepo.addWorkflowInstance(instance);
    String parentId = instance.getId();

    WorkflowProcessor parent = null;
    for (WorkflowProcessor processor : queue.getProcessors()) {
      if (parentId.equals(processor.getWorkflowInstance().getId())) {
        parent = processor;
      }
    }
    assertNotNull(parent, "the composite instance produced no processor");

    for (WorkflowProcessor sub : parent.getSubProcessors()) {
      String subId = sub.getWorkflowInstance().getId();
      assertNotNull(subId, "a manufactured sub-instance has no identifier");
      assertNotNull(instanceRepo.getWorkflowInstanceById(subId),
          "sub-instance " + subId + " was never written to the instance "
              + "repository, so nothing will ever run it");
    }
  }

  /**
   * A repository that cannot be read leaves the queue reporting nothing rather
   * than throwing.
   *
   * <p>The querier calls this from its own loop and has no handler of its
   * own; an exception escaping here used to end that thread and with it all
   * dispatching. The queue answers {@code null}, which is a poor way to say
   * "I could not tell you" but is what the caller is written against, so it is
   * stated here rather than left to be discovered.
   */
  @HegelTest(testCases = 20)
  void anUnreadableRepositoryIsReportedRatherThanThrown(TestCase tc)
      throws Exception {
    int ignored = tc.draw(integers().min(0).max(3), "unused");
    WorkflowInstanceRepository broken = new FailingInstanceRepository();
    WorkflowProcessorQueue queue =
        new WorkflowProcessorQueue(broken, lifecycle(), new MapWorkflowRepository());

    assertNull(queue.getProcessors(),
        "a failing instance repository did not produce the null the querier "
            + "is written to expect");
  }

  /**
   * Persisting an instance that has never been stored files it; persisting one
   * that has been stored updates it in place.
   *
   * <p>{@code persist} is how every state change the engine makes reaches
   * disk, and it decides between an insert and an update purely on whether the
   * instance already carries an identifier. Getting that wrong either loses
   * the change or duplicates the instance.
   */
  @HegelTest(testCases = 25)
  void persistingAddsOnceAndThenUpdatesInPlace(TestCase tc) throws Exception {
    int updates = tc.draw(integers().min(1).max(4), "updates");

    MemoryWorkflowInstanceRepository instanceRepo =
        new MemoryWorkflowInstanceRepository(20);
    WorkflowProcessorQueue queue = new WorkflowProcessorQueue(instanceRepo,
        lifecycle(), new MapWorkflowRepository());

    WorkflowInstance instance =
        compositeInstance("persisted", "sequential", 1, 0, 0, 0);
    queue.persist(instance);
    String id = instance.getId();
    assertNotNull(id, "persisting a new instance left it without an id");
    assertEquals(1, instanceRepo.getNumWorkflowInstances(),
        "persisting one new instance stored a different number of instances");

    for (int i = 0; i < updates; i++) {
      instance.setState(lifecycle().getDefaultLifecycle()
          .createState("Queued", "waiting", "update " + i));
      queue.persist(instance);
      assertEquals(id, instance.getId(),
          "persisting an existing instance changed its identifier");
      assertEquals(1, instanceRepo.getNumWorkflowInstances(),
          "persisting an existing instance stored a second copy of it");
    }
    assertEquals("Queued",
        instanceRepo.getWorkflowInstanceById(id).getState().getName(),
        "the last update never reached the repository");
  }
}
