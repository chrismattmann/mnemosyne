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

export function classifyMetKey(key) {
  const k = String(key || '').toLowerCase().replace(/[^a-z0-9]/g, '')
  if (k === 'workflowinstid' || k === 'jobid') {
    return 'instance'
  }
  if (k === 'workflowid') {
    return 'workflow'
  }
  if (k === 'taskid') {
    return 'task'
  }
  if (k === 'producttype' || k === 'producttypename' || k === 'casproducttype'
      || k === 'casproducttypename') {
    return 'type'
  }
  if (k === 'filename' || k === 'productname' || k === 'casproductname'
      || k === 'casfilename' || k === 'inputfiles') {
    return 'product'
  }
  return null
}

export function metValues(value) {
  if (value == null || value === '') {
    return []
  }
  if (Array.isArray(value)) {
    return value.filter((item) => item != null && String(item).length > 0).map(String)
  }
  return [String(value)]
}
