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

import { instanceAbandoned, instanceTerminal } from './workflowGraph.js'

export function compareValues(a, b, dir) {
  const mul = dir === 'desc' ? -1 : 1
  const emptyA = a == null || a === ''
  const emptyB = b == null || b === ''
  if (emptyA && emptyB) {
    return 0
  }
  if (emptyA) {
    return 1
  }
  if (emptyB) {
    return -1
  }
  if (typeof a === 'number' && typeof b === 'number') {
    if (a === b) {
      return 0
    }
    return (a < b ? -1 : 1) * mul
  }
  return String(a).localeCompare(String(b), undefined, {
    numeric: true,
    sensitivity: 'base'
  }) * mul
}

export function sortRows(rows, getter, dir) {
  return (rows || []).slice().sort((a, b) => compareValues(getter(a), getter(b), dir))
}

export function toggleSort(field, currentField, currentDir) {
  if (currentField === field) {
    return { field, dir: currentDir === 'asc' ? 'desc' : 'asc' }
  }
  return { field, dir: 'asc' }
}

export function parseStamp(iso) {
  if (!iso) {
    return null
  }
  const t = Date.parse(iso)
  return Number.isFinite(t) ? t : null
}

export function wallClockMs(startIso, endIso, now, status, running) {
  const start = parseStamp(startIso)
  if (start == null) {
    return null
  }
  const end = parseStamp(endIso)
  if (end != null) {
    const ms = end - start
    return ms < 0 ? 0 : ms
  }
  if (instanceTerminal(status)) {
    return null
  }
  if (instanceAbandoned({ status, endDateTime: endIso, running })) {
    return null
  }
  const clock = now || Date.now()
  const ms = clock - start
  return ms < 0 ? 0 : ms
}

export function formatWallClock(ms) {
  if (ms == null) {
    return '—'
  }
  const total = Math.floor(ms / 1000)
  const sec = total % 60
  const min = Math.floor(total / 60)
  return min + '.' + String(sec).padStart(2, '0')
}
