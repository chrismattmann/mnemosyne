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
package org.apache.oodt.cas.pge.metadata;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.oodt.cas.metadata.Metadata;

import junit.framework.TestCase;

public class TestPgeProgress extends TestCase {

  public void testReadsDotProgressFile() throws Exception {
    File file = File.createTempFile("pge", ".progress");
    file.deleteOnExit();
    Files.write(file.toPath(),
        "done=50\ntotal=612\nmsg=encoded\n".getBytes(StandardCharsets.UTF_8));
    PgeProgress progress = PgeProgress.readFile(file);
    assertEquals(Integer.valueOf(50), progress.getDone());
    assertEquals(Integer.valueOf(612), progress.getTotal());
    assertEquals("encoded", progress.getMessage());
  }

  public void testMissingFileIsNull() {
    assertNull(PgeProgress.readFile(new File("/no/such/.progress")));
    assertNull(PgeProgress.fromMetadata(new Metadata()));
  }

  public void testRoundTripMetadataBothKeyStyles() {
    PgeProgress progress = new PgeProgress(Integer.valueOf(3), Integer.valueOf(10), "split");
    Metadata met = progress.toMetadata();
    assertEquals("3", met.getMetadata("PGETask_Done"));
    assertEquals("3", met.getMetadata("PGETask/Done"));
    PgeProgress back = PgeProgress.fromMetadata(met);
    assertTrue(progress.sameAs(back));
  }

  public void testUnchangedSnapshotIsSame() {
    PgeProgress a = new PgeProgress(Integer.valueOf(1), Integer.valueOf(2), "encoded");
    PgeProgress b = new PgeProgress(Integer.valueOf(1), Integer.valueOf(2), "encoded");
    assertTrue(a.sameAs(b));
    assertFalse(a.sameAs(new PgeProgress(Integer.valueOf(2), Integer.valueOf(2), "encoded")));
  }

  public void testClearFromDropsBothKeyStyles() {
    Metadata met = new Metadata();
    met.addMetadata("PGETask_Progress", "jaccard");
    met.addMetadata("PGETask/Progress", "jaccard");
    met.addMetadata("PGETask_Done", "0");
    met.addMetadata("PGETask/Done", "0");
    met.addMetadata("PGETask_Total", "653");
    met.addMetadata("Filename", "keep-me");
    PgeProgress.clearFrom(met);
    assertNull(PgeProgress.fromMetadata(met));
    assertEquals("keep-me", met.getMetadata("Filename"));
  }

  public void testApplyToOverwritesStaleKeys() {
    Metadata met = new Metadata();
    met.addMetadata("PGETask_Progress", "jaccard");
    met.addMetadata("PGETask_Done", "0");
    new PgeProgress(Integer.valueOf(653), Integer.valueOf(653), "bg CLIP").applyTo(met);
    assertEquals("bg CLIP", met.getMetadata("PGETask_Progress"));
    assertEquals("653", met.getMetadata("PGETask_Done"));
  }
}
