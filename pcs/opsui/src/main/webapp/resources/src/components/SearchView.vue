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
    <p><a href="#" @click.prevent="$emit('back')">← Catalog</a></p>
    <h2>Catalog query</h2>
    <form class="query" @submit.prevent="submit">
      <input v-model="sql" type="text" spellcheck="false"
        :placeholder="exampleSql"/>
      <button type="submit">Query</button>
    </form>
    <p class="muted cap-note">A query returns at most {{ queryCap }} products.</p>
    <p v-if="queryError || error" class="banner">{{ queryError || error }}</p>
    <p v-else-if="loading && !rows.length" class="empty">Running query…</p>
    <p v-else-if="!rows.length" class="empty">No products matched.</p>
    <p v-if="truncated" class="notice cap">
      Showing the first {{ limit }} of more than {{ limit }} matches. Add a WHERE clause to narrow the query.
    </p>
    <table v-if="rows.length">
      <thead>
        <tr>
          <SortHead field="name" :sort="sort" :dir="dir" @sort="onSort">Name</SortHead>
          <SortHead field="type" :sort="sort" :dir="dir" @sort="onSort">Type</SortHead>
          <SortHead field="status" :sort="sort" :dir="dir" @sort="onSort">Status</SortHead>
        </tr>
      </thead>
      <tbody>
        <tr v-for="product in rows" :key="product.id || product.name">
          <td>
            <a v-if="product.id || product.name" href="#" @click.prevent="$emit('open', product.id || product.name)">
              {{ product.name || product.id }}
            </a>
            <span v-else>—</span>
          </td>
          <td>
            <a v-if="typeName(product)" href="#" @click.prevent="$emit('open-type', typeName(product))">
              {{ typeName(product) }}
            </a>
            <span v-else>—</span>
          </td>
          <td>{{ product.transferStatus || '—' }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<script>
import { computed, ref, watch } from 'vue'
import SortHead from './SortHead.vue'
import { EXAMPLE_CATALOG_SQL, QUERY_RESULT_CAP, catalogSqlError } from '../sqlQuery.js'
import { sortRows, toggleSort } from '../sort.js'

export default {
  name: 'SearchView',
  components: { SortHead },
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['query', 'open', 'open-type', 'back'],
  setup(props, { emit }) {
    const query = computed(() => (props.payload && props.payload.query) || {})
    const sql = ref(query.value.sql || '')
    const queryError = ref('')
    watch(query, (next) => {
      if (next.sql) {
        sql.value = next.sql
      }
      queryError.value = ''
    })
    function submit() {
      const err = catalogSqlError(sql.value)
      queryError.value = err || ''
      if (!err) {
        emit('query', sql.value)
      }
    }
    const results = computed(() => query.value.results || [])
    const sort = ref('')
    const dir = ref('asc')
    function typeName(product) {
      return (product && product.type && product.type.name) || ''
    }
    const rows = computed(() => {
      if (!sort.value) {
        return results.value
      }
      const getter = sort.value === 'type'
        ? (row) => typeName(row)
        : sort.value === 'status'
          ? (row) => row.transferStatus || ''
          : (row) => row.name || row.id || ''
      return sortRows(results.value, getter, dir.value)
    })
    function onSort(field) {
      const next = toggleSort(field, sort.value, dir.value)
      sort.value = next.field
      dir.value = next.dir
    }
    return {
      sql,
      queryError,
      exampleSql: EXAMPLE_CATALOG_SQL,
      queryCap: QUERY_RESULT_CAP,
      results,
      rows,
      sort,
      dir,
      onSort,
      error: computed(() => query.value.error || ''),
      truncated: computed(() => Boolean(query.value.truncated)),
      limit: computed(() => Number(query.value.limit) || QUERY_RESULT_CAP),
      submit,
      typeName
    }
  }
}
</script>

<style scoped>
h2 {
  margin: 0.4rem 0 0.3rem;
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

.cap {
  margin: 0 0 0.8rem;
}
</style>
