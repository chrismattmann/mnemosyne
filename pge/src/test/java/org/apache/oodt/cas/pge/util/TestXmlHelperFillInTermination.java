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

package org.apache.oodt.cas.pge.util;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.pge.exceptions.PGEException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * fillIn looped while the replaced value still held a '[', with an empty body.
 * Nothing guaranteed the bracket would ever be consumed, so an unresolvable
 * name span forever, allocating a String on each pass.
 *
 * <p>
 * Every test here has a timeout: without the fix they do not fail, they hang.
 * </p>
 */
public class TestXmlHelperFillInTermination {

  /**
   * The reachable case. PathUtils.replaceEnvVariables leaves an unresolved
   * token as it was written -- it used to substitute the four characters
   * "null" -- so the loop condition stopped being guaranteed to end.
   */
  @Test(timeout = 15000)
  public void anUnresolvableNameDoesNotSpin() throws Exception {
    String value = XmlHelper.fillIn("/data/[NoSuchKeyAnywhere]/file.dat",
        new Metadata());

    assertEquals("/data/[NoSuchKeyAnywhere]/file.dat", value);
  }

  @Test(timeout = 15000)
  public void anUnresolvableNameAmongResolvableOnesDoesNotSpin()
      throws Exception {
    Metadata metadata = new Metadata();
    metadata.addMetadata("Known", "archive");

    String value = XmlHelper.fillIn("/[Known]/[NoSuchKeyAnywhere]/f.dat",
        metadata);

    assertEquals("/archive/[NoSuchKeyAnywhere]/f.dat", value);
  }

  /**
   * A value that refers to itself expands rather than settling, so there is no
   * fixed point to stop at and the pass count is what ends it. Reported rather
   * than run forever.
   */
  @Test(timeout = 15000)
  public void aselfReferentialValueIsReportedRatherThanExpandedForever() {
    Metadata metadata = new Metadata();
    metadata.addMetadata("Loop", "[Loop][Loop]");

    try {
      XmlHelper.fillIn("[Loop]", metadata);
      throw new AssertionError("expected a PGEException");
    } catch (PGEException e) {
      assertTrue("message should explain what went wrong: " + e.getMessage(),
          String.valueOf(e.getMessage()).contains("not settle")
              || String.valueOf(e.getCause()).contains("not settle"));
    }
  }

  @Test(timeout = 15000)
  public void avalueThatRefersToItselfDirectlyDoesNotSpin() throws Exception {
    Metadata metadata = new Metadata();
    metadata.addMetadata("Same", "[Same]");

    // Resolves to itself, so the fixed point ends it rather than the cap.
    assertEquals("[Same]", XmlHelper.fillIn("[Same]", metadata));
  }

  /** Ordinary resolution is untouched. */
  @Test(timeout = 15000)
  public void aresolvableValueStillResolves() throws Exception {
    Metadata metadata = new Metadata();
    metadata.addMetadata("Dir", "/archive");

    assertEquals("/archive/f.dat", XmlHelper.fillIn("[Dir]/f.dat", metadata));
  }

  /** Nested resolution, which is the reason the loop exists at all. */
  @Test(timeout = 15000)
  public void anestedValueStillResolvesThroughSeveralPasses() throws Exception {
    Metadata metadata = new Metadata();
    metadata.addMetadata("Outer", "[Middle]");
    metadata.addMetadata("Middle", "[Inner]");
    metadata.addMetadata("Inner", "done");

    assertEquals("done", XmlHelper.fillIn("[Outer]", metadata));
  }

  @Test(timeout = 15000)
  public void withRecursionOffOnePassIsStillMade() throws Exception {
    Metadata metadata = new Metadata();
    metadata.addMetadata("Outer", "[Middle]");
    metadata.addMetadata("Middle", "done");

    assertEquals("[Middle]", XmlHelper.fillIn("[Outer]", metadata, false));
  }
}
