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

package org.apache.oodt.cas.resource.structs;

/**
 * Turns a {@link JobStatus} constant into something worth showing a person.
 *
 * This lived as a static on the XML-RPC resource manager client, which had
 * nothing to do with it: mapping <code>__Queued__</code> to
 * <code>QUEUED</code> involves no transport at all. It was reachable only by
 * importing a client you did not otherwise want, and it went when that client
 * did.
 *
 * @author mattmann
 */
public final class JobStatusNames {

    private JobStatusNames() {
    }

    /**
     * @param status
     *          One of the {@link JobStatus} constants.
     * @return A readable name, or null if the status is not one of them, which
     *         is what the original did.
     */
    public static String getReadableJobStatus(String status) {
        if (status == null) {
            return null;
        } else if (status.equals(JobStatus.SUCCESS)) {
            return "SUCCESS";
        } else if (status.equals(JobStatus.FAILURE)) {
            return "FAILURE";
        } else if (status.equals(JobStatus.EXECUTED)) {
            return "EXECUTED";
        } else if (status.equals(JobStatus.QUEUED)) {
            return "QUEUED";
        } else if (status.equals(JobStatus.SCHEDULED)) {
            return "SCHEDULED";
        } else if (status.equals(JobStatus.KILLED)) {
            return "KILLED";
        } else {
            return null;
        }
    }
}
