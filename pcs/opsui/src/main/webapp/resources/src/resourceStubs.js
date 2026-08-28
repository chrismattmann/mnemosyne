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

function normalizeUrl(url) {
  return String(url || '').trim().replace(/\/+$/, '').toLowerCase()
}

export function stubForNode(node, stubs) {
  const url = normalizeUrl(node && node.url)
  if (!url) {
    return null
  }
  const list = stubs || []
  for (let i = 0; i < list.length; i++) {
    if (normalizeUrl(list[i] && list[i].url) === url) {
      return list[i]
    }
  }
  return null
}
