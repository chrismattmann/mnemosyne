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

/**
 * What to ask the workflow service for, given the route being shown.
 *
 * The filter has to travel with the request. Filtering a page after it
 * arrives leaves the paging to walk every instance in the repository, so a
 * workflow with a single run sits pages deep behind pages that look empty --
 * everything on them belongs to some other workflow. The service filters and
 * returns the matches as one page, which is also the honest page count.
 *
 * @param {object} route the current route
 * @returns {{status: string, page: number, workflow: string}} the request
 */
export function instancesRequest(route) {
  const r = route || {}
  return {
    status: r.status || 'ALL',
    page: Number(r.page) > 0 ? Number(r.page) : 1,
    workflow: r.workflow || ''
  }
}
