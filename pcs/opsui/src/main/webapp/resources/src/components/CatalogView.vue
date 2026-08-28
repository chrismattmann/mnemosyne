<!--
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->
<template>
  <section>
    <h2>File catalog</h2>
    <p class="muted">Product types in File Manager.</p>
    <form class="query" @submit.prevent="submit">
      <input v-model="sql" type="text" spellcheck="false"
        :placeholder="exampleSql"/>
      <button type="submit">Query</button>
    </form>
    <p class="muted cap-note">A query returns at most {{ queryCap }} products.</p>
    <form class="query find" @submit.prevent="find">
      <input v-model="needle" type="text"
        placeholder="Product name or id"/>
      <button type="submit">Open</button>
    </form>
    <p class="muted cap-note">Open a cataloged file without writing SQL.</p>
    <p v-if="queryError" class="banner">{{ queryError }}</p>
    <p v-if="loading && !types.length" class="empty">Loading types…</p>
    <p v-else-if="!types.length" class="empty">No product types yet.</p>
    <template v-else>
      <p v-if="populated.length" class="muted">
        {{ populated.length }} type{{ populated.length === 1 ? '' : 's' }} with products.
        <span v-if="empty.length"> {{ empty.length }} empty type{{ empty.length === 1 ? '' : 's' }} hidden by default.</span>
      </p>
      <p v-else class="muted">None of these types have products yet.</p>
      <table v-if="visibleRows.length">
        <thead>
          <tr>
            <SortHead field="name" :sort="sort" :dir="dir" @sort="onSort">Type</SortHead>
            <th>Description</th>
            <SortHead field="numProducts" :sort="sort" :dir="dir" @sort="onSort">Products</SortHead>
          </tr>
        </thead>
        <tbody>
          <tr v-for="type in visibleRows" :key="type.id || type.name" :class="{ emptyRow: !(Number(type.numProducts) > 0) }">
            <td>
              <a href="#" @click.prevent="$emit('open', type.name)">{{ type.name }}</a>
            </td>
            <td>{{ type.description }}</td>
            <td>{{ type.numProducts }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="empty.length && populated.length" class="toggle-wrap">
        <button class="ghost" type="button" @click="showEmpty = !showEmpty">
          {{ showEmpty ? 'Hide empty types' : 'Show empty types (' + empty.length + ')' }}
        </button>
      </p>
    </template>
  </section>
</template>

<script>
import { computed, ref } from 'vue'
import SortHead from './SortHead.vue'
import { sortRows, toggleSort } from '../sort.js'
import { EXAMPLE_CATALOG_SQL, QUERY_RESULT_CAP, catalogSqlError } from '../sqlQuery.js'
import { partitionTypes } from '../catalogGroups.js'

export default {
  name: 'CatalogView',
  components: { SortHead },
  props: {
    types: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  emits: ['open', 'query', 'find'],
  setup(props, { emit }) {
    const sql = ref('')
    const needle = ref('')
    const queryError = ref('')
    const sort = ref('')
    const dir = ref('asc')
    const showEmpty = ref(false)
    const groups = computed(() => partitionTypes(props.types))
    const populated = computed(() => groups.value.populated)
    const empty = computed(() => groups.value.empty)
    function sorted(list) {
      if (!sort.value) {
        return list
      }
      const getter = sort.value === 'numProducts'
        ? (row) => Number(row.numProducts) || 0
        : (row) => row.name || ''
      return sortRows(list, getter, dir.value)
    }
    const visibleRows = computed(() => {
      const main = populated.value.length ? populated.value : empty.value
      const rows = showEmpty.value && populated.value.length
        ? populated.value.concat(empty.value)
        : main
      return sorted(rows)
    })
    function onSort(field) {
      const next = toggleSort(field, sort.value, dir.value)
      sort.value = next.field
      dir.value = next.dir
    }
    function submit() {
      const err = catalogSqlError(sql.value)
      queryError.value = err || ''
      if (!err) {
        emit('query', sql.value)
      }
    }
    function find() {
      const name = String(needle.value || '').trim()
      queryError.value = name ? '' : 'Enter a product name or id'
      if (name) {
        emit('find', name)
      }
    }
    return {
      sql, needle, queryError, exampleSql: EXAMPLE_CATALOG_SQL, queryCap: QUERY_RESULT_CAP, sort, dir, onSort, submit, find,
      showEmpty, populated, empty, visibleRows
    }
  }
}
</script>

<style scoped>
h2 {
  margin: 1.4rem 0 0.3rem;
}

.query {
  display: flex;
  gap: 0.5rem;
  margin: 0.8rem 0 1rem;
}

.query input {
  flex: 1;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85rem;
}

.cap-note {
  margin: -0.5rem 0 1rem;
  font-size: 0.85rem;
}

.toggle-wrap {
  margin-top: 0.8rem;
}

.emptyRow td {
  color: var(--muted);
}
</style>
