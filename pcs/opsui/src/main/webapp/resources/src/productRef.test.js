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
import { productRoute } from './productRef.js'

test('an id is passed through unchanged', () => {
  assert.deepEqual(productRoute('58b57ea7-a4ae-11f1-8b12-0bb513b16329'), {
    view: 'product',
    id: '58b57ea7-a4ae-11f1-8b12-0bb513b16329'
  })
})

test('a path resolves to the name the catalog knows the product by', () => {
  // InputFiles on a RAT audit instance looks exactly like this
  assert.deepEqual(
    productRoute('/Users/mattmann/git/tika/tika-parsers/src/test/resources/testJAR.jar'),
    { view: 'product', id: 'testJAR.jar' }
  )
})

test('a trailing slash does not swallow the name', () => {
  assert.deepEqual(productRoute('/a/b/report.log/'), {
    view: 'product',
    id: 'report.log'
  })
})

test('a relative path is treated the same as an absolute one', () => {
  assert.deepEqual(productRoute('output/rat_x-java-source_123.log'), {
    view: 'product',
    id: 'rat_x-java-source_123.log'
  })
})

test('a name with no separators is left alone', () => {
  assert.deepEqual(productRoute('testJAR.jar'), {
    view: 'product',
    id: 'testJAR.jar'
  })
})

test('nothing to open yields no route', () => {
  assert.equal(productRoute(''), null)
  assert.equal(productRoute(null), null)
  assert.equal(productRoute(undefined), null)
})

test('a path of only separators yields no route', () => {
  assert.equal(productRoute('///'), null)
})
