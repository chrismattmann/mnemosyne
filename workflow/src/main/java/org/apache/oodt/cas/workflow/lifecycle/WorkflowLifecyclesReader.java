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

import org.apache.oodt.cas.workflow.exceptions.WorkflowException;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;
import org.apache.oodt.commons.exceptions.CommonsException;
import org.apache.oodt.commons.xml.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

//JDK imports

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * A reader for the <code>workflow-lifecycle.xml</code> file.
 * </p>
 *
 * <p>
 * The file has always been able to hold more than one lifecycle, one of them
 * the default and the others bound to a particular workflow. Two things stopped
 * that from working: the reader looked for named lifecycles inside the
 * <code>&lt;default&gt;</code> element rather than beside it, and it never read
 * the <code>workflowId</code> attribute, so every lifecycle it produced claimed
 * to belong to no workflow and could never be selected. Both are fixed here,
 * which is what makes the per-workflow form documented in the shipped example
 * files actually usable.
 * </p>
 *
 * <p>
 * A lifecycle may also declare, per state, which states can follow it and what
 * has to hold before each can be entered, and a stage may carry a priority used
 * to choose between states that are eligible at the same moment. A file that
 * declares none of this parses and behaves exactly as before.
 * </p>
 */
public final class WorkflowLifecyclesReader implements WorkflowLifecycleMetKeys {

    /* our log stream */
    private static Logger LOG = Logger.getLogger(WorkflowLifecyclesReader.class
            .getName());

    private WorkflowLifecyclesReader() throws InstantiationException {
        throw new InstantiationException("Don't construct utility classes!");
    }

    public static List parseLifecyclesFile(String lifecyclesFilePath)
        throws CommonsException, WorkflowException {
        List lifecycles = parseLifecyclesFile(lifecyclesFilePath,
                new LinkedHashSet<String>());

        boolean sawDefault = false;
        for (Object lifecycle : lifecycles) {
            if (((WorkflowLifecycle) lifecycle).isDefaultLifecycle()) {
                sawDefault = true;
                break;
            }
        }

        if (!sawDefault) {
            throw new WorkflowException("file: [" + lifecyclesFilePath
                    + "] must specify a default workflow lifecycle, either as "
                    + "a <default> element or as a <lifecycle> with "
                    + "default=\"true\"!");
        }

        return lifecycles;
    }

    /**
     * Reads one file and everything it imports.
     *
     * Locally declared lifecycles come first in the returned list, so that a
     * file importing a shared set of lifecycles can override any of them by
     * declaring its own with the same workflow id, or name its own default.
     *
     * @param lifecyclesFilePath
     *          The file to read.
     * @param visited
     *          Absolute paths already being read, so that a file importing
     *          itself, directly or through a chain, stops rather than
     *          recursing until the stack runs out.
     */
    private static List parseLifecyclesFile(String lifecyclesFilePath,
            Set<String> visited) throws CommonsException, WorkflowException {
        File lifecyclesFile = new File(lifecyclesFilePath);
        String canonicalPath = canonicalPathOf(lifecyclesFile);

        if (!visited.add(canonicalPath)) {
            LOG.log(Level.WARNING, "Lifecycle file: [" + canonicalPath
                    + "] is imported more than once; skipping the repeat");
            return new Vector();
        }

        Document doc = getDocumentRoot(lifecyclesFilePath);
        if (doc == null) {
            throw new WorkflowException("Unable to read workflow lifecycle "
                    + "file: [" + lifecyclesFilePath + "]");
        }
        Element rootElem = doc.getDocumentElement();

        List lifecycles = new Vector();

        // The magic <default> element, still the common case.
        Element defaultElem = firstChildElement(rootElem, DEFAULT_LIFECYCLE);
        if (defaultElem != null) {
            lifecycles.add(readLifecycle(defaultElem, true));
        }

        // Named lifecycles, beside <default> rather than inside it.
        for (Element lifecycleElem : childElements(rootElem,
                LIFECYCLE_TAG_NAME)) {
            lifecycles.add(readLifecycle(lifecycleElem, false));
        }

        // Imports come last so local declarations win.
        for (Element importElem : childElements(rootElem, IMPORT_ELEM_NAME)) {
            String importPath = importElem.getAttribute(IMPORT_TAG_FILE_ATTR);
            if (importPath == null || importPath.equals("")) {
                LOG.log(Level.WARNING, "Ignoring <import> with no file "
                        + "attribute in: [" + lifecyclesFilePath + "]");
                continue;
            }
            lifecycles.addAll(parseLifecyclesFile(
                    resolveAgainst(lifecyclesFile, importPath), visited));
        }

        return lifecycles;
    }

