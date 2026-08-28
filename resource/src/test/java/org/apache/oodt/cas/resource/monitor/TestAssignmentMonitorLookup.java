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

package org.apache.oodt.cas.resource.monitor;

import org.apache.oodt.cas.resource.structs.ResourceNode;

import org.junit.Test;

import java.net.URL;

import static org.junit.Assert.*;

/**
 * getNodeByURL compared addresses with ==, so the lookup succeeded only when
 * the caller happened to hold the very URL object the monitor had stored.
 * Anything that built its URL from a string -- reading a node address off the
 * wire, out of configuration, or from a CLI argument -- got null for a node
 * that was registered and healthy.
 */
public class TestAssignmentMonitorLookup {

    private static AssignmentMonitor monitorWith(ResourceNode... nodes)
            throws Exception {
        java.util.List<ResourceNode> list = new java.util.Vector<ResourceNode>();
        for (ResourceNode n : nodes) {
            list.add(n);
        }
        return new AssignmentMonitor(list);
    }

    @Test
    public void testANodeIsFoundByAnEquivalentUrl() throws Exception {
        ResourceNode node = new ResourceNode("node-0",
                new URL("http://0.example:9000"), 8);
        AssignmentMonitor monitor = monitorWith(node);

        ResourceNode found = monitor.getNodeByURL(new URL("http://0.example:9000"));

        assertNotNull("a registered node was not found by its own address", found);
        assertEquals("node-0", found.getNodeId());
    }

    /** The identical object still works, as it always did. */
    @Test
    public void testANodeIsFoundByTheVeryUrlItWasRegisteredWith() throws Exception {
        URL address = new URL("http://0.example:9000");
        AssignmentMonitor monitor = monitorWith(new ResourceNode("node-0", address, 8));

        assertNotNull(monitor.getNodeByURL(address));
    }

    /** An address nothing is registered at still returns null. */
    @Test
    public void testAnUnknownAddressIsNotFound() throws Exception {
        AssignmentMonitor monitor = monitorWith(
                new ResourceNode("node-0", new URL("http://0.example:9000"), 8));

        assertNull(monitor.getNodeByURL(new URL("http://1.example:9000")));
    }

    /** A different port is a different node. */
    @Test
    public void testThePortIsPartOfTheAddress() throws Exception {
        AssignmentMonitor monitor = monitorWith(
                new ResourceNode("node-0", new URL("http://0.example:9000"), 8));

        assertNull(monitor.getNodeByURL(new URL("http://0.example:9001")));
    }
}
