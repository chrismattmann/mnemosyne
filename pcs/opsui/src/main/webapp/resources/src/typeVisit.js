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

export function typeHash(name) {
  return 'catalog/' + encodeURIComponent(name || '')
}

export function typeFromParts(parts) {
  if (!parts || parts[0] !== 'catalog' || !parts[1]) {
    return null
  }
  return { view: 'type', name: parts[1] }
}

/**
 * Load-more paging is only for the current visit to a type. Leaving the
 * type, or opening a different type, starts again at page 1.
 */
export function shouldResetTypeVisit(prev, next) {
  const onType = Boolean(next && next.view === 'type')
  const wasType = Boolean(prev && prev.view === 'type')
  if (onType) {
    return !wasType || prev.name !== next.name
  }
  return wasType
}
