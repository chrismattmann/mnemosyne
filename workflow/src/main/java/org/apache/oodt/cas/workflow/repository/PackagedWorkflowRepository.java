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
package org.apache.oodt.cas.workflow.repository;

//OODT imports

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.metadata.exceptions.CasMetadataException;
import org.apache.oodt.cas.workflow.examples.BranchRedirector;
import org.apache.oodt.cas.workflow.examples.NoOpTask;
import org.apache.oodt.cas.workflow.exceptions.WorkflowException;
import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowCondition;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.WorkflowTaskConfiguration;
import org.apache.oodt.cas.workflow.structs.exceptions.RepositoryException;
import org.apache.oodt.cas.workflow.util.XmlStructFactory;
import org.apache.oodt.commons.exceptions.CommonsException;
import org.apache.oodt.commons.xml.XMLUtils;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

//JDK imports

/**
 *
 * Loads Workflow2 (WEngine) style workflow XML files.
 *
 * <h2>What it is for</h2>
 *
 * The older {@link XMLWorkflowRepository} reads a fixed set of files -
 * <code>tasks.xml</code>, <code>conditions.xml</code>, <code>events.xml</code>
 * - in which a workflow is a flat list of tasks. This one reads any number of
 * files in a single dialect where workflows nest: a workflow may contain
 * workflows, each declared sequential or parallel, to any depth. That nesting
 * is what the queue-based engine was built to run.
 *
 * <p>
 * It is selected by setting the workflow repository factory to
 * {@link PackagedWorkflowRepositoryFactory} and pointing
 * <code>org.apache.oodt.cas.workflow.wengine.packagedRepo.dir.path</code> at a
 * directory. Every file in that directory is parsed; there are no reserved
 * filenames.
 * </p>
 *
 * <h2>The dialect</h2>
 *
 * Each file is rooted at <code>cas:workflows</code> and holds the elements
 * listed in {@link Graph#graphElementNames}: <code>sequential</code>,
 * <code>parallel</code>, <code>task</code>, <code>condition</code> and
 * <code>workflow</code>. An
 * element carrying an <code>id</code> is a definition; one carrying an
 * <code>id-ref</code> is a reference to a definition, which may live in any of
 * the parsed files. Definitions can therefore be written once and reused, which
 * is most of the point of the dialect.
 *
 * <p>
 * A sub-workflow may be written either way round. <code>&lt;sequential&gt;</code>
 * and <code>&lt;parallel&gt;</code> name the execution strategy in the tag;
 * <code>&lt;workflow execution="sequential"&gt;</code> names it in an attribute.
 * Both produce a {@link org.apache.oodt.cas.workflow.structs.ParentChildWorkflow},
 * and either may nest inside another. Only the generic form needs an element
 * name that is not itself a strategy, which is why the names this class scans
 * for and the strategies {@link Graph} accepts are two separate lists.
 * </p>
 *
 * <p>
 * One attribute is <em>not</em> honoured: the <code>execution</code> on a
 * <code>&lt;conditions&gt;</code> block. That element never becomes a
 * {@link Graph} -- this class reads its <code>type</code> directly and descends
 * to the <code>condition</code> children -- so
 * <code>&lt;conditions execution="parallel"&gt;</code> documents intent to a
 * reader without changing what runs.
 * </p>
 *
 * <h2>How a file becomes a model</h2>
 *
 * {@link #init()} makes three passes over every file, in order, because each
 * depends on the one before it:
 *
 * <ol>
 * <li>{@link #loadConfiguration} gathers <code>configuration</code> blocks.
 * A block may name itself and be inherited elsewhere through
 * <code>extends</code>, and <code>p:</code>-prefixed attributes on any element
 * fold into the same static metadata.</li>
 * <li>{@link #loadTaskAndConditionDefinitions} registers the standalone
 * <code>task</code> and <code>condition</code> definitions, so that an
 * <code>id-ref</code> encountered later resolves.</li>
 * <li>{@link #loadGraphs} walks the XML recursively, building a {@link Graph}
 * per element and linking it to its parent. {@link
 * #expandWorkflowTasksAndConditions} then turns each Graph into the domain
 * object its execution type calls for, and attaches it to the parent: a
 * workflow into {@link #workflows}, a condition onto the enclosing workflow or
 * task, a task onto the enclosing workflow.</li>
 * </ol>
 *
 * <h2>Why this class is more than a parser</h2>
 *
 * The engine that consumes these models runs a flat workflow of tasks and
 * understands conditions only on tasks. The XML expresses more than that, so
 * the last two steps rewrite the model into something the engine can execute.
 * This is the part worth knowing about, because the workflows handed out are
 * deliberately not shaped like the file that was read.
 *
 * <ul>
 * <li><b>Every workflow is an event.</b> {@link #computeEvents()} maps each
 * workflow's id to itself, so a workflow is started by sending an event named
 * after it; there is no separate event declaration in this dialect. The
 * generated wrappers below are registered the same way when they are built,
 * so everything {@link #getWorkflows()} lists can be started.</li>
 * <li><b>A nested workflow becomes a redirect.</b> Inside a
 * <code>sequential</code> parent, a child workflow is replaced by a generated
 * {@link BranchRedirector} task carrying the child's id as
 * <code>eventName</code>. Reaching that task fires the event, which starts the
 * child. Nesting is therefore flattened into a chain of events rather than
 * executed as a tree.</li>
 * <li><b>A parallel workflow does not survive as a workflow.</b> It is removed
 * from {@link #workflows} entirely, and its children are registered under its
 * event instead, so firing the event starts all of them at once. A child that
 * is a bare task is first wrapped in a generated single-task workflow named
 * <code>parallel-&lt;uuid&gt;</code>. Asking for a parallel workflow by id
 * returns nothing; asking for the workflows of its event returns its
 * children.</li>
 * <li><b>Workflow-level conditions are hoisted into a task.</b>
 * {@link #computeWorkflowConditions()} inserts a generated no-op task at
 * position 0 carrying them, since the engine only enforces conditions attached
 * to a task.</li>
 * </ul>
 *
 * <p>
 * All four rewrites happen during construction, so a model is fully expanded
 * before any caller sees it, and the generated ids (<code>redirector-</code>,
 * <code>parallel-</code>, and the conditions task) appear in anything that
 * reports on a running workflow.
 * </p>
 *
 * @see XMLWorkflowRepository
 * @see Graph
 * @see org.apache.oodt.cas.workflow.engine.PrioritizedQueueBasedWorkflowEngine
 *
 * @author mattmann
 * @author bfoster
 */
