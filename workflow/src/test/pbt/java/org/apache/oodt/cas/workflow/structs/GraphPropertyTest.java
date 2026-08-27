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

package org.apache.oodt.cas.workflow.structs;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.exceptions.WorkflowException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Properties of {@link Graph}, the parsed form of one element of a packaged
 * workflow file.
 *
 * <p>Every node of a packaged workflow — the workflow itself, the sequential
 * and parallel blocks inside it, and each task and condition — becomes one of
 * these. The repository builds them straight from DOM elements, so the
 * elements here are built in memory rather than parsed from a file: that is
 * the only way to state what the constructor does with a shape no file in the
 * source tree happens to contain.
 */
class GraphPropertyTest {

  /** The element names {@link Graph} lists as execution types. */
  private static final List<String> PROCESSOR_IDS = Graph.processorIds;

  /**
   * The two element names the constructor singles out for having to declare an
   * execution type. A packaged workflow file's root is one of these.
   */
  private static final List<String> DECLARING_ELEMENTS =
      List.of("workflow", "conditions");

  private static Document document() throws ParserConfigurationException {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .newDocument();
  }

  private static String word(TestCase tc, String label) {
    return tc.draw(text().minSize(1).maxSize(6).categories("Lu", "Ll"), label);
  }

  /**
   * A name usable as an XML element or attribute name. Drawn from ASCII
   * letters alone: what a DOM implementation accepts as a name is a question
   * about XML, not about {@link Graph}.
   */
  private static String xmlName(TestCase tc, String label) {
    return "n" + tc.draw(integers().min(0).max(9999), label);
  }

  /**
   * A task or condition element parses, and everything written on it reads
   * back. This is one row of a workflow file becoming one node of the model
   * the engine walks.
   */
  @HegelTest
  void anElementNamedForAProcessorParsesAndKeepsItsAttributes(TestCase tc)
      throws Exception {
    String elementName = tc.draw(sampledFrom(PROCESSOR_IDS), "elementName");
    String modelId = word(tc, "modelId");
    String modelName = word(tc, "modelName");
    String clazz = word(tc, "clazz");
    long timeout = tc.draw(integers().min(-1).max(600), "timeout");
    boolean optional = tc.draw(booleans(), "optional");
    Element element = document().createElement(elementName);
    element.setAttribute("id", modelId);
    element.setAttribute("name", modelName);
    element.setAttribute("class", clazz);
    element.setAttribute("timeout", String.valueOf(timeout));
    element.setAttribute("optional", String.valueOf(optional));

    Graph graph = new Graph(element, new Metadata());

    assertEquals(elementName, graph.getExecutionType(),
        "the node's execution type is not its element name");
    assertEquals(modelId, graph.getModelId(), "the model id changed");
    assertEquals(modelName, graph.getModelName(), "the model name changed");
    assertEquals(clazz, graph.getClazz(), "the class changed");
    assertEquals(timeout, graph.getTimeout(), "the timeout changed");
    assertEquals(optional, graph.isOptional(),
        "whether the node is optional changed");
    assertNotNull(graph.getChildren(), "the node has null children");
    assertTrue(graph.getChildren().isEmpty(),
        "a freshly parsed node already has children");
  }

  /**
   * A node that gives neither an id nor a reference to one is given an id of
   * its own, and no two such nodes are given the same one. The repository
   * files every node by its model id, so an unidentified node would collide
   * with the next unidentified node.
   */
  @HegelTest
  void anUnidentifiedNodeIsGivenAnIdOfItsOwn(TestCase tc) throws Exception {
    String elementName = tc.draw(sampledFrom(PROCESSOR_IDS), "elementName");
    boolean referencing = tc.draw(booleans(), "referencing");
    String idRef = word(tc, "idRef");
    Element element = document().createElement(elementName);
    if (referencing) {
      element.setAttribute("id-ref", idRef);
    }

    Graph first = new Graph(element, new Metadata());
    Graph second = new Graph(element, new Metadata());

    if (referencing) {
      assertEquals(idRef, first.getModelIdRef(), "the reference changed");
      assertFalse(first.getModelId() != null && !first.getModelId().equals(""),
          "a node referring to another was given an id of its own: "
              + first.getModelId());
    } else {
      assertNotNull(first.getModelId(), "an unidentified node has no id");
      assertFalse(first.getModelId().equals(""),
          "an unidentified node was given a blank id");
      assertFalse(first.getModelId().equals(second.getModelId()),
          "two unidentified nodes were given the same id");
    }
  }

  /**
   * An alias becomes the node's model id. That is what an alias is for: the
   * same task declared twice under different aliases is two nodes of the
   * model rather than one that the second declaration overwrote.
   */
  @HegelTest
  void anAliasBecomesTheModelId(TestCase tc) throws Exception {
    String elementName = tc.draw(sampledFrom(PROCESSOR_IDS), "elementName");
    String modelId = word(tc, "modelId");
    String alias = word(tc, "alias");
    Element element = document().createElement(elementName);
    element.setAttribute("id", modelId);
    element.setAttribute("alias", alias);

    Graph graph = new Graph(element, new Metadata());

    assertEquals(alias, graph.getAlias(), "the alias changed");
    assertEquals(alias, graph.getModelId(),
        "the aliased node is still filed under " + graph.getModelId());
  }

