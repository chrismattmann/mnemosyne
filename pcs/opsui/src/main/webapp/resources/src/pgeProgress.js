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

export function progressLabel(progress) {
  if (!progress) {
    return ''
  }
  const done = progress.done
  const total = progress.total
  const message = progress.message || ''
  if (done != null && total != null) {
    return (message ? message + ' ' : '') + done + ' / ' + total
  }
  if (done != null) {
    return (message ? message + ' ' : '') + String(done)
  }
  return message
}

export function progressPct(progress) {
  if (!progress || progress.total == null || Number(progress.total) <= 0) {
    return 0
  }
  const done = Number(progress.done) || 0
  const pct = Math.round((100 * done) / Number(progress.total))
  if (pct < 0) {
    return 0
  }
  if (pct > 100) {
    return 100
  }
  return pct
}
