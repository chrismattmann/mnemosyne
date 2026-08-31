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

import { concatBytes, decodePeek, PEEK_BYTES } from './productPeek.js'

const services = () => `${window.location.origin}/pcs/services`

async function readJson(response) {
  const text = await response.text()
  let body = {}
  if (text) {
    try {
      body = JSON.parse(text)
    } catch (e) {
      body = { error: text }
    }
  }
  if (!response.ok) {
    const message = body.error || text || response.statusText || `HTTP ${response.status}`
    throw new Error(message)
  }
  return body
}

export function getHealth() {
  return fetch(`${services()}/health/report`).then(readJson)
}

export function getTypes() {
  return fetch(`${services()}/catalog/types`).then(readJson)
}

export function getTypeProducts(name, page) {
  const params = new URLSearchParams()
  params.set('page', String(page || 1))
  return fetch(`${services()}/catalog/types/${encodeURIComponent(name)}/products?${params}`)
    .then(readJson)
}

export async function getProduct(id) {
  const response = await fetch(`${services()}/catalog/products/${encodeURIComponent(id)}`)
  if (response.status === 404) {
    return { missing: true, id }
  }
  return readJson(response)
}

export function getPedigree(filename) {
  return fetch(`${services()}/pedigree/report/${encodeURIComponent(filename)}`).then(readJson)
}

export function getInstances(status, page, workflow) {
  const params = new URLSearchParams()
  params.set('status', status || 'ALL')
  params.set('page', String(page || 1))
  if (workflow) {
    params.set('workflow', workflow)
  }
  return fetch(`${services()}/workflow/instances?${params}`).then(readJson)
}

export function getInstance(id) {
  return fetch(`${services()}/workflow/instances/${encodeURIComponent(id)}`).then(readJson)
}

export function getStatuses() {
  return fetch(`${services()}/workflow/statuses`).then(readJson)
}

export function getWorkflows() {
  return fetch(`${services()}/workflow/definitions`).then(readJson)
}

export function getWorkflow(id) {
  return fetch(`${services()}/workflow/definitions/${encodeURIComponent(id)}`).then(readJson)
}

export function getTask(id) {
  return fetch(`${services()}/workflow/tasks/${encodeURIComponent(id)}`).then(readJson)
}

export function getCondition(id) {
  return fetch(`${services()}/workflow/conditions/${encodeURIComponent(id)}`).then(readJson)
}

export function getConfig(component) {
  return fetch(`${services()}/config/${encodeURIComponent(component)}`).then(readJson)
}

export function queryCatalog(sql) {
  const params = new URLSearchParams()
  params.set('sql', sql || '')
  return fetch(`${services()}/catalog/query?${params}`).then(readJson)
}

export function getResources() {
  return fetch(`${services()}/resource/overview`).then(readJson)
}

export async function peekProduct(id, maxBytes) {
  const n = maxBytes || PEEK_BYTES
  const response = await fetch(productDataUrl(id), {
    headers: { Range: 'bytes=0-' + (n - 1) }
  })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || response.statusText || 'Peek failed')
  }
  const mime = response.headers.get('content-type') || ''
  let bytes
  if (response.body && typeof response.body.getReader === 'function') {
    const reader = response.body.getReader()
    const chunks = []
    let got = 0
    while (got < n) {
      const step = await reader.read()
      if (step.done) {
        break
      }
      chunks.push(step.value)
      got += step.value.length
    }
    try {
      await reader.cancel()
    } catch (e) {
      // already closed
    }
    bytes = concatBytes(chunks, n)
  } else {
    const buf = new Uint8Array(await response.arrayBuffer())
    bytes = buf.subarray(0, n)
  }
  const decoded = decodePeek(bytes)
  decoded.mime = mime
  return decoded
}

export function productDataUrl(id, refIndex) {
  const params = new URLSearchParams()
  params.set('productID', id)
  if (refIndex !== undefined && refIndex !== null && refIndex !== '') {
    params.set('refIndex', String(refIndex))
  }
  return `data?${params.toString()}`
}
