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

export function splitHash(raw) {
  const value = String(raw || '').replace(/^#\/?/, '')
  const q = value.indexOf('?')
  if (q < 0) {
    return { path: value, query: {} }
  }
  const query = {}
  const params = new URLSearchParams(value.slice(q + 1))
  params.forEach((v, k) => {
    query[k] = v
  })
  return { path: value.slice(0, q), query: query }
}

export function instancesQuery(workflow, since, sort, dir) {
  const params = new URLSearchParams()
  if (workflow) {
    params.set('workflow', workflow)
  }
  if (since) {
    params.set('since', since)
  }
  // In the hash so a sorted view is a link someone can send.
  if (sort) {
    params.set('sort', sort)
    params.set('dir', dir === 'desc' ? 'desc' : 'asc')
  }
  const encoded = params.toString()
  return encoded ? '?' + encoded : ''
}
