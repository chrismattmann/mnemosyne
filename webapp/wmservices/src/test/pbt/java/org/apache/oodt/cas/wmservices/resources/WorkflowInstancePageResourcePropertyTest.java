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

package org.apache.oodt.cas.wmservices.resources;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowState;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowInstancePage;

/**
 * Properties of the page of workflow instances the REST service returns.
 *
 * <p>{@link WorkflowInstancePageResource} is the adapter between the workflow
 * manager's own page object and the JSON a client is served. An adapter that
 * loses an instance, reorders one or disagrees with the page numbers it was
 * given makes the client's paging arithmetic wrong, and the client has nothing
 * else to check it against.
 */
class WorkflowInstancePageResourcePropertyTest {

  private static Generator<String> word() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  private static WorkflowInstance instanceNamed(String id, String taskId, String stateName) {
    WorkflowState state = new WorkflowState();
    state.setName(stateName);
    state.setDescription("a state");
    state.setStartTime(new Date(0));

    WorkflowInstance instance = new WorkflowInstance();
    instance.setId(id);
    instance.setCurrentTaskId(taskId);
    instance.setState(state);
    instance.setSharedContext(new Metadata());
    return instance;
  }

  /**
   * The page a client is served describes every instance on the page the
   * workflow manager produced, in the same order. The client identifies a
   * running workflow by its position and its id, so neither may move.
   */
  @HegelTest
  void everyInstanceOnThePageIsDescribedInOrder(TestCase tc) {
    List<String> ids = tc.draw(lists(word()).maxSize(6), "ids");
    String taskId = tc.draw(word(), "taskId");
    String stateName =
        tc.draw(sampledFrom(Arrays.asList("QUEUED", "STARTED", "FINISHED")), "stateName");

    List<WorkflowInstance> instances = new ArrayList<WorkflowInstance>();
    for (String id : ids) {
      instances.add(instanceNamed(id, taskId, stateName));
    }

    WorkflowInstancePage page = new WorkflowInstancePage();
    page.setPageWorkflows(instances);

    List<WorkflowInstanceResource> described =
        new WorkflowInstancePageResource(page, ids.size()).getPageWorkflows();

    assertEquals(ids.size(), described.size(), "the page lost or invented an instance");
    for (int i = 0; i < ids.size(); i++) {
      assertEquals(ids.get(i), described.get(i).getWorkflowInstanceId(), "instance " + i);
      assertEquals(taskId, described.get(i).getCurrentTaskId(), "current task of instance " + i);
      assertEquals(
          stateName, described.get(i).getWorkflowState().getName(), "state of instance " + i);
    }
  }

  /**
   * The paging numbers a client is served are the ones the workflow manager
   * worked out. A client builds its page links from these, so a number the
   * adapter changes is a link that goes to the wrong page.
   */
  @HegelTest
  void thePagingNumbersAreThePageManagersOwn(TestCase tc) {
    int pageNum = tc.draw(integers().min(1).max(500), "pageNum");
    int totalPages = tc.draw(integers().min(1).max(500), "totalPages");
    int pageSize = tc.draw(integers().min(1).max(100), "pageSize");
    int totalCount = tc.draw(integers().min(0).max(50_000), "totalCount");

    WorkflowInstancePage page = new WorkflowInstancePage();
    page.setPageNum(pageNum);
    page.setTotalPages(totalPages);
    page.setPageSize(pageSize);
    page.setPageWorkflows(new ArrayList<WorkflowInstance>());

    WorkflowInstancePageResource resource = new WorkflowInstancePageResource(page, totalCount);

    assertEquals(pageNum, resource.getPageNum());
    assertEquals(totalPages, resource.getTotalPages());
    assertEquals(pageSize, resource.getPageSize());
    assertEquals(totalCount, resource.getTotalWorkflowCount());
  }

  /**
   * Every key and value of a workflow's shared context reaches the client. The
   * shared context is the workflow's whole state as far as an outside caller is
   * concerned.
   */
  @HegelTest
  void everyKeyAndValueOfTheSharedContextReachesTheClient(TestCase tc) {
    List<String> keys = tc.draw(lists(word()).minSize(1).maxSize(5), "keys");
    List<String> values = tc.draw(lists(word()).minSize(1).maxSize(3), "values");

    Metadata context = new Metadata();
    for (String key : keys) {
      for (String value : values) {
        context.addMetadata(key, value);
      }
    }

    MetadataResource resource = new MetadataResource(context);

    List<MetadataResource.MetadataEntry> entries = resource.getMetadataEntries();
    assertEquals(context.getAllKeys().size(), entries.size(), "a key was lost");
    for (MetadataResource.MetadataEntry entry : entries) {
      assertEquals(
          context.getAllMetadata(entry.getKey()),
          entry.getValues(),
          "the values of [" + entry.getKey() + "] were changed");
    }
  }
}
