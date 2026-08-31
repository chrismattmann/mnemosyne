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
 * The statuses to offer in the instance filter.
 *
 * The list used to be written into the component: a union of the states two
 * different engines produce. Against either one it offered states that never
 * occur, and it carried "QUEUED" where the queue-based engine reports
 * "Queued", so the filter for the most common state on that deployment
 * matched nothing at all.
 *
 * The deployment's own lifecycle is the authority. Its statuses are used
 * as given, case included. The built-in list remains only for a manager too
 * old to be asked, so an upgraded UI still works against one.
 *
 * @param {string[]} reported statuses the workflow manager reported
 * @param {string[]} fallback statuses to use when it reported none
 * @returns {string[]} the options, ALL first, without duplicates
 */
export function statusOptions(reported, fallback) {
  const source = (reported && reported.length) ? reported : (fallback || [])
  const options = ['ALL']
  for (const status of source) {
    if (status && status !== 'ALL' && !options.includes(status)) {
      options.push(status)
    }
  }
  return options
}
