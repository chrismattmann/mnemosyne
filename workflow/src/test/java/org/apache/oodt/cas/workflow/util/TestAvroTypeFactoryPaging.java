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

package org.apache.oodt.cas.workflow.util;

import org.apache.oodt.cas.workflow.struct.avrotypes.AvroWorkflowInstancePage;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;

import org.junit.Test;

import java.util.Vector;

import static org.junit.Assert.*;

/**
 * A page's size is written on the way out and was never read back, so every
 * page that crossed the wire arrived carrying the field's initial -1 however
 * large the page actually was. A client asking "how many per page" got an
 * answer that was never true.
 */
public class TestAvroTypeFactoryPaging {

    private static WorkflowInstancePage page(int num, int size, int total) {
        WorkflowInstancePage p = new WorkflowInstancePage();
        p.setPageNum(num);
        p.setPageSize(size);
        p.setTotalPages(total);
        p.setPageWorkflows(new Vector());
        return p;
    }

    @Test
    public void testPageSizeSurvivesTheRoundTrip() {
        AvroWorkflowInstancePage sent =
                AvroTypeFactory.getAvroWorkflowInstancePage(page(2, 20, 5));
        assertEquals("the size did not survive being written", 20, sent.getPageSize().intValue());

        WorkflowInstancePage received = AvroTypeFactory.getWorkflowInstancePage(sent);
        assertEquals("the page size was dropped on read", 20, received.getPageSize());
    }

    /** the fields that already survived still do. */
    @Test
    public void testTheOtherPageFieldsStillSurvive() {
        WorkflowInstancePage received = AvroTypeFactory.getWorkflowInstancePage(
                AvroTypeFactory.getAvroWorkflowInstancePage(page(2, 20, 5)));

        assertEquals(2, received.getPageNum());
        assertEquals(5, received.getTotalPages());
    }

    /** a blank page reports its own size rather than a stale one. */
    @Test
    public void testABlankPageRoundTrips() {
        WorkflowInstancePage blank = WorkflowInstancePage.blankPage();
        WorkflowInstancePage received = AvroTypeFactory.getWorkflowInstancePage(
                AvroTypeFactory.getAvroWorkflowInstancePage(blank));

        assertEquals(blank.getPageSize(), received.getPageSize());
    }
}