public class PackagedWorkflowRepository implements WorkflowRepository {

  private List<File> files;

  private Map<String, ParentChildWorkflow> workflows;

  private Map<String, WorkflowCondition> conditions;

  private Map<String, WorkflowTask> tasks;

  private Map<String, Metadata> globalConfGroups;

  private Map<String, List<ParentChildWorkflow>> eventWorkflowMap;

  private static final Logger LOG = Logger
      .getLogger(PackagedWorkflowRepository.class.getName());

  public PackagedWorkflowRepository(List<File> files)
      throws InstantiationException {
    this.files = files;
    try {
      this.init();
    } catch (Exception e) {
      LOG.log(Level.SEVERE, e.getMessage());
      throw new InstantiationException(e.getMessage());
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.repository.WorkflowRepository#getWorkflowByName
   * (java.lang.String)
   */
  @Override
  public Workflow getWorkflowByName(String workflowName)
      throws RepositoryException {

    for (Workflow w : this.workflows.values()) {
      if (w.getName().equals(workflowName)) {
        return w;
      }
    }

    return null;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.repository.WorkflowRepository#getWorkflowById
   * (java.lang.String)
   */
  @Override
  public Workflow getWorkflowById(String workflowId) throws RepositoryException {
    return workflows.get(workflowId);
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.repository.WorkflowRepository#getWorkflows()
   */
  @Override
  public List getWorkflows() throws RepositoryException {
    List<Workflow> workflows = new Vector<Workflow>();
    workflows.addAll(this.workflows.values());
    return workflows;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.repository.WorkflowRepository#getTasksByWorkflowId
   * (java.lang.String)
   */
  @Override
  public List getTasksByWorkflowId(String workflowId)
      throws RepositoryException {
    Workflow w = this.getWorkflowById(workflowId);
    return w.getTasks();
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.oodt.cas.workflow.repository.WorkflowRepository#
   * getTasksByWorkflowName(java.lang.String)
   */
  @Override
  public List getTasksByWorkflowName(String workflowName)
      throws RepositoryException {
    Workflow w = this.getWorkflowByName(workflowName);
    if (w != null) {
      return w.getTasks();
    } else {
      return Collections.emptyList();
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.repository.WorkflowRepository#getWorkflowsForEvent
   * (java.lang.String)
   */
  @Override
  public List getWorkflowsForEvent(String eventName) throws RepositoryException {
    List<ParentChildWorkflow> workflows = this.eventWorkflowMap.get(eventName);
    if (workflows != null && workflows.size() > 0) {
      return workflows;
    } else {
      return Collections.emptyList();
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.oodt.cas.workflow.repository.WorkflowRepository#
   * getConditionsByTaskName(java.lang.String)
   */
  @Override
  public List getConditionsByTaskName(String taskName)
      throws RepositoryException {

    for (WorkflowTask task : this.tasks.values()) {
      if (task.getTaskName().equals(taskName)) {
        return task.getConditions();
      }
    }

    return Collections.emptyList();
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.oodt.cas.workflow.repository.WorkflowRepository#
   * getConditionsByTaskId(java.lang.String)
   */
  @Override
  public List getConditionsByTaskId(String taskId) throws RepositoryException {
    if (this.tasks.get(taskId) != null) {
      return this.tasks.get(taskId).getConditions();
    } else {
      return Collections.emptyList();
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.oodt.cas.workflow.repository.WorkflowRepository#
   * getConfigurationByTaskId(java.lang.String)
   */
  @Override
  public WorkflowTaskConfiguration getConfigurationByTaskId(String taskId)
      throws RepositoryException {
    return convertToTaskConfiguration(this.globalConfGroups.get(taskId));
  }

  /*
   * Both of these used to reach getAllKeys() on a null Metadata. An unknown
   * task id misses the map, and a task that declares no <configuration>
   * block never puts one there in the first place -- which is most of the
   * tasks in the shipped examples, so this was reachable from a policy
   * directory that is entirely valid. An absent configuration is an empty
   * configuration, not a failure.
   */

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.repository.WorkflowRepository#getWorkflowTaskById
   * (java.lang.String)
   */
  @Override
  public WorkflowTask getWorkflowTaskById(String taskId)
      throws RepositoryException {
    return this.tasks.get(taskId);
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.oodt.cas.workflow.repository.WorkflowRepository#
   * getWorkflowConditionById(java.lang.String)
   */
  @Override
  public WorkflowCondition getWorkflowConditionById(String conditionId)
      throws RepositoryException {
    return this.conditions.get(conditionId);
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.repository.WorkflowRepository#getRegisteredEvents
   * ()
   */
  @Override
  public List getRegisteredEvents() throws RepositoryException {
    return Arrays.asList(this.eventWorkflowMap.keySet().toArray());
  }
  
  /* (non-Javadoc)
   * @see org.apache.oodt.cas.workflow.repository.WorkflowRepository#addTask(org.apache.oodt.cas.workflow.structs.WorkflowTask)
   */
  @Override
  public String addTask(WorkflowTask task) throws RepositoryException {
    // check its conditions
    if(task.getPreConditions() != null && task.getPreConditions().size() > 0){
      for(WorkflowCondition cond: task.getPreConditions()){
        if(!this.conditions.containsKey(cond.getConditionId())){
          throw new RepositoryException("Reference in new task: ["+task.getTaskName()+"] to undefined pre condition ith id: ["+cond.getConditionId()+"]");            
        }          
      }
      
      for(WorkflowCondition cond: task.getPostConditions()){
        if(!this.conditions.containsKey(cond.getConditionId())){
          throw new RepositoryException("Reference in new task: ["+task.getTaskName()+"] to undefined post condition ith id: ["+cond.getConditionId()+"]");            
        }              
      }
    }
    
      String taskId = task.getTaskId() != null ? 
        task.getTaskId():UUID.randomUUID().toString();
      this.tasks.put(taskId, task);
      return taskId;
  }  

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.repository.WorkflowRepository#addWorkflow(
   * org.apache.oodt.cas.workflow.structs.Workflow)
   */
  @Override
  public String addWorkflow(Workflow workflow) throws RepositoryException {
    // first check to see that its tasks are all present
    if (workflow.getTasks() == null || (workflow.getTasks().size() == 0)) {
      throw new RepositoryException("Attempt to define a new worklfow: ["
          + workflow.getName() + "] with no tasks.");
    }

    for (WorkflowTask task : (List<WorkflowTask>) workflow.getTasks()) {
      if (!this.tasks.containsKey(task.getTaskId())) {
        throw new RepositoryException("Reference in new workflow: ["
            + workflow.getName() + "] to undefined task with id: ["
            + task.getTaskId() + "]");
      }

      // check its conditions
      if (task.getConditions() != null && task.getConditions().size() > 0) {
        for (WorkflowCondition cond : (List<WorkflowCondition>) task
            .getConditions()) {
          if (!this.conditions.containsKey(cond.getConditionId())) {
            throw new RepositoryException("Reference in new workflow: ["
                + workflow.getName() + "] to undefined condition ith id: ["
                + cond.getConditionId() + "]");
          }
        }
      }
    }

    // recast it as a parent/child workflow
    String workflowId = workflow.getId();
	if (workflowId == null || (workflowId.equals(""))) {
		// generate its ID
		workflowId = UUID.randomUUID().toString();
		workflow.setId(workflowId);
	}
      
    ParentChildWorkflow pcw;
    if(workflow instanceof ParentChildWorkflow) {
        pcw = (ParentChildWorkflow) workflow;
    }
    else {
        Graph graph = new Graph();
        graph.setExecutionType("sequential");
        pcw = new ParentChildWorkflow(graph);
        pcw.setName(workflow.getName());
        pcw.setTasks(workflow.getTasks());
        pcw.setId(workflow.getId());
    }
    this.workflows.put(pcw.getId(), pcw);
    this.eventWorkflowMap.put(workflowId, Collections.singletonList(pcw));

    // generate its ID
    return workflowId;
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.oodt.cas.workflow.repository.WorkflowRepository#
   * getConditionsByWorkflowId(java.lang.String)
   */
  @Override
  public List<WorkflowCondition> getConditionsByWorkflowId(String workflowId)
      throws RepositoryException {
    if (!this.workflows.containsKey(workflowId)) {
      throw new RepositoryException(
          "Attempt to obtain conditions for a workflow: " + "[" + workflowId
          + "] that does not exist!");
    }

    return this.workflows.get(workflowId).getConditions();
  }
  

  /* (non-Javadoc)
   * @see org.apache.oodt.cas.workflow.repository.WorkflowRepository#getTaskById(java.lang.String)
   */
  @Override
  public WorkflowTask getTaskById(String taskId) throws RepositoryException {
    return this.tasks.get(taskId);
  }  

  private void init() throws RepositoryException {
    this.workflows = new ConcurrentHashMap<String, ParentChildWorkflow>();
    this.tasks = new ConcurrentHashMap<String, WorkflowTask>();
    this.conditions = new ConcurrentHashMap<String, WorkflowCondition>();
    this.eventWorkflowMap = new ConcurrentHashMap<String, List<ParentChildWorkflow>>();
    this.globalConfGroups = new ConcurrentHashMap<String, Metadata>();
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder parser;

    try {
      parser = factory.newDocumentBuilder();
      List<Element> rootElements = new Vector<Element>();
      for (File file : files) {
        rootElements.add(parser.parse(file).getDocumentElement());
      }
      for (Element root : rootElements) {
        Metadata staticMetadata = new Metadata();
        loadConfiguration(rootElements, root, staticMetadata);
        loadTaskAndConditionDefinitions(rootElements, root, staticMetadata);
        loadGraphs(rootElements, root, new Graph(), staticMetadata);
      }

      // Once, over everything that was read, rather than once per file. Both
      // of these work on the accumulated maps, not on the file just parsed,
      // so running them inside the loop meant re-running them over workflows
      // that had already been rewritten. That was not merely wasteful: the
      // second pass cleared a generated wrapper's task list and rebuilt it
      // from a graph that has no children, emptying the workflow and losing
      // the task. It took two files in the policy directory to happen, which
      // is the ordinary case, since the repository is pointed at a directory.
      computeEvents();
      computeWorkflowConditions();
    } catch (Exception e) {
      LOG.log(Level.SEVERE, e.getMessage());
      throw new RepositoryException(e.getMessage());
    }
  }

  /**
   * Formerly moved a workflow's conditions into a generated no-op task placed
   * first, because the engine enforced conditions on tasks and not on
   * workflows.
   *
   * The engine now gates a workflow's tasks on its conditions directly, so the
   * generated task is not merely redundant: the same condition is evaluated
   * once as the workflow's and again as the generated task's, which for a
   * condition that queries a catalogue or waits on a file is real work done
   * twice, and shows up in the ordering as the same condition recorded
   * repeatedly before anything else runs.
   *
   * Kept as a method rather than deleted at the call site so the history of
   * why the generated task existed stays attached to the thing it produced.
   */
  private void computeWorkflowConditions() {
    // Nothing to do. Conditions stay on the workflow that declared them.
  }

  private void computeEvents() throws WorkflowException {
    List<ParentChildWorkflow> workflows = new Vector<ParentChildWorkflow>();
    for (ParentChildWorkflow w : this.workflows.values()) {
      workflows.add(w);

    }
    for (ParentChildWorkflow workflow : workflows) {

      // event for each workflow id
      List<ParentChildWorkflow> wList = new Vector<ParentChildWorkflow>();
      wList.add(workflow);
      this.eventWorkflowMap.put(workflow.getId(), wList);

      // clear its tasks, we are going to re-add them back
      workflow.getTasks().clear();
      List<Graph> children = workflow.getGraph().getChildren();
      if (workflow.getGraph().getExecutionType().equals("sequential")) {
        for (Graph child : children) {
          if (child.getWorkflow() != null) {
            workflow.getTasks().add(
                generateRedirector(child.getWorkflow().getId()));
          } else if (child.getTask() != null) {
            workflow.getTasks().add(child.getTask());
          }
        }
      } else if (workflow.getGraph().getExecutionType().equals("parallel")) {
        // clear it as a workflow from the list
        // to begin with
        this.eventWorkflowMap.get(workflow.getId()).clear();
        this.workflows.remove(workflow.getId());
        for (Graph child : children) {
          if (child.getWorkflow() != null) {
            // add child workflow to the event kickoff for this id
            this.eventWorkflowMap.get(workflow.getId())
                .add(child.getWorkflow());
          } else if (child.getTask() != null) {
            // add a new dynamic workflow
            // with just this task
            ParentChildWorkflow w = getDynamicWorkflow(child.getTask());
            this.eventWorkflowMap.get(workflow.getId()).add(w);
          }
        }
      } else {
        throw new WorkflowException("Unsupported execution type: ["
                                    + workflow.getGraph().getExecutionType() + "]");
      }
    }
  }

  private void loadTaskAndConditionDefinitions(List<Element> rootElements,
      Element rootElem, Metadata staticMetadata)
      throws CommonsException, CasMetadataException, WorkflowException, ParseException {

    List<Element> conditionBlocks = this.getChildrenByTagName(rootElem,
        "condition");
    List<Element> taskBlocks = this.getChildrenByTagName(rootElem, "task");

    if (conditionBlocks != null && conditionBlocks.size() > 0) {
      LOG.log(Level.FINER, "Loading: [" + conditionBlocks.size()
          + "] conditions from: ["
          + rootElem.getOwnerDocument().getDocumentURI() + "]");

      for (Element condElem : conditionBlocks) {
        loadGraphs(rootElements, condElem, new Graph(), staticMetadata);
      }

    }

    if (taskBlocks != null && taskBlocks.size() > 0) {
      LOG.log(Level.FINER, "Loading: [" + taskBlocks.size() + "] tasks from: ["
          + rootElem.getOwnerDocument().getDocumentURI() + "]");
      for (Element taskElem : taskBlocks) {
        loadGraphs(rootElements, taskElem, new Graph(), staticMetadata);
      }
    }
  }

  private void loadGraphs(List<Element> rootElements, Element graphElem,
      Graph parent, Metadata staticMetadata)
      throws CommonsException, CasMetadataException, WorkflowException,
      ParseException {
    loadGraphs(rootElements, graphElem, parent, staticMetadata, null);
  }

  /**
   * @param conditionType
   *          Which phase a condition element belongs to, "pre" or "post", as
   *          declared on the enclosing conditions block. Null for anything
   *          that is not a condition, and treated as "pre" when a conditions
   *          block does not say, which is how every file written before the
   *          attribute was honoured reads.
   */
  private void loadGraphs(List<Element> rootElements, Element graphElem,
      Graph parent, Metadata staticMetadata, String conditionType)
      throws CommonsException, CasMetadataException, WorkflowException, ParseException {

    LOG.log(Level.FINEST, "Visiting node: [" + graphElem.getNodeName() + "]");
    loadConfiguration(rootElements, graphElem, staticMetadata);
    Graph graph = !graphElem.getNodeName().equals("cas:workflows") ? new Graph(
        graphElem, staticMetadata) : new Graph();
    parent.getChildren().add(graph);
    graph.setParent(parent);
    if (!graphElem.getNodeName().equals("cas:workflows")) {
      expandWorkflowTasksAndConditions(graph, staticMetadata, conditionType);
    }

    // Scanning by processorIds meant <workflow> was never looked for, so a
    // sub-workflow written in the generic form could not be reached at all --
    // and expandWorkflowTasksAndConditions below, which has always handled
    // "workflow" alongside "sequential" and "parallel", was unreachable with it.
    for (String processorType : Graph.graphElementNames) {
      LOG.log(Level.FINE, "Scanning for: [" + processorType + "] nodes");
      List<Element> procTypeBlocks = this.getChildrenByTagName(graphElem,
          processorType);
      if (procTypeBlocks != null && procTypeBlocks.size() > 0) {
        LOG.log(Level.FINE, "Found: [" + procTypeBlocks.size() + "] ["
            + processorType + "] processor types");
        for (Element procTypeBlock : procTypeBlocks) {
          loadGraphs(rootElements, procTypeBlock, graph, staticMetadata, null);
        }
      } else {
        if (processorType.equals("condition")) {
          // Every conditions block, not just the first. Reading one meant a
          // workflow declaring pre and post conditions silently lost one of
          // them, and which one depended on the order they were written in.
          for (Element conditionsElem : this.getChildrenByTagName(graphElem,
              "conditions")) {
            String condType = conditionsElem.getAttribute("type");
            // The execution attribute has been in the dialect and in the
            // shipped examples all along, and nothing read it, so a block
            // asking for its conditions in parallel got them one at a time.
            String condExecution = conditionsElem.getAttribute("execution");
            List<Element> procTypeBlockNodes = this.getChildrenByTagName(
                conditionsElem, "condition");
            if (procTypeBlockNodes != null && procTypeBlockNodes.size() > 0) {
              LOG.log(Level.FINE, "Found: [" + procTypeBlockNodes.size()
                  + "] linked [" + (condType == null || condType.equals("")
                      ? "pre" : condType) + "] condition definitions");
              recordConditionExecutionType(graph, condType, condExecution);
              for (Element procTypeBlockNode : procTypeBlockNodes) {
                loadGraphs(rootElements, procTypeBlockNode, graph,
                    staticMetadata, condType);
              }
            }
          }
        }
      }
    }


  }

  private void loadConfiguration(List<Element> rootElements, Node workflowNode,
      Metadata staticMetadata) throws ParseException, CommonsException, CasMetadataException, WorkflowException {
    NodeList children = workflowNode.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node curChild = children.item(i);

      if (curChild.getNodeName().equals("configuration")) {
        Metadata curMetadata = new Metadata();
        if (!((Element) curChild).getAttribute("extends").equals("")) {
          for (String extension : ((Element) curChild).getAttribute("extends")
                                                      .split(",")) {
            curMetadata
                .replaceMetadata(globalConfGroups.containsKey(extension) ? globalConfGroups
                    .get(extension) : this.loadConfGroup(rootElements,
                    extension, globalConfGroups));
          }
        }
        curMetadata.replaceMetadata(XmlStructFactory
            .getConfigurationAsMetadata(curChild));
        NamedNodeMap attrMap = curChild.getAttributes();
        String configName = null;
        for (int j = 0; j < attrMap.getLength(); j++) {
          Attr attr = (Attr) attrMap.item(j);
          if (attr.getName().equals("name")) {
            configName = attr.getValue();
          }
        }

        if (configName == null || (configName.equals(""))) {
          NamedNodeMap workflowNodeAttrs = workflowNode.getAttributes();
          for (int j = 0; j < workflowNodeAttrs.getLength(); j++) {
            Attr attr = (Attr) workflowNodeAttrs.item(j);
            if (attr.getName().equals("id")) {
              configName = attr.getValue();
            }
          }
        }

        this.globalConfGroups.put(configName, curMetadata);
        staticMetadata.replaceMetadata(curMetadata);
      }
    }
  }

  private Metadata loadConfGroup(List<Element> rootElements, String group,
      Map<String, Metadata> globalConfGroups)
      throws ParseException, CommonsException, CasMetadataException, WorkflowException {
    for (final Element rootElement : rootElements) {
      NodeList nodes = rootElement.getElementsByTagName("configuration");
      for (int i = 0; i < nodes.getLength(); i++) {
        Node node = nodes.item(i);
        String name = ((Element) node).getAttribute("name");
        if (name.equals(group)) {
          return XmlStructFactory.getConfigurationAsMetadata(node);
        }
      }
    }
    throw new WorkflowException("Configuration group '" + group + "' not defined!");
  }

  private void expandWorkflowTasksAndConditions(Graph graph,
      Metadata staticMetadata, String conditionType) {
    boolean post = "post".equalsIgnoreCase(conditionType);
    if (graph.getExecutionType().equals("workflow")
        || graph.getExecutionType().equals("sequential")
        || graph.getExecutionType().equals("parallel")) {
      ParentChildWorkflow workflow = new ParentChildWorkflow(graph);
      workflow.setId(graph.getModelId());
      workflow.setName(graph.getModelName());
      graph.setWorkflow(workflow);
      if (graph.getParent() == null || (graph.getParent().getWorkflow() == null)) {
        LOG.log(Level.FINEST, "Workflow: [" + graph.getModelId()
            + "] has no parent: it's a top-level workflow");
      }

      if (workflow.getName() == null || (workflow.getName().equals(""))) {
        workflow.setName(graph.getExecutionType() + "-" + workflow.getId());
      }
      this.workflows.put(graph.getModelId(), workflow);
    } else if (graph.getExecutionType().equals("condition")) {
      WorkflowCondition cond;

      if (graph.getModelIdRef() != null && !graph.getModelIdRef().equals("")) {
        cond = this.conditions.get(graph.getModelIdRef());
      } else {
        cond = new WorkflowCondition();
        cond.setConditionId(graph.getModelId());
        cond.setConditionName(graph.getModelName());
        cond.setConditionInstanceClassName(graph.getClazz());
        cond.setTimeoutSeconds(graph.getTimeout());
        cond.setOptional(graph.isOptional());
        cond.setCondConfig(convertToConditionConfiguration(staticMetadata));

        if (cond.getConditionName() == null || (cond.getConditionName()
                                                    .equals(""))) {
          cond.setConditionName(cond.getConditionId());
        }
        this.conditions.put(graph.getModelId(), cond);

      }

      graph.setCond(cond);
      if (graph.getParent() != null) {
        if (graph.getParent().getWorkflow() != null) {
          LOG.log(Level.FINEST, "Adding condition: [" + cond.getConditionName()
              + "] to parent workflow: ["
              + graph.getParent().getWorkflow().getName() + "]");
          // The type attribute has been in the dialect and in the shipped
          // examples all along; nothing read it, so every condition became a
          // precondition and a post-condition could not be expressed.
          if (post) {
            graph.getParent().getWorkflow().getPostConditions().add(cond);
          } else {
            graph.getParent().getWorkflow().getPreConditions().add(cond);
          }
        } else if (graph.getParent().getTask() != null) {
          // getPreConditions, not getConditions. WorkflowTask.getConditions
          // builds a fresh list from the pre and post lists and returns it, so
          // adding to it added to a temporary that was discarded on the next
          // line: no task has ever carried the conditions written on it.
          // Workflow.getConditions returns its real list, which is why a
          // condition on a workflow attached and one on a task did not.
          if (post) {
            graph.getParent().getTask().getPostConditions().add(cond);
          } else {
            graph.getParent().getTask().getPreConditions().add(cond);
          }
        } else {
          LOG.log(Level.FINEST, "Condition: [" + graph.getModelId()
              + "] has not parent: it's a condition definition");
        }
      } else {
        LOG.log(Level.FINEST, "Condition: [" + graph.getModelId()
            + "]: parent is null");
      }
      // if parent doesn't have task or workflow set, then its parent
      // is null and it's a condition definition, just add it

    } else if (graph.getExecutionType().equals("task")) {
      WorkflowTask task;
      if (graph.getModelIdRef() != null && !graph.getModelIdRef().equals("")) {
        LOG.log(Level.FINER, "Model ID-Ref to: [" + graph.getModelIdRef() + "]");
        task = this.tasks.get(graph.getModelIdRef());
      } else {
        task = new WorkflowTask();
        task.setTaskId(graph.getModelId());
        task.setTaskName(graph.getModelName());
        task.setTaskConfig(convertToTaskConfiguration(staticMetadata));
        task.setTaskInstanceClassName(graph.getClazz());

        if (task.getTaskName() == null || (task.getTaskName().equals(""))) {
          task.setTaskName(task.getTaskId());
        }
        this.tasks.put(graph.getModelId(), task);
      }

      graph.setTask(task);
      if (graph.getParent() != null) {
        if (graph.getParent().getWorkflow() != null) {
          graph.getParent().getWorkflow().getTasks().add(task);
        } else {
          LOG.log(Level.FINEST, "Task: [" + graph.getModelId()
              + "] has no parent: it's a task definition");
        }
      } else {
        LOG.log(Level.FINEST, "Task: [" + graph.getModelId()
            + "]: parent is null");
      }
    }

  }

  /**
   * Wraps a bare task from a parallel block in a workflow of its own, since a
   * task cannot be started on its own.
   *
   * The wrapper is registered as an event as well as a workflow. It used to be
   * only a workflow: these are built while computeEvents is already iterating
   * a snapshot taken before they existed, so nothing ever came back to give
   * them one, and getWorkflows and getRegisteredEvents disagreed about them.
   * A wrapper is a real workflow that really runs, so it is listed and
   * startable like any other.
   */
  private ParentChildWorkflow getDynamicWorkflow(WorkflowTask task) {
    Graph graph = new Graph();
    graph.setExecutionType("sequential");
    ParentChildWorkflow workflow = new ParentChildWorkflow(graph);
    workflow.setId("parallel-" + UUID.randomUUID().toString());
    workflow.setName("Parallel Single Task " + task.getTaskName());
    workflow.getTasks().add(task);
    this.workflows.put(workflow.getId(), workflow);

    List<ParentChildWorkflow> asEvent = new Vector<ParentChildWorkflow>();
    asEvent.add(workflow);
    this.eventWorkflowMap.put(workflow.getId(), asEvent);

    return workflow;
  }

  /**
   * Builds the task a parent workflow uses to reach a nested sub-workflow.
   *
   * <p>
   * The id is derived from the event it redirects to rather than generated
   * randomly, because it has to be the same in every repository that reads the
   * same files. A workflow manager builds this repository twice -- once for
   * itself and once for the engine's processor queue -- and with a random id
   * the two disagreed: the manager created instances referencing its own
   * redirector ids, and the engine's repository rejected the generated
   * sub-workflow with "undefined task" because it had minted different ones.
   * The redirector then ran without its configuration, so the event name it
   * was meant to send was null.
   * </p>
   *
   * <p>
   * Deriving the id also means it survives a restart, so an instance persisted
   * against a redirector still resolves afterwards. Two parents redirecting to
   * the same child produce the same task, which is correct: the task carries
   * nothing but the event name, and each parent still gets its own instance.
   * </p>
   */
  private WorkflowTask generateRedirector(String eventName) {
    WorkflowTask task = new WorkflowTask();
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    config.addConfigProperty("eventName", eventName);
    task.setTaskId("redirector-" + eventName);
    task.setTaskName("Redirector Task");
    task.setTaskInstanceClassName(BranchRedirector.class.getName());
    this.tasks.put(task.getTaskId(), task);
    return task;
  }

  private WorkflowTaskConfiguration convertToTaskConfiguration(Metadata met) {
    WorkflowTaskConfiguration config = new WorkflowTaskConfiguration();
    if (met == null) {
      return config;
    }
    for (String key : met.getAllKeys()) {
      config.addConfigProperty(key, met.getMetadata(key));
    }
    return config;
  }

  private WorkflowConditionConfiguration convertToConditionConfiguration(
      Metadata met) {
    WorkflowConditionConfiguration config = new WorkflowConditionConfiguration();
    if (met == null) {
      return config;
    }
    for (String key : met.getAllKeys()) {
      config.addConfigProperty(key, met.getMetadata(key));
    }
    return config;
  }

  /**
   * Taken from: http://stackoverflow.com/questions/1241525/java-element-
   * getelementsbytagname-restrict-to-top-level
   */
  /**
   * Records how a conditions block asked for its conditions to be evaluated.
   *
   * <p>
   * The block itself never becomes a {@link Graph} -- this class reads its
   * attributes and descends straight to the condition children -- so the
   * strategy is kept on the workflow that owns the conditions. It cannot be
   * kept on the conditions: one written with an id and referenced by id-ref is
   * a single shared object, so a strategy set there would follow it into every
   * other workflow that references it.
   * </p>
   *
   * <p>
   * The graph passed here is the enclosing element's, whose workflow is set by
   * expandWorkflowTasksAndConditions before the children are visited.
   * </p>
   */
  private void recordConditionExecutionType(Graph graph, String conditionType,
      String executionType) {
    if (executionType == null || executionType.trim().equals("")
        || graph.getWorkflow() == null) {
      return;
    }

    if (!Graph.processorIds.contains(executionType)) {
      LOG.log(Level.WARNING, "Ignoring unsupported execution type '"
          + executionType + "' on a conditions block of workflow: ["
          + graph.getWorkflow().getId() + "]");
      return;
    }

    if ("post".equalsIgnoreCase(conditionType)) {
      graph.getWorkflow().setPostConditionExecutionType(executionType);
    } else {
      graph.getWorkflow().setPreConditionExecutionType(executionType);
    }
  }

  private List<Element> getChildrenByTagName(Element parent, String name) {
    List<Element> nodeList = new Vector<Element>();
    for (Node child = parent.getFirstChild(); child != null; child = child
        .getNextSibling()) {
      if (child.getNodeType() == Node.ELEMENT_NODE
          && name.equals(child.getNodeName())) {
        nodeList.add((Element) child);
      }
    }

    return nodeList;
  }

  private WorkflowTask getGlobalWorkflowConditionsTask(String workflowName, String workflowId,
      List<WorkflowCondition> conditions) {
    WorkflowTask task = new WorkflowTask();
    task.setConditions(conditions);
    task.setTaskConfig(new WorkflowTaskConfiguration());
    task.setTaskId(workflowId + "-global-conditions-eval");
    task.setTaskName(workflowName + "-global-conditions-eval");
    task.setTaskInstanceClassName(NoOpTask.class.getName());
    this.tasks.put(task.getTaskId(), task);
    return task;
  }
  
}
