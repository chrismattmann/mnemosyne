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

const IMPL_TOKENS = [
  'mappeddatasource',
  'datasource',
  'xmlrpc',
  'avrorpc',
  'packagedrepo',
  'threadpool',
  'jobstack',
  'lucene',
  'solr',
  'avro',
  'xml',
  'local',
  'remote',
  'memory',
  'xstream',
  'wengine'
]

const PKG_ALIASES = {
  repository: ['repositorymgr', 'repo', 'wengine'],
  instrepo: ['instanceRep']
}

export function groupConfigRows(rows) {
  const list = Array.isArray(rows) ? rows : []
  const factories = collectFactories(list)
  const blocks = []
  for (let i = 0; i < list.length; i++) {
    const row = list[i]
    const inactive = classifyInactive(row.key, factories)
    if (!inactive) {
      blocks.push({
        type: 'row',
        id: 'row:' + row.key,
        row
      })
      continue
    }
    const last = blocks[blocks.length - 1]
    if (last && last.type === 'group' && last.group === inactive.group) {
      last.rows.push(row)
      continue
    }
    blocks.push({
      type: 'group',
      id: 'group:' + inactive.group,
      group: inactive.group,
      impl: inactive.impl,
      label: inactive.group + '.*',
      rows: [row]
    })
  }
  return blocks
}

export function displayValue(value) {
  if (!value || value.indexOf(',') < 0) {
    return value
  }
  const hits = value.match(/file:\/\//g)
  if (hits && hits.length > 1) {
    return value.replace(/,(?=file:\/\/)/g, ',\\\n')
  }
  return value
}

function collectFactories(rows) {
  const factories = []
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i]
    if (!isFactoryKey(row.key) || !looksLikeClass(row.value)) {
      continue
    }
    const impl = implFromClass(row.value)
    if (!impl) {
      continue
    }
    factories.push({
      impl,
      namespaces: namespacesFor(row.value)
    })
  }
  return factories
}

function isFactoryKey(key) {
  if (!key) {
    return false
  }
  const lower = key.toLowerCase()
  return lower.endsWith('.factory')
    || lower.endsWith('.server')
    || lower.endsWith('.client')
    || lower.endsWith('.manager')
    || lower.endsWith('.manager.client')
}

function looksLikeClass(value) {
  return typeof value === 'string' && /^[A-Za-z][\w.]*\.[A-Z][\w]*$/.test(value.trim())
}

function implFromClass(value) {
  const simple = value.trim().split('.').pop().toLowerCase()
  for (let i = 0; i < IMPL_TOKENS.length; i++) {
    const token = IMPL_TOKENS[i]
    if (simple.indexOf(token) >= 0) {
      return token === 'avrorpc' ? 'avro' : token
    }
  }
  return null
}

function namespacesFor(fqn) {
  const trimmed = fqn.trim()
  const lastDot = trimmed.lastIndexOf('.')
  if (lastDot < 0) {
    return []
  }
  const pkg = trimmed.slice(0, lastDot)
  const namespaces = [pkg]
  const segs = pkg.split('.')
  const last = segs[segs.length - 1]
  const parent = segs.slice(0, -1).join('.')
  if (last === 'rpc' && parent) {
    namespaces.push(parent)
  }
  const aliases = PKG_ALIASES[last]
  if (aliases && parent) {
    for (let i = 0; i < aliases.length; i++) {
      namespaces.push(parent + '.' + aliases[i])
    }
  }
  return namespaces
}

function classifyInactive(key, factories) {
  if (!key || !factories.length) {
    return null
  }
  const lower = key.toLowerCase()
  for (let i = 0; i < factories.length; i++) {
    const factory = factories[i]
    for (let j = 0; j < factory.namespaces.length; j++) {
      const prefix = factory.namespaces[j].toLowerCase() + '.'
      if (lower.length <= prefix.length || lower.indexOf(prefix) !== 0) {
        continue
      }
      const seg = lower.slice(prefix.length).split('.')[0]
      if (IMPL_TOKENS.indexOf(seg) >= 0 && seg !== factory.impl) {
        return {
          group: key.slice(0, prefix.length + seg.length),
          impl: seg
        }
      }
    }
  }
  return null
}
