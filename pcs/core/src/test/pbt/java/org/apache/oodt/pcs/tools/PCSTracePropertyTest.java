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

package org.apache.oodt.pcs.tools;

import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

/**
 * Properties of the trace report {@link PCSTrace} prints.
 *
 * <p>A trace is what an operator runs when a product looks wrong. The file
 * manager here is a closed port, which is the situation the tool is most likely
 * to meet in anger — a mistyped URL, a manager that has not come up yet — and
 * the report still has to be a report rather than a stack trace.
 */
class PCSTracePropertyTest {

  /** URLs nothing is listening on. */
  private static final String DEAD_FM = "http://127.0.0.1:1";
  private static final String DEAD_WM = "http://127.0.0.1:2";

  private static final Generator<String> PRODUCT_NAME =
      text().minSize(1).maxSize(16).categories("Lu", "Ll", "Nd");

  private static final Generator<String> TYPE_NAME =
      text().minSize(1).maxSize(10).categories("Lu", "Ll", "Nd");

  /**
   * A trace against a PCS that cannot answer still produces a report with all
   * of its sections, and never a raw exception. The operator needs to be told
   * that nothing is known, which is a different thing from the tool falling
   * over.
   */
  @HegelTest(testCases = 8)
  void aTraceAgainstAnUnreachablePcsStillProducesAReport(TestCase tc) throws Exception {
    String productName = tc.draw(PRODUCT_NAME, "productName");
    List<String> excluded = tc.draw(lists(TYPE_NAME).minSize(0).maxSize(4), "excluded");
    boolean listNotCataloged = tc.draw(dev.hegel.Generators.booleans(), "listNotCataloged");

    PrintStream originalOut = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      PCSTrace tracer = new PCSTrace(DEAD_WM, DEAD_FM);
      tracer.setExcludeTypeList(excluded);
      if (listNotCataloged) {
        tracer.enableNonCatalogProductsInPed();
      }

      System.setOut(new PrintStream(captured, true, "UTF-8"));
      tracer.doTrace(productName);
      System.setOut(originalOut);

      String report = captured.toString("UTF-8");
      assertTrue(report.contains("Product: "), "the report has no product section");
      assertTrue(report.contains("Location: "), "the report has no location section");
      assertTrue(report.contains("Metadata: "), "the report has no metadata section");
      assertTrue(report.contains("Full lineage:"), "the report has no lineage section");
      assertTrue(report.contains("Downstream:"), "the report has no downstream lineage");
      assertTrue(report.contains("Upstream:"), "the report has no upstream lineage");
    } finally {
      System.setOut(originalOut);
    }
  }

  /**
   * The exclude list a caller sets is the exclude list the tracer uses. It is
   * handed straight to the pedigree walk, and silently losing it would widen a
   * lineage query across product types the operator explicitly ruled out.
   */
  @HegelTest(testCases = 8)
  void theExcludeListIsKeptAsGiven(TestCase tc) {
    List<String> excluded = tc.draw(lists(TYPE_NAME).minSize(0).maxSize(6), "excluded");

    PCSTrace tracer = new PCSTrace(DEAD_WM, DEAD_FM);
    tracer.setExcludeTypeList(excluded);

    assertEquals(excluded, tracer.getExcludeTypeList(), "the exclude list changed");
  }
}
