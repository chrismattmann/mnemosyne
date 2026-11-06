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
 * <p>It moved to {@link org.apache.oodt.cas.workflow.tasks.NoOpTask}, for the
 * same reason {@code BranchRedirector} did: all three workflow repositories
 * generate one for every workflow carrying global conditions, so it is
 * mechanism rather than illustration, and a package named "examples" said
 * otherwise.</p>
 *
 * <p>This subclass stays because this name is already written down in places
 * this release cannot reach. It is more exposed than the redirector was: the
 * three repositories persist it by name into generated task definitions, so
 * every workflow instance a previous release stored with global conditions
 * names <em>this</em> package in a catalog that outlives the upgrade. Policy
 * written by hand names it too. Renaming the class would fail all of those at
 * load time. It carries no behaviour of its own.</p>
 *
 * @deprecated use {@link org.apache.oodt.cas.workflow.tasks.NoOpTask}. Kept so
 *     existing configuration and stored instances keep working; scheduled for
 *     removal once no deployment names this package.
 */
@Deprecated
public class NoOpTask extends org.apache.oodt.cas.workflow.tasks.NoOpTask {

    public NoOpTask() {
        super();
    }
}
