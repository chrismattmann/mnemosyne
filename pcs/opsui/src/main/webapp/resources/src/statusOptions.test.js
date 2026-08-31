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
import { statusOptions } from './statusOptions.js'

const BUILT_IN = ['QUEUED', 'STARTED', 'PAUSED']

test('the lifecycle the manager reports is what gets offered', () => {
  assert.deepEqual(
    statusOptions(['Null', 'Loaded', 'Queued', 'Executing', 'Success'], BUILT_IN),
    ['ALL', 'Null', 'Loaded', 'Queued', 'Executing', 'Success']
  )
})

test('case is kept as the lifecycle declares it', () => {
  // "QUEUED" would not match an instance reporting "Queued"
  const options = statusOptions(['Queued'], BUILT_IN)
  assert.ok(options.includes('Queued'))
  assert.ok(!options.includes('QUEUED'))
})

test('a manager that reports nothing falls back to the built-in list', () => {
  assert.deepEqual(statusOptions([], BUILT_IN), ['ALL', 'QUEUED', 'STARTED', 'PAUSED'])
  assert.deepEqual(statusOptions(null, BUILT_IN), ['ALL', 'QUEUED', 'STARTED', 'PAUSED'])
})

test('ALL is offered once, even if the lifecycle names it', () => {
  assert.deepEqual(statusOptions(['ALL', 'Queued'], BUILT_IN), ['ALL', 'Queued'])
})

test('duplicates and blanks are dropped', () => {
  assert.deepEqual(statusOptions(['Queued', 'Queued', '', null, 'Success'], BUILT_IN),
    ['ALL', 'Queued', 'Success'])
})

test('nothing anywhere still yields a usable list', () => {
  assert.deepEqual(statusOptions([], []), ['ALL'])
})