    /**
     * Resolves an imported path relative to the file that imported it, so that
     * a set of lifecycle files can be moved as a group.
     */
    private static String resolveAgainst(File importingFile, String path) {
        File imported = new File(path);
        if (imported.isAbsolute()) {
            return imported.getPath();
        }
        File parent = importingFile.getAbsoluteFile().getParentFile();
        return new File(parent, path).getPath();
    }

    private static String canonicalPathOf(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static WorkflowLifecycle readLifecycle(Element lifecycleElem,
            boolean isDefault) {
        WorkflowLifecycle lifecycle = new WorkflowLifecycle();
        String lifecycleName = isDefault ? WorkflowLifecycle.DEFAULT_LIFECYCLE
                : lifecycleElem.getAttribute(LIFECYCLE_TAG_NAME_ATTR);
        lifecycle.setName(lifecycleName);

        String workflowId = lifecycleElem
                .getAttribute(LIFECYCLE_TAG_WORKFLOW_ID_ATTR);
        lifecycle.setWorkflowId(workflowId != null && !workflowId.equals("")
                ? workflowId : WorkflowLifecycle.NO_WORKFLOW_ID);

        lifecycle.setDefaultLifecycle(isDefault
                || "true".equalsIgnoreCase(lifecycleElem
                        .getAttribute(LIFECYCLE_TAG_DEFAULT_ATTR)));

        addStagesToLifecycle(lifecycle, lifecycleElem);

        return lifecycle;
    }

    private static void addStagesToLifecycle(WorkflowLifecycle lifecycle,
            Element lifecycleElem) {
        List<Element> stageElems = childElements(lifecycleElem,
                STAGE_ELEM_NAME);
        for (int i = 0; i < stageElems.size(); i++) {
            Element stageElem = stageElems.get(i);
            WorkflowLifecycleStage stage = new WorkflowLifecycleStage();
            stage.setName(stageElem.getAttribute(STAGE_TAG_NAME_ATTR));
            stage.setOrder(i + 1);
            stage.setPriority(readIntAttribute(stageElem,
                    STAGE_TAG_PRIORITY_ATTR, 0));
            stage.setStates(readStates(stageElem, stage));
            lifecycle.addStage(stage);
        }
    }

    private static int readIntAttribute(Element elem, String attribute,
            int fallback) {
        String value = elem.getAttribute(attribute);
        if (value == null || value.equals("")) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOG.log(Level.WARNING, "Attribute [" + attribute + "] on stage: ["
                    + elem.getAttribute(STAGE_TAG_NAME_ATTR) + "] is not a "
                    + "number: [" + value + "]; using [" + fallback + "]");
            return fallback;
        }
    }

    private static List<WorkflowState> readStates(Element stageElem,
            WorkflowLifecycleStage category) {
      List<WorkflowState> states = new Vector<WorkflowState>();
      for (Element statusElem : childElements(stageElem, STATUS_TAG_NAME)) {
          // see if its name is specified via the name attribute, otherwise
          // read it in back compat mode

          WorkflowState state = new WorkflowState();
          state.setCategory(category);

          String namedForm = statusElem.getAttribute(LIFECYCLE_TAG_NAME_ATTR);
          if (namedForm != null && !namedForm.equals("")) {
            state.setName(namedForm);
            state.setDescription(XMLUtils.getElementText(
                DESCRIPTION_ELEM_NAME, statusElem));
          } else {
            // back compat mode
            String statusName = XMLUtils.getSimpleElementText(statusElem);
            state.setName(statusName);
            state.setMessage(statusName);
          }

          readTransitions(statusElem, state);
          readPreConditions(statusElem, state);
          states.add(state);
      }

      return states;
    }

