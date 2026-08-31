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
 * Where a product reference should take the reader.
 *
 * A reference is not always an id. Metadata carries file paths -- InputFiles
 * on a workflow instance is a list of absolute paths -- and those reach the
 * router through the same click as a real id. Asking the catalog for a
 * product named "/Users/.../testJAR.jar" is answered with a 400, which the
 * page shows as an error where the reader expected the file.
 *
 * A path is reduced to its last segment, which is the product name the
 * catalog knows it by. Anything else is passed through as an id.
 *
 * @param {string} ref a product id, or a path to a file that was ingested
 * @returns {{view: string, id: string}|null} the route, or null for nothing
 */
export function productRoute(ref) {
  if (ref === null || ref === undefined || ref === '') {
    return null
  }
  const value = String(ref)
  if (value.indexOf('/') < 0) {
    return { view: 'product', id: value }
  }
  const name = value.split('/').filter(Boolean).pop()
  return name ? { view: 'product', id: name } : null
}
