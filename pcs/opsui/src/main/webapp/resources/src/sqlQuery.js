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

export const EXAMPLE_CATALOG_SQL = 'SELECT Filename FROM EmploymentJob'
export const QUERY_RESULT_CAP = 200

function maskQuoted(sql) {
  let out = ''
  let inQuote = false
  for (const ch of sql) {
    if (ch === "'") {
      inQuote = !inQuote
      out += ' '
    } else {
      out += inQuote ? ' ' : ch
    }
  }
  return out
}

function isNameChar(ch) {
  return /[A-Za-z0-9_.-]/.test(ch)
}

function keywordIndex(masked, word, fromIndex) {
  const needle = word.toLowerCase()
  const hay = masked.toLowerCase()
  let from = fromIndex
  while (from <= hay.length - needle.length) {
    const at = hay.indexOf(needle, from)
    if (at < 0) {
      return -1
    }
    const beforeOk = at === 0 || !isNameChar(hay[at - 1])
    const after = at + needle.length
    const afterOk = after >= hay.length || !isNameChar(hay[after])
    if (beforeOk && afterOk) {
      return at
    }
    from = at + 1
  }
  return -1
}

/**
 * Returns an error string if the catalog query is not SELECT ... FROM ...,
 * otherwise null. Keywords are matched case-insensitively and ignored inside
 * single quotes, matching SqlParser.parseSqlQuery.
 */
export function catalogSqlError(sql) {
  const trimmed = String(sql || '').trim()
  if (!trimmed) {
    return `Enter a SQL query, for example: ${EXAMPLE_CATALOG_SQL}`
  }
  const masked = maskQuoted(trimmed)
  const selectAt = keywordIndex(masked, 'select', 0)
  if (selectAt < 0 || masked.slice(0, selectAt).trim()) {
    return `Query must start with SELECT, for example: ${EXAMPLE_CATALOG_SQL}`
  }
  const fromAt = keywordIndex(masked, 'from', selectAt + 6)
  if (fromAt < 0) {
    return `Query needs a FROM clause, for example: ${EXAMPLE_CATALOG_SQL}`
  }
  const whereAt = keywordIndex(masked, 'where', fromAt + 4)
  if (whereAt >= 0 && whereAt < fromAt) {
    return `WHERE must come after FROM, for example: ${EXAMPLE_CATALOG_SQL} WHERE Filename == 'job-1001.json'`
  }
  const selectList = trimmed.slice(selectAt + 6, fromAt).trim()
  const fromList = trimmed.slice(fromAt + 4, whereAt >= 0 ? whereAt : trimmed.length).trim()
  if (!selectList) {
    return `SELECT list is empty, for example: ${EXAMPLE_CATALOG_SQL}`
  }
  if (!fromList) {
    return `FROM clause is empty, for example: ${EXAMPLE_CATALOG_SQL}`
  }
  if (whereAt >= 0 && !trimmed.slice(whereAt + 5).trim()) {
    return `WHERE clause is empty, for example: ${EXAMPLE_CATALOG_SQL} WHERE Filename == 'job-1001.json'`
  }
  return null
}
