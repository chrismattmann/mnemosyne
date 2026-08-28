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
 * Flatten a pedigree JSON blob into clickable nodes. The PCS encoder
 * nests relatives under the current product's name; that self root is
 * stripped so a TSV page lists the split, not itself, and a product
 * with only itself in the tree looks empty rather than inventing
 * children.
 */
export function lineageNodes(value, selfName) {
  if (value == null || value === '') {
    return []
  }
  if (typeof value === 'string') {
    return value === selfName ? [] : [{ name: value, children: null }]
  }
  if (Array.isArray(value)) {
    return value.flatMap((item) => lineageNodes(item, selfName))
  }
  if (typeof value === 'object') {
    const keys = Object.keys(value)
    if (keys.length === 1 && keys[0] === selfName) {
      return lineageNodes(value[keys[0]], selfName)
    }
    return keys.map((name) => ({
      name,
      children: value[name]
    }))
  }
  return []
}