    /**
     * Reads the states declared reachable from this one. Order is kept: it is
     * the tie-break of last resort when two candidates sit in stages of equal
     * priority.
     */
    private static void readTransitions(Element statusElem,
            WorkflowState state) {
        for (Element nextElem : childElements(statusElem, NEXT_ELEM_NAME)) {
            String nextName = nextElem.getAttribute(NEXT_TAG_STATE_ATTR);
            if (nextName == null || nextName.equals("")) {
                // Fall back to the element's text, so both
                // <next state="X"/> and <next>X</next> read the same.
                nextName = XMLUtils.getSimpleElementText(nextElem);
            }
            if (nextName != null && !nextName.trim().equals("")) {
                state.addNextStateName(nextName.trim());
            } else {
                LOG.log(Level.WARNING, "Ignoring <next> with no state on "
                        + "status: [" + state.getName() + "]");
            }
        }
    }

    private static void readPreConditions(Element statusElem,
            WorkflowState state) {
        for (Element condElem : childElements(statusElem,
                PRECONDITION_ELEM_NAME)) {
            String className = condElem
                    .getAttribute(PRECONDITION_TAG_CLASS_ATTR);
            if (className == null || className.equals("")) {
                LOG.log(Level.WARNING, "Ignoring <precondition> with no class "
                        + "on status: [" + state.getName() + "]");
                continue;
            }
            state.addPreCondition(new WorkflowState.AttachedPreCondition(
                    className.trim(), instantiatePreCondition(className.trim()),
                    readConfiguration(condElem)));
        }
    }

    private static WorkflowConditionConfiguration readConfiguration(
            Element condElem) {
        WorkflowConditionConfiguration config =
                new WorkflowConditionConfiguration();
        for (Element propElem : childElements(condElem, PROPERTY_ELEM_NAME)) {
            String name = propElem.getAttribute(PROPERTY_TAG_NAME_ATTR);
            String value = propElem.getAttribute(PROPERTY_TAG_VALUE_ATTR);
            if (name != null && !name.equals("")) {
                config.addConfigProperty(name, value != null ? value : "");
            }
        }
        return config;
    }

    /**
     * Builds the precondition named in the file.
     *
     * A precondition that cannot be built is left null, and the state it
     * guards is then never entered. That is the safe direction: a guard nobody
     * can run must not be mistaken for a guard that passed.
     */
    private static StatePreCondition instantiatePreCondition(String className) {
        try {
            return (StatePreCondition) Class.forName(className)
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unable to load state precondition: ["
                    + className + "]; the state it guards will not be "
                    + "entered. Reason is [" + e + "]");
            return null;
        }
    }

    /**
     * Direct children with the given tag name.
     *
     * Element.getElementsByTagName searches the whole subtree, which is how
     * stages belonging to a nested lifecycle used to end up merged into the
     * lifecycle that contained it.
     */
    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> children = new Vector<Element>();
        if (parent == null) {
            return children;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                children.add((Element) node);
            }
        }
        return children;
    }

    private static Element firstChildElement(Element parent, String tagName) {
        List<Element> children = childElements(parent, tagName);
        return children.isEmpty() ? null : children.get(0);
    }

    private static Document getDocumentRoot(String xmlFile) {
        // open up the XML file
        DocumentBuilderFactory factory;
        DocumentBuilder parser;
        Document document;
        InputSource inputSource;

        InputStream xmlInputStream;

        try {
            xmlInputStream = new File(xmlFile).toURI().toURL().openStream();
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when getting input stream from [" + xmlFile
                            + "]: returning null document root");
            return null;
        }

        inputSource = new InputSource(xmlInputStream);

        try {
            factory = DocumentBuilderFactory.newInstance();
            parser = factory.newDocumentBuilder();
            document = parser.parse(inputSource);
        } catch (Exception e) {
            LOG.warning("Unable to parse xml file [" + xmlFile + "]."
                    + "Reason is [" + e + "]");
            return null;
        } finally {
            try {
                xmlInputStream.close();
            } catch (IOException ignored) {
                // nothing useful to do; the parse result is what matters
            }
        }

        return document;
    }

}
