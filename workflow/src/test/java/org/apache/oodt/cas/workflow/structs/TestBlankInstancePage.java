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

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * WorkflowInstancePage.blankPage carries the same defect #134 item 17 reports
 * against ProductPage.blankPage: page zero of zero is not the first page, so a
 * client offers a "previous page" on a result set with nothing in it.
 */
public class TestBlankInstancePage {

  @Test
  public void aBlankPageIsBothFirstAndLast() {
    WorkflowInstancePage blank = WorkflowInstancePage.blankPage();

    assertTrue("a blank page should be the first page", blank.isFirstPage());
    assertTrue("a blank page should be the last page", blank.isLastPage());
    assertTrue(blank.getPageWorkflows().isEmpty());
  }

  @Test
  public void aRealFirstPageIsUnaffected() {
    WorkflowInstancePage page =
        new WorkflowInstancePage(1, 3, 10, new ArrayList<WorkflowInstance>());

    assertTrue(page.isFirstPage());
    assertFalse(page.isLastPage());
  }
}
