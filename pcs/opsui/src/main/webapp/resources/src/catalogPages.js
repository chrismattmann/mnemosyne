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

function typeName(payload) {
  const catalog = payload && payload.catalog
  return (catalog && catalog.type && catalog.type.name) || ''
}

function productKey(product) {
  return (product && (product.id || product.name)) || ''
}

/**
 * Append a type-products page onto the pages already shown. Page 1 or a
 * different type replaces; later pages concatenate and drop duplicate ids.
 */
export function mergeTypeCatalog(previous, incoming) {
  const next = incoming && incoming.catalog ? incoming : { catalog: incoming || {} }
  const catalog = next.catalog || {}
  const page = Number(catalog.page) || 1
  if (!previous || typeName(previous) !== typeName(next) || page <= 1) {
    return next
  }
  const seen = {}
  const products = []
  const prior = ((previous.catalog && previous.catalog.products) || [])
    .concat(catalog.products || [])
  prior.forEach((product) => {
    const key = productKey(product)
    if (!key || seen[key]) {
      return
    }
    seen[key] = true
    products.push(product)
  })
  return {
    catalog: Object.assign({}, catalog, { products: products })
  }
}
