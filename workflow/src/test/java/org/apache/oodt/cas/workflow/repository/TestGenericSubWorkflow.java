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

package org.apache.oodt.cas.workflow.repository;

import org.apache.oodt.cas.workflow.structs.Graph;
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.Workflow;

import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The generic spelling of a sub-workflow: {@code <workflow execution="...">},
 * which names its execution strategy in an attribute rather than in the tag.
 *
 * <p>
 * expandWorkflowTasksAndConditions has always handled the execution type
 * "workflow" alongside "sequential" and "parallel", but the repository scanned
 * for child elements using Graph.processorIds -- the list of execution
 * strategies -- and "workflow" is not a strategy, so the element was never
 * looked for and that branch was unreachable. The two lists are now separate.
 * </p>
 */
public class TestGenericSubWorkflow {

  private static final String SUBWORKFLOW =
      "src/test/resources/wengine-e2e/e2e-subworkflow.xml";

  @Test
  public void theElementNamesAreNotTheExecutionStrategies() {
    assertTrue("a container element, not a strategy",
        Graph.graphElementNames.contains("workflow"));
    assertFalse("execution=\"workflow\" is meaningless",
        Graph.processorIds.contains("workflow"));

    for (String strategy : Graph.processorIds) {
      assertTrue(strategy + " must still be scanned for",
          Graph.graphElementNames.contains(strategy));
    }
  }

  @Test
  public void agenericSubWorkflowIsRead() throws Exception {
    Workflow outer = repository().getWorkflowById("urn:oodt:e2e:GenericOuter");

    assertNotNull("the <workflow> element was never scanned for", outer);
    assertEquals("Generic Outer", outer.getName());
  }

  /** The strategy comes from the attribute, not from the tag name. */
  @Test
  public void thedeclaredStrategyIsWhatItRunsAs() throws Exception {
    PackagedWorkflowRepository repo = repository();

    ParentChildWorkflow outer =
        (ParentChildWorkflow) repo.getWorkflowById("urn:oodt:e2e:GenericOuter");

    assertEquals("sequential", outer.getGraph().getExecutionType());
  }

  @Test
  public void anestedGenericSubWorkflowIsReadToo() throws Exception {
    ParentChildWorkflow inner = (ParentChildWorkflow) repository()
        .getWorkflowById("urn:oodt:e2e:GenericInner");

    assertNotNull("a <workflow> nested in a <workflow>", inner);
    assertEquals("sequential", inner.getGraph().getExecutionType());
    assertEquals("urn:oodt:e2e:StepTwo", inner.getTasks().get(0).getTaskId());
  }

  /**
   * A parallel workflow is dissolved into one dynamic workflow per task, so it
   * does not appear under its own id. The generic spelling has to be treated
   * identically to a &lt;parallel&gt; element -- that equivalence is the whole
   * claim, so it is asserted against the tag-name file rather than assumed.
   */
  @Test
  public void agenericParallelIsDissolvedJustLikeAParallelElement()
      throws Exception {
    assertFalse("<parallel> should be dissolved",
        idsFrom(repositoryFor("src/test/resources/wengine-e2e/e2e-parallel.xml"))
            .contains("urn:oodt:e2e:BothAtOnce"));

    assertFalse("<workflow execution=\"parallel\"> should be dissolved too",
        idsFrom(repository()).contains("urn:oodt:e2e:GenericParallel"));
  }

  /**
   * A sequential parent reaches its child through a redirector task, which is
   * the same mechanism a nested &lt;sequential&gt; uses.
   */
  @Test
  public void theOuterWorkflowReachesTheInnerOne() throws Exception {
    ParentChildWorkflow outer = (ParentChildWorkflow) repository()
        .getWorkflowById("urn:oodt:e2e:GenericOuter");

    assertEquals(2, outer.getTasks().size());
    assertEquals("urn:oodt:e2e:StepOne", outer.getTasks().get(0).getTaskId());
  }

  /** The tag-name spelling has to keep working exactly as before. */
  @Test
  public void thenamedSpellingStillWorks() throws Exception {
    PackagedWorkflowRepository repo =
        repositoryFor("src/test/resources/wengine-e2e/e2e-parallel.xml");

    ParentChildWorkflow outer =
        (ParentChildWorkflow) repo.getWorkflowById("urn:oodt:e2e:Outer");
    ParentChildWorkflow inner =
        (ParentChildWorkflow) repo.getWorkflowById("urn:oodt:e2e:Inner");

    assertEquals("sequential", outer.getGraph().getExecutionType());
    assertEquals("sequential", inner.getGraph().getExecutionType());
  }

  @Test
  public void bothSequentialSubWorkflowsAreListed() throws Exception {
    List<String> ids = idsFrom(repository());

    assertTrue("outer missing from getWorkflows()",
        ids.contains("urn:oodt:e2e:GenericOuter"));
    assertTrue("inner missing from getWorkflows()",
        ids.contains("urn:oodt:e2e:GenericInner"));
  }

  private List<String> idsFrom(PackagedWorkflowRepository repo)
      throws Exception {
    List<String> ids = new java.util.ArrayList<String>();
    for (Object o : repo.getWorkflows()) {
      ids.add(((Workflow) o).getId());
    }
    return ids;
  }

  private PackagedWorkflowRepository repository() throws Exception {
    return repositoryFor(SUBWORKFLOW);
  }

  private PackagedWorkflowRepository repositoryFor(String path)
      throws Exception {
    return new PackagedWorkflowRepository(
        Collections.singletonList(new File(path)));
  }
}
