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
package org.apache.oodt.cas.workflow.examples;

/**
 * Where this class used to live.
 *
 * <p>It moved to {@link org.apache.oodt.cas.workflow.tasks.BranchRedirector},
 * because it is not an example: {@code PackagedWorkflowRepository} generates one
 * for every nested workflow it flattens, so it is the mechanism by which a
 * nested W2 workflow runs at all. Sitting in a package called "examples" said
 * the opposite, and that mattered — a DRAT audit lost a completed 3,503-file
 * ingest to a defect in it, and the class's location made it look optional.</p>
 *
 * <p>This subclass stays because a workflow task is named in configuration by
 * its fully qualified class name. Policy written by hand, and any task
 * definition persisted by an earlier release, can still say
 * {@code org.apache.oodt.cas.workflow.examples.BranchRedirector} — and a
 * renamed class would fail those at load time, which is the one moment an
 * operator has no good way to diagnose. It carries no behaviour of its own.</p>
 *
 * @deprecated use {@link org.apache.oodt.cas.workflow.tasks.BranchRedirector}.
 *     Kept so existing configuration keeps working; scheduled for removal once
 *     no deployment names this package.
 */
@Deprecated
public class BranchRedirector
        extends org.apache.oodt.cas.workflow.tasks.BranchRedirector {

    public BranchRedirector() {
        super();
    }
}
