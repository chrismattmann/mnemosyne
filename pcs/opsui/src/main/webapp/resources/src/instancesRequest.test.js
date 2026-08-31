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
import { instancesRequest } from './instancesRequest.js'

test('a workflow filter travels with the request', () => {
  // Left behind, the service pages over every instance and the filtering
  // happens afterwards, on whatever that page happened to contain.
  assert.deepEqual(
    instancesRequest({ view: 'instances', workflow: 'urn:drat:PartitionPhase' }),
    { status: 'ALL', page: 1, workflow: 'urn:drat:PartitionPhase', sort: '', dir: '' }
  )
})

test('status and page travel too', () => {
  assert.deepEqual(
    instancesRequest({ status: 'Success', page: 3, workflow: 'urn:x:Y' }),
    { status: 'Success', page: 3, workflow: 'urn:x:Y', sort: '', dir: '' }
  )
})

test('no filter asks for everything', () => {
  assert.deepEqual(instancesRequest({ view: 'instances' }),
    { status: 'ALL', page: 1, workflow: '', sort: '', dir: '' })
})

test('a missing or nonsense page is the first one', () => {
  assert.equal(instancesRequest({}).page, 1)
  assert.equal(instancesRequest({ page: 0 }).page, 1)
  assert.equal(instancesRequest({ page: -2 }).page, 1)
  assert.equal(instancesRequest({ page: 'x' }).page, 1)
})

test('a page arriving as a string is still a page', () => {
  assert.equal(instancesRequest({ page: '4' }).page, 4)
})

test('no route at all is survivable', () => {
  assert.deepEqual(instancesRequest(null),
    { status: 'ALL', page: 1, workflow: '', sort: '', dir: '' })
})

test('a sort travels with the request', () => {
  // Sorting the page after it arrives orders the twenty rows that happened
  // to come back, so the longest wall clock in the deployment stays hidden
  // on some other page.
  assert.deepEqual(
    instancesRequest({ view: 'instances', sort: 'wall', dir: 'desc' }),
    { status: 'ALL', page: 1, workflow: '', sort: 'wall', dir: 'desc' }
  )
})

test('a sort with no direction is ascending', () => {
  const ask = instancesRequest({ sort: 'start' })
  assert.equal(ask.dir, 'asc')
})

test('a nonsense direction is ascending', () => {
  assert.equal(instancesRequest({ sort: 'start', dir: 'sideways' }).dir, 'asc')
})

test('a direction with no column to point at is not sent', () => {
  assert.equal(instancesRequest({ dir: 'desc' }).dir, '')
})
