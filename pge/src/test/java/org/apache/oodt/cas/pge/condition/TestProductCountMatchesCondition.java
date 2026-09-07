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
package org.apache.oodt.cas.pge.condition;

import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.workflow.structs.WorkflowConditionConfiguration;

import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Map;

/**
 * Whether the gathering stage has waited for everything it gathers.
 *
 * <p>
 * The pipeline this exists for splits work into pieces, produces one
 * product per piece, and must not gather until every piece has an answer.
 * Gated on a pause instead, it gathered seven of ten and reported success:
 * every record present, and the ones belonging to the missing three left
 * as they arrived.
 * </p>
 */
public class TestProductCountMatchesCondition extends TestCase {

  /** Counts whatever it is told to, without a file manager. */
  private static class Catalog extends ProductCountMatchesCondition {
    private final Map<String, Integer> counts = new HashMap<String, Integer>();

    Catalog answers(String typeName, int count) {
      counts.put(typeName, Integer.valueOf(count));
      return this;
    }

    @Override
    protected int countProducts(String urlStr, String typeName) {
      Integer known = counts.get(typeName);
      return known == null ? 0 : known.intValue();
    }
  }

  private WorkflowConditionConfiguration config(String... pairs) {
    WorkflowConditionConfiguration config =
        new WorkflowConditionConfiguration();
    config.addConfigProperty("FileManagerUrl", "http://localhost:9000");
    config.addConfigProperty("ProductTypeName", "Answers");
    config.addConfigProperty("MatchesProductTypeName", "Pieces");
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      config.addConfigProperty(pairs[i], pairs[i + 1]);
    }
    return config;
  }

  public void testHoldsWhileAnswersAreOutstanding() {
    Catalog condition = new Catalog().answers("Pieces", 458)
        .answers("Answers", 437);
    assertFalse("twenty one pieces have no answer yet",
        condition.evaluate(new Metadata(), config()));
  }

  public void testPassesWhenEveryPieceHasAnAnswer() {
    Catalog condition = new Catalog().answers("Pieces", 458)
        .answers("Answers", 458);
    assertTrue(condition.evaluate(new Metadata(), config()));
  }

  public void testPassesIfAnswersSomehowExceedPieces() {
    // A re-run can leave more answers than pieces. That is not a reason to
    // wait forever for a number that will never be reached exactly.
    Catalog condition = new Catalog().answers("Pieces", 10)
        .answers("Answers", 12);
    assertTrue(condition.evaluate(new Metadata(), config()));
  }

  public void testAnEmptyCatalogIsNotSatisfaction() {
    // Zero does equal zero, which would open the gate before the producing
    // stage had made anything at all.
    Catalog condition = new Catalog().answers("Pieces", 0)
        .answers("Answers", 0);
    assertFalse("nothing has been produced yet",
        condition.evaluate(new Metadata(), config()));
  }

  public void testTheFloorIsConfigurable() {
    Catalog condition = new Catalog().answers("Pieces", 3)
        .answers("Answers", 3);
    assertFalse("three pieces is below the floor of five",
        condition.evaluate(new Metadata(), config("MinCount", "5")));
    assertTrue(condition.evaluate(new Metadata(), config("MinCount", "3")));
  }

  public void testACountThatCouldNotBeReadHolds() {
    // Not the same as a count of zero. Reading a failed query as "nothing
    // to wait for" would start the gathering against whatever happened to
    // be catalogued when the file manager went away.
    ProductCountMatchesCondition condition =
        new ProductCountMatchesCondition() {
          @Override
          protected int countProducts(String urlStr, String typeName) {
            return -1;
          }
        };
    assertFalse(condition.evaluate(new Metadata(), config()));
  }

  public void testMissingConfigurationHoldsRatherThanPasses() {
    WorkflowConditionConfiguration bare =
        new WorkflowConditionConfiguration();
    bare.addConfigProperty("FileManagerUrl", "http://localhost:9000");
    assertFalse("without both type names there is nothing to compare",
        new Catalog().evaluate(new Metadata(), bare));
  }

  public void testAnUnreadableFloorFallsBackRatherThanThrowing() {
    Catalog condition = new Catalog().answers("Pieces", 4)
        .answers("Answers", 4);
    assertTrue(condition.evaluate(new Metadata(),
        config("MinCount", "not a number")));
  }
}
