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

package org.apache.oodt.cas.resource.mux;

import org.apache.oodt.cas.resource.scheduler.Scheduler;
import org.apache.oodt.cas.resource.scheduler.SchedulerFactory;

/**
 * Builds {@link StubScheduler}s.
 *
 * <p>{@link XmlBackendRepository} loads a scheduler by class name out of the
 * queue-to-backend file, so naming this factory in a generated file is the
 * supported way to get a scheduler the test controls — no real monitor, batch
 * manager, node repository or socket required.
 */
public class StubSchedulerFactory implements SchedulerFactory {

  @Override
  public Scheduler createScheduler() {
    return new StubScheduler();
  }
}
