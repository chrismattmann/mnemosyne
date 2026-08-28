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

export const PEEK_BYTES = 2048

export function concatBytes(chunks, max) {
  let total = 0
  ;(chunks || []).forEach((chunk) => {
    total += chunk ? chunk.length : 0
  })
  const n = Math.min(total, max == null ? total : max)
  const out = new Uint8Array(n)
  let offset = 0
  ;(chunks || []).forEach((chunk) => {
    if (!chunk || offset >= n) {
      return
    }
    const take = Math.min(chunk.length, n - offset)
    out.set(chunk.subarray(0, take), offset)
    offset += take
  })
  return out
}

export function isTextMime(mime) {
  const value = String(mime || '').toLowerCase()
  if (!value) {
    return true
  }
  return value.indexOf('text/') === 0
    || value.indexOf('json') >= 0
    || value.indexOf('xml') >= 0
    || value.indexOf('csv') >= 0
    || value.indexOf('tsv') >= 0
    || value.indexOf('javascript') >= 0
}

export function looksBinary(bytes) {
  if (!bytes || !bytes.length) {
    return false
  }
  let nul = 0
  const n = Math.min(bytes.length, PEEK_BYTES)
  for (let i = 0; i < n; i++) {
    if (bytes[i] === 0) {
      nul++
    }
  }
  return nul / n > 0.01
}

export function decodePeek(bytes) {
  const slice = bytes && bytes.length > PEEK_BYTES ? bytes.subarray(0, PEEK_BYTES) : bytes
  if (!slice || !slice.length) {
    return { text: '', truncated: false, binary: false }
  }
  if (looksBinary(slice)) {
    return { text: '', truncated: slice.length >= PEEK_BYTES, binary: true }
  }
  const text = new TextDecoder('utf-8', { fatal: false }).decode(slice)
  return {
    text: text,
    truncated: bytes.length >= PEEK_BYTES,
    binary: false
  }
}
