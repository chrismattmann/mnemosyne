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

package org.apache.oodt.pcs.util;

import org.junit.Test;

import java.net.URL;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Telling "the File Manager is down" apart from "the catalog is empty".
 *
 * <p>
 * The safe* methods never throw: with no client they return an empty list, a
 * null, or a blank object. That suits a caller that wants to keep rendering
 * and misleads one that reports what it found -- a product type named
 * "blank" holding -1 products reads as data, not as an outage. The state was
 * private, which is exactly why the service layer could not report it.
 * </p>
 */
public class TestFileManagerUtilsConnection {

  /** Nothing is listening here. */
  private static final String DEAD = "http://localhost:1/";

  @Test
  public void anunreachableFileManagerReportsItself() throws Exception {
    FileManagerUtils fm = new FileManagerUtils(new URL(DEAD));

    assertFalse("an unreachable File Manager should not report a connection",
        fm.isConnected());
  }

  /** Construction must not throw, which is what the safe* contract rests on. */
  @Test
  public void constructionAgainstAdeadAddressStillYieldsAusableObject()
      throws Exception {
    FileManagerUtils fm = new FileManagerUtils(new URL(DEAD));

    assertNotNull(fm);
    assertEquals(DEAD, fm.getFmUrl().toString());
  }

  /**
   * The behaviour a caller gets when it does not check first: empty, not an
   * exception. This is the part that has to keep working.
   */
  @Test
  public void thesafeMethodsStillReturnEmptyRatherThanThrowing()
      throws Exception {
    FileManagerUtils fm = new FileManagerUtils(new URL(DEAD));

    List types = fm.safeGetProductTypes();
    assertNotNull("safeGetProductTypes should not return null", types);
    assertTrue("an unreachable File Manager has no product types to give",
        types.isEmpty());
  }

  /**
   * The address is kept even when the connection failed, so a report can say
   * where it looked rather than leaving the reader to guess.
   */
  @Test
  public void theaddressSurvivesAfailedConnection() throws Exception {
    FileManagerUtils fm = new FileManagerUtils(new URL(DEAD));

    assertNotNull("a failed connection must still know its address",
        fm.getFmUrl());
    assertEquals(DEAD, fm.getFmUrl().toString());
  }
}
