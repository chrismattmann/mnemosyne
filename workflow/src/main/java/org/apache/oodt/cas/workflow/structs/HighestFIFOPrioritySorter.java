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

package org.apache.oodt.cas.workflow.structs;

//JDK imports
import org.apache.oodt.cas.workflow.engine.processor.WorkflowProcessor;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports

/**
 * 
 * Sorts based on highest {@link Priority} for a {@link WorkflowProcessor} using
 * a particular {@link #boostAmount} provided by a calling party. The
 * {@link #boostAmount} is computed based on a function that allows
 * {@link #boostAmount} to grow over time depending on
 * {@link #secondsBetweenBoosts} and a maximum {@link #boostCap}.
 * 
 * @author bfoster
 * @author mattmann
 * @version $Revision$
 * 
 */
public class HighestFIFOPrioritySorter implements PrioritySorter {

  public static final double DOUBLE = 1000.0;
  private int secondsBetweenBoosts;
  private double boostAmount;
  private double boostCap;
  private static final Logger LOG = Logger
      .getLogger(HighestFIFOPrioritySorter.class.getName());

  /** Seconds between boosts when none is configured. */
  public static final int DEFAULT_SECONDS_BETWEEN_BOOSTS = 1;

  /** Boost applied to a waiting instance when none is configured. */
  public static final double DEFAULT_BOOST_AMOUNT = 50.0;

  /** Ceiling a boosted priority will not pass when none is configured. */
  public static final double DEFAULT_BOOST_CAP = 1.0;

  /**
   * Constructs a sorter with default boost settings.
   *
   * <p>
   * GenericWorkflowObjectFactory resolves the
   * org.apache.oodt.cas.workflow.wengine.prioritizer property with
   * Class.newInstance(), which needs a no-arg constructor. Without one this
   * class -- the only sorter that ages a waiting instance up, and so the only
   * one that stops a low-priority instance starving -- could not be named in
   * workflow.properties at all: it threw InstantiationException, the engine
   * was left null, and the workflow manager failed to start.
   * </p>
   */
  public HighestFIFOPrioritySorter() {
    this(DEFAULT_SECONDS_BETWEEN_BOOSTS, DEFAULT_BOOST_AMOUNT,
        DEFAULT_BOOST_CAP);
  }

  public HighestFIFOPrioritySorter(int secondsBetweenBoosts,
      double boostAmount, double boostCap) {
    this.secondsBetweenBoosts = secondsBetweenBoosts;
    this.boostAmount = boostAmount;
    this.boostCap = boostCap;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.structs.PrioritySorter#sort(java.util.List)
   */
  @Override
  public synchronized void sort(List<WorkflowProcessor> candidates) {

    Collections.sort(candidates, new Comparator<WorkflowProcessor>() {
      public int compare(WorkflowProcessor o1, WorkflowProcessor o2) {
        return calculatePriority(o2).compareTo(calculatePriority(o1));
      }
    });
  }

  private Double calculatePriority(WorkflowProcessor processorStub) {
    double aliveTime;

    try {
      aliveTime = (double) (System.currentTimeMillis() - processorStub
          .getWorkflowInstance().getCreationDate().getTime());
    } catch (Exception e) {
      LOG.log(Level.SEVERE, e.getMessage());
      LOG.log(Level.WARNING,
          "Unable to compute aliveTime for computing FIFO priority: Reason: ["
              + e.getMessage() + "]");
      aliveTime = 0.0;
    }

    double boostPercentage = aliveTime / DOUBLE
        / (double) this.secondsBetweenBoosts;
    return Math.max(
        processorStub.getWorkflowInstance().getPriority().getValue(),
        Math.min(
            this.boostCap,
            processorStub.getWorkflowInstance().getPriority()
                         .getValue()
            + (boostPercentage * this.boostAmount)));
  }

}
