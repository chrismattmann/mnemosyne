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

package org.apache.oodt.config.distributed.utils;

import org.apache.oodt.config.distributed.ZNodePaths;

import org.junit.After;
import org.junit.Test;

import static org.apache.oodt.config.Constants.DEFAULT_PROJECT;
import static org.apache.oodt.config.Constants.Properties.OODT_PROJECT;
import static org.junit.Assert.assertEquals;

/**
 * The project name fell back to the default only when it was null, so a start
 * script interpolating an unset variable -- {@code -Dorg.apache.oodt.config.project=}
 * -- produced {@code /projects//components/filemgr}. Zookeeper rejected that
 * far from here, with "empty node name specified @10", rather than at the
 * configuration boundary where the mistake was made.
 */
public class TestBlankProjectName {

  @After
  public void clearProperty() {
    System.clearProperty(OODT_PROJECT);
  }

  @Test
  public void anEmptyProjectPropertyFallsBackToTheDefault() {
    System.setProperty(OODT_PROJECT, "");
    assertEquals(DEFAULT_PROJECT, ConfigUtils.getOODTProjectName());
  }

  @Test
  public void aWhitespaceProjectPropertyFallsBackToTheDefault() {
    System.setProperty(OODT_PROJECT, "   ");
    assertEquals(DEFAULT_PROJECT, ConfigUtils.getOODTProjectName());
  }

  @Test
  public void arealProjectNameIsStillHonoured() {
    System.setProperty(OODT_PROJECT, "mnemosyne");
    assertEquals("mnemosyne", ConfigUtils.getOODTProjectName());
  }

  @Test
  public void aBlankProjectNeverBuildsAnEmptyPathSegment() {
    for (String project : new String[] {null, "", "  "}) {
      String path = new ZNodePaths(project, "filemgr").getComponentZNodePath();
      assertEquals("/projects/" + DEFAULT_PROJECT + "/components/filemgr", path);
    }
  }

  @Test
  public void aRealProjectStillBuildsItsOwnPath() {
    assertEquals("/projects/mnemosyne/components/filemgr",
        new ZNodePaths("mnemosyne", "filemgr").getComponentZNodePath());
  }
}
