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
import {
  JOB_PAGE_SIZE,
  clampShown,
  hasMoreJobs,
  moreJobsLabel,
  nextShown,
  remainingJobs,
  visibleJobs
} from './jobPages.js'

function jobs(n) {
  return Array.from({ length: n }, (_, i) => ({ id: 'job-' + i }))
}

test('a long queue draws one page, not all of it', () => {
  const queue = jobs(700)
  assert.equal(visibleJobs(queue, JOB_PAGE_SIZE).length, JOB_PAGE_SIZE)
  assert.equal(hasMoreJobs(queue, JOB_PAGE_SIZE), true)
  assert.equal(remainingJobs(queue, JOB_PAGE_SIZE), 650)
})

test('a short queue draws entirely and offers no button', () => {
  const queue = jobs(12)
  assert.equal(visibleJobs(queue, JOB_PAGE_SIZE).length, 12)
  assert.equal(hasMoreJobs(queue, JOB_PAGE_SIZE), false)
  assert.equal(remainingJobs(queue, JOB_PAGE_SIZE), 0)
})

test('load more advances a page and stops at the end', () => {
  const queue = jobs(120)
  const second = nextShown(queue, JOB_PAGE_SIZE)
  assert.equal(second, 100)
  assert.equal(visibleJobs(queue, second).length, 100)

  const third = nextShown(queue, second)
  assert.equal(third, 120, 'the last page is short, not overshot')
  assert.equal(hasMoreJobs(queue, third), false)
})

/*
 * The view polls. Re-asking with the same `shown` must not collapse a queue
 * the user has expanded, which is what a reset on every refresh would do.
 */
test('an expanded queue survives a refresh', () => {
  const queue = jobs(700)
  const expanded = nextShown(queue, nextShown(queue, JOB_PAGE_SIZE))
  assert.equal(expanded, 150)
  assert.equal(visibleJobs(queue, expanded).length, 150,
    'the same shown count against a fresh payload draws the same rows')
})

/* And a queue that has drained does not keep claiming rows it no longer has. */
test('shrinking below what was expanded clamps to what is there', () => {
  assert.equal(clampShown(600, 20), 20)
  assert.equal(visibleJobs(jobs(20), 600).length, 20)
  assert.equal(hasMoreJobs(jobs(20), 600), false)
})

test('an empty queue shows nothing and offers nothing', () => {
  assert.equal(visibleJobs([], JOB_PAGE_SIZE).length, 0)
  assert.equal(hasMoreJobs([], JOB_PAGE_SIZE), false)
  assert.equal(clampShown(JOB_PAGE_SIZE, 0), 0)
})

test('missing or malformed input is treated as an empty queue', () => {
  for (const bad of [null, undefined, 'nope', 42, {}]) {
    assert.deepEqual(visibleJobs(bad, JOB_PAGE_SIZE), [])
    assert.equal(hasMoreJobs(bad, JOB_PAGE_SIZE), false)
  }
})

test('the label matches the catalog and counts what is left', () => {
  assert.equal(moreJobsLabel(jobs(700), JOB_PAGE_SIZE),
    'Load more · 650 remaining')
  assert.equal(moreJobsLabel(jobs(10), JOB_PAGE_SIZE), 'Load more')
})

test('a nonsense page size falls back rather than drawing nothing', () => {
  const queue = jobs(700)
  for (const bad of [0, -5, null, undefined, 'ten']) {
    assert.equal(visibleJobs(queue, JOB_PAGE_SIZE, bad).length, JOB_PAGE_SIZE)
  }
})