  /**
   * Attributes written with the {@code p:} prefix become static metadata under
   * the name without it. This is how a workflow file configures a node inline
   * rather than in a configuration block, and the metadata it produces is what
   * the node's task is handed when it runs.
   */
  @HegelTest
  void prefixedAttributesBecomeStaticMetadata(TestCase tc) throws Exception {
    String elementName = tc.draw(sampledFrom(PROCESSOR_IDS), "elementName");
    int count = tc.draw(integers().min(0).max(3), "count");
    Element element = document().createElement(elementName);
    Metadata staticMetadata = new Metadata();
    String[] names = new String[count];
    String[] values = new String[count];
    for (int i = 0; i < count; i++) {
      names[i] = xmlName(tc, "propName" + i) + i;
      values[i] = word(tc, "propValue" + i);
      element.setAttribute("p:" + names[i], values[i]);
    }
    element.setAttribute("id", word(tc, "modelId"));

    new Graph(element, staticMetadata);

    for (int i = 0; i < count; i++) {
      assertEquals(values[i], staticMetadata.getMetadata(names[i]),
          names[i] + " did not reach the static metadata");
    }
    assertEquals(count, staticMetadata.getAllKeys().size(),
        "the static metadata picked up something else: "
            + staticMetadata.getAllKeys());
  }

  /**
   * An element named after nothing the engine can run is rejected. A workflow
   * file naming a block the engine has no processor for cannot be run, and
   * saying so while reading it is the only chance to say so at all.
   */
  @HegelTest
  void anElementNamedAfterNoProcessorIsRejected(TestCase tc) throws Exception {
    String elementName = xmlName(tc, "elementName");
    tc.assume(!PROCESSOR_IDS.contains(elementName));
    tc.assume(!DECLARING_ELEMENTS.contains(elementName));
    Element element = document().createElement(elementName);
    element.setAttribute("execution", "sequential");

    assertThrows(WorkflowException.class,
        () -> new Graph(element, new Metadata()),
        elementName + " was accepted as an execution type");
  }

  /**
   * A workflow or conditions element that declares how it executes is
   * accepted, and executes that way.
   *
   * <p>The constructor rejects one of these that declares no execution type,
   * with a message naming the missing attribute, which is only a sensible
   * thing to do if declaring it is what makes the element acceptable. The
   * repository agrees: it asks the resulting node whether its execution type
   * is {@code workflow} while deciding whether the node is a workflow.
   */
  @HegelTest
  void aWorkflowElementDeclaringItsExecutionTypeIsAccepted(TestCase tc)
      throws Exception {
    String elementName = tc.draw(sampledFrom(DECLARING_ELEMENTS),
        "elementName");
    String executionType = tc.draw(sampledFrom(List.of("sequential",
        "parallel")), "executionType");
    Element element = document().createElement(elementName);
    element.setAttribute("execution", executionType);
    element.setAttribute("id", word(tc, "modelId"));

    Graph graph = assertDoesNotThrow(
        () -> new Graph(element, new Metadata()),
        "a <" + elementName + " execution=\"" + executionType
            + "\"> was rejected");

    assertNotNull(graph.getExecutionType(),
        "a <" + elementName + " execution=\"" + executionType
            + "\"> parsed to a node that executes no way at all");
  }

  /**
   * A workflow or conditions element that declares no execution type is
   * rejected, with the missing attribute named.
   */
  @HegelTest
  void aWorkflowElementWithoutAnExecutionTypeIsRejected(TestCase tc)
      throws Exception {
    String elementName = tc.draw(sampledFrom(DECLARING_ELEMENTS),
        "elementName");
    Element element = document().createElement(elementName);
    element.setAttribute("id", word(tc, "modelId"));

    WorkflowException thrown = assertThrows(WorkflowException.class,
        () -> new Graph(element, new Metadata()),
        "a <" + elementName + "> declaring no execution type was accepted");

    assertTrue(thrown.getMessage().contains("execution type"),
        "the rejection does not mention the execution type: "
            + thrown.getMessage());
  }

  /**
   * A node knows which of the three kinds of thing it holds, and a node built
   * by hand holds none of them. The engine switches on these three questions
   * to decide what to do with a node.
   */
  @HegelTest
  void aNodeKnowsWhatItHolds(TestCase tc) {
    String modelId = word(tc, "modelId");
    Graph graph = new Graph();

    assertFalse(graph.isTask(), "an empty node claims to hold a task");
    assertFalse(graph.isCondition(),
        "an empty node claims to hold a condition");
    assertFalse(graph.isWorkflow(),
        "an empty node claims to hold a workflow");

    WorkflowTask task = new WorkflowTask();
    task.setTaskId(modelId);
    graph.setTask(task);
    assertTrue(graph.isTask(), "a node holding a task denies it");
    assertFalse(graph.isCondition(),
        "a node holding a task claims to hold a condition");

    graph.setCond(new WorkflowCondition(modelId, modelId, modelId, 0));
    assertTrue(graph.isCondition(), "a node holding a condition denies it");

    graph.setWorkflow(new ParentChildWorkflow(new Graph()));
    assertTrue(graph.isWorkflow(), "a node holding a workflow denies it");
  }
}
