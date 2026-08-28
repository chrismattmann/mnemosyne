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
 * Calendar date as written on the instance (YYYY-MM-DD), not the UTC
 * day of the instant. 20:28 PDT on the 27th is the 28th in UTC; the
 * Started column still says the 27th, so the filter must too.
 */
export function instanceStartDate(iso) {
  const match = String(iso || '').match(/^(\d{4}-\d{2}-\d{2})/)
  return match ? match[1] : ''
}

export function instanceMatches(inst, workflow, since) {
  if (!inst) {
    return false
  }
  if (workflow) {
    const name = inst.workflowName || ''
    const id = inst.workflowId || ''
    if (name !== workflow && id !== workflow) {
      return false
    }
  }
  if (since) {
    const day = instanceStartDate(inst.startDateTime)
    if (day && day < since) {
      return false
    }
  }
  return true
}

export function workflowFilterOptions(instances, definitions) {
  const seen = {}
  const out = []
  function add(name, id) {
    const key = name || id
    if (!key || seen[key]) {
      return
    }
    seen[key] = true
    out.push({ name: name || id, id: id || name })
  }
  ;(instances || []).forEach((inst) => add(inst.workflowName, inst.workflowId))
  ;(definitions || []).forEach((wf) => add(wf.name, wf.id))
  out.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
  return out
}
