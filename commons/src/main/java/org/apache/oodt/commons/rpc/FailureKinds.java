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
package org.apache.oodt.commons.rpc;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Sorts a server-side failure into one of a fixed set of kinds, so a caller can
 * branch on what went wrong without parsing a message.
 *
 * <p>
 * Each Avro protocol declares its own {@code OodtFailureKind} enum, because a
 * protocol's types live in that protocol's namespace and generating one shared
 * class from several modules would put duplicates on the classpath. The
 * vocabulary is kept in one place here so those enums cannot drift apart; the
 * servers call {@link #classify} and hand the result to their own generated
 * enum's {@code valueOf}.
 *
 * <p>
 * Classification is by exception class name rather than by type, so this class
 * does not have to depend on filemgr, workflow, resource and crawler in order
 * to name their exceptions. That also means an exception this does not
 * recognise sorts to {@link #UNKNOWN} rather than failing to compile, which is
 * the behaviour we want as the servers grow: the exact class name travels in
 * its own field, so nothing is lost even when the kind is coarse.
 */
public final class FailureKinds {

    public static final String UNKNOWN = "UNKNOWN";

    /**
     * Declared but not yet produced. No exception type means "not found":
     * the catalogs signal a missing record by returning null, and a
     * CatalogException that happens to say "NOT found" is not distinguishable
     * from any other by its class. Adding a kind later is a protocol change,
     * so it is declared now and can be populated when a server is taught to
     * raise it deliberately.
     */
    public static final String NOT_FOUND = "NOT_FOUND";

    private static final Map<String, String> BY_SIMPLE_NAME;

    static {
        Map<String, String> m = new HashMap<String, String>();
        // file manager
        m.put("CatalogException", "CATALOG");
        m.put("RepositoryManagerException", "REPOSITORY");
        m.put("ValidationLayerException", "VALIDATION");
        m.put("DataTransferException", "TRANSFER");
        m.put("VersioningException", "VERSIONING");
        m.put("QueryFormulationException", "QUERY");
        // workflow
        m.put("RepositoryException", "WORKFLOW_REPOSITORY");
        m.put("InstanceRepositoryException", "INSTANCE_REPOSITORY");
        // resource manager and batch stub
        m.put("JobExecutionException", "JOB_EXECUTION");
        m.put("JobRepositoryException", "JOB_REPOSITORY");
        m.put("JobQueueException", "JOB_QUEUE");
        m.put("JobInputException", "JOB_INPUT");
        m.put("JobException", "JOB");
        m.put("MonitorException", "MONITOR");
        m.put("QueueManagerException", "QUEUE");
        m.put("SchedulerException", "SCHEDULER");
        // anything transport or JDK level
        m.put("IOException", "IO");
        m.put("MalformedURLException", "IO");
        m.put("UnknownHostException", "IO");
        m.put("InterruptedException", "INTERRUPTED");
        BY_SIMPLE_NAME = Collections.unmodifiableMap(m);
    }

    private FailureKinds() {
    }

    /**
     * The kind for a failure, walking up the class hierarchy so a subclass of a
     * known exception sorts with its parent rather than falling to
     * {@link #UNKNOWN}.
     *
     * @return a name every generated {@code OodtFailureKind} declares.
     */
    public static String classify(Throwable cause) {
        if (cause == null) {
            return UNKNOWN;
        }
        for (Class<?> c = cause.getClass(); c != null; c = c.getSuperclass()) {
            String kind = BY_SIMPLE_NAME.get(c.getSimpleName());
            if (kind != null) {
                return kind;
            }
        }
        return UNKNOWN;
    }

    /** Every kind the protocols must declare. Used to keep the enums in step. */
    public static java.util.Set<String> all() {
        java.util.Set<String> kinds = new java.util.TreeSet<String>(BY_SIMPLE_NAME.values());
        kinds.add(UNKNOWN);
        kinds.add(NOT_FOUND);
        return Collections.unmodifiableSet(kinds);
    }
}
