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

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * Metadata keys for reading the {@link WorkflowLifecycle}s file
 * </p>.
 */
public interface WorkflowLifecycleMetKeys {

    String DEFAULT_LIFECYCLE = "default";

    String LIFECYCLE_TAG_NAME_ATTR = "name";

    String STAGE_TAG_NAME_ATTR = "name";

    String STATUS_TAG_NAME = "status";

    String STAGE_ELEM_NAME = "stage";

    String LIFECYCLE_TAG_NAME = "lifecycle";

    /** Marks a named lifecycle as the one to use when a workflow names none. */
    String LIFECYCLE_TAG_DEFAULT_ATTR = "default";

    /** Binds a lifecycle to a single workflow by id. */
    String LIFECYCLE_TAG_WORKFLOW_ID_ATTR = "workflowId";

    /** Tie-break weight for a stage; higher wins. */
    String STAGE_TAG_PRIORITY_ATTR = "priority";

    /** Declares a state reachable from the enclosing state. */
    String NEXT_ELEM_NAME = "next";

    String NEXT_TAG_STATE_ATTR = "state";

    /** Declares a guard on entry to the enclosing state. */
    String PRECONDITION_ELEM_NAME = "precondition";

    String PRECONDITION_TAG_CLASS_ATTR = "class";

    /** Configuration for a precondition. */
    String PROPERTY_ELEM_NAME = "property";

    String PROPERTY_TAG_NAME_ATTR = "name";

    String PROPERTY_TAG_VALUE_ATTR = "value";

    /** Pulls in lifecycles defined in another file. */
    String IMPORT_ELEM_NAME = "import";

    String IMPORT_TAG_FILE_ATTR = "file";

    String DESCRIPTION_ELEM_NAME = "description";
}
