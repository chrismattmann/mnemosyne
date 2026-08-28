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

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.engine.processor.TaskProcessor;
import org.apache.oodt.cas.workflow.engine.processor.WorkflowProcessor;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The class is named for first-in-last-out and documented as "the first ones
 * to get processed are the most recently created instances". Its comparator
 * sorted ascending by start date -- oldest first, a FIFO ordering -- so the
 * name, the javadoc and the code could not all be right, and two of the three
 * agreed with each other.
 */
public class TestFILOPrioritySorterOrder {

    private static final String LIFECYCLE =
        "./src/main/resources/examples/wengine/wengine-lifecycle.xml";

    /** TaskProcessor is the simplest concrete one; the sorter only reads
     *  the instance hanging off it, but the constructor wants a real
     *  lifecycle manager. */
    private static WorkflowProcessor processorStartedAt(long millis)
            throws Exception {
        WorkflowInstance inst = new WorkflowInstance();
        inst.setId("urn:test:inst-" + millis);
        inst.setParentChildWorkflow(new ParentChildWorkflow(new Graph()));
        inst.setSharedContext(new Metadata());
        inst.setStartDateTimeIsoStr(
                org.apache.oodt.commons.util.DateConvert.isoFormat(new Date(millis)));
        return new TaskProcessor(new WorkflowLifecycleManager(LIFECYCLE), inst);
    }

    /** The shrunk counterexample: two instances, aged 0s and 1s. */
    @Test
    public void testTheMostRecentlyCreatedIsScheduledFirst() throws Exception {
        WorkflowProcessor older = processorStartedAt(1000L);
        WorkflowProcessor newer = processorStartedAt(2000L);

        List<WorkflowProcessor> candidates = new ArrayList<WorkflowProcessor>();
        candidates.add(older);
        candidates.add(newer);

        new FILOPrioritySorter().sort(candidates);

        assertSame("the oldest instance was scheduled first, which is FIFO",
                newer, candidates.get(0));
    }

    /** and the whole list is newest-first, not just the head. */
    @Test
    public void testTheWholeListIsNewestFirst() throws Exception {
        List<WorkflowProcessor> candidates = new ArrayList<WorkflowProcessor>();
        for (int i = 0; i < 5; i++) {
            candidates.add(processorStartedAt(1000L + i * 1000L));
        }

        new FILOPrioritySorter().sort(candidates);

        for (int i = 1; i < candidates.size(); i++) {
            assertTrue("not descending at " + i,
                    candidates.get(i - 1).getWorkflowInstance().getStartDate()
                        .compareTo(candidates.get(i).getWorkflowInstance()
                            .getStartDate()) >= 0);
        }
    }
}
