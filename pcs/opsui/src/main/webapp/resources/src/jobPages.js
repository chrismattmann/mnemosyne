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

/*
 * Showing the queue a page at a time.
 *
 * The resources view rendered every queued job in one table. A two node run
 * of a few hundred chunks puts seven hundred jobs in that queue, and the page
 * that is supposed to tell you what the cluster is doing becomes the reason
 * you cannot read it.
 *
 * Unlike the product catalog, which pages against the File Manager, the whole
 * job list arrives in a single payload. So this is a rendering limit rather
 * than a fetch: nothing more is requested, fewer rows are drawn.
 */

export const JOB_PAGE_SIZE = 50

function size(step) {
  const asNumber = Number(step)
  return asNumber > 0 ? asNumber : JOB_PAGE_SIZE
}

function count(jobs) {
  return Array.isArray(jobs) ? jobs.length : 0
}

/**
 * How many rows to draw: at least one page, never more than there are, and
 * never fewer than the caller has already expanded to.
 *
 * The view polls, so this is asked again every refresh with the same `shown`.
 * Clamping rather than resetting is what keeps a queue the user has expanded
 * from collapsing under them each time the poll lands.
 */
export function clampShown(shown, total, step) {
  const perPage = size(step)
  const available = Math.max(0, Number(total) || 0)
  const wanted = Number(shown) > 0 ? Number(shown) : perPage
  return Math.min(Math.max(wanted, perPage), available)
}

export function visibleJobs(jobs, shown, step) {
  const list = Array.isArray(jobs) ? jobs : []
  return list.slice(0, clampShown(shown, list.length, step))
}

export function hasMoreJobs(jobs, shown, step) {
  return clampShown(shown, count(jobs), step) < count(jobs)
}

export function remainingJobs(jobs, shown, step) {
  return Math.max(0, count(jobs) - clampShown(shown, count(jobs), step))
}

/**
 * The label the catalog uses, so the two pages behave the same way and read
 * the same way.
 */
export function moreJobsLabel(jobs, shown, step) {
  const remaining = remainingJobs(jobs, shown, step)
  return remaining > 0 ? 'Load more · ' + remaining + ' remaining' : 'Load more'
}

/** One more page, stopping at the end of the list. */
export function nextShown(jobs, shown, step) {
  const perPage = size(step)
  return Math.min(
    clampShown(shown, count(jobs), step) + perPage,
    Math.max(count(jobs), perPage)
  )
}
