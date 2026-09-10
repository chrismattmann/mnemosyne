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
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { sortRows, toggleSort } from './sort.js'
import { visibleJobs, JOB_PAGE_SIZE } from './jobPages.js'

/*
 * A queue is read to answer "what is stuck" and "what is waiting on which
 * queue". Neither question survives an arbitrary row order, and neither
 * survives paging that reveals rows in a different order from the one on
 * screen.
 *
 * It cannot answer "what is this node running": a job in the queue has not
 * been dispatched, so it has no node. That column was removed rather than
 * left to render an em dash on every row.
 */

const getter = (field) => (job) => {
  const value = job ? job[field] : null
  return field === 'load' && value != null ? Number(value) : value
}

test('a queue can be ordered by the queue a job is waiting in', () => {
  const jobs = [
    { id: 'c', queue: 'gpu' }, { id: 'a', queue: 'local' }, { id: 'b', queue: 'gpu' }
  ]
  const byQueue = sortRows(jobs, getter('queue'), 'asc')
  assert.deepEqual(byQueue.map((j) => j.id), ['c', 'b', 'a'])
})

test('load sorts as a number, not as text', () => {
  const jobs = [{ id: 'a', load: 10 }, { id: 'b', load: 9 }, { id: 'c', load: 2 }]
  assert.deepEqual(
    sortRows(jobs, getter('load'), 'asc').map((j) => j.id),
    ['c', 'b', 'a'],
    'as strings "10" would sort before "9"')
})

test('a second click reverses, a third field starts ascending', () => {
  let s = toggleSort('queue', '', 'asc')
  assert.equal(s.field, 'queue')
  const first = s.dir
  s = toggleSort('queue', s.field, s.dir)
  assert.notEqual(s.dir, first, 'clicking the same column reverses it')
  s = toggleSort('status', s.field, s.dir)
  assert.equal(s.field, 'status')
})

test('missing values sort last rather than first', () => {
  const jobs = [{ id: 'a', queue: null }, { id: 'b', queue: 'gpu' }]
  assert.deepEqual(sortRows(jobs, getter('queue'), 'asc').map((j) => j.id), ['b', 'a'])
})

/*
 * The interaction that matters: sorting happens before paging, so the first
 * page is the first page of what is on screen. Sorting after paging would
 * order only the rows already drawn, and "Load more" would then append rows
 * that belong above them.
 */
test('paging shows the first page of the sorted queue, not of the raw one', () => {
  const jobs = Array.from({ length: 120 }, (_, i) => ({ id: 'job-' + i, load: 120 - i }))
  const sorted = sortRows(jobs, getter('load'), 'asc')
  const page = visibleJobs(sorted, JOB_PAGE_SIZE)
  assert.equal(page.length, JOB_PAGE_SIZE)
  assert.equal(page[0].load, 1, 'the lowest load is on the first page')
  assert.equal(page[JOB_PAGE_SIZE - 1].load, JOB_PAGE_SIZE)
})
