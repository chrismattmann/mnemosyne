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
    <form class="query" @submit.prevent="$emit('query', sql)">
      <input v-model="sql" type="text" spellcheck="false"
        placeholder="SELECT Filename FROM EmploymentJob"/>
      <button type="submit">Query</button>
    </form>
    <p v-if="loading && !types.length" class="empty">Loading types…</p>
    <p v-else-if="!types.length" class="empty">No product types yet.</p>
    <table v-else>
      <thead>
        <tr>
          <SortHead field="name" :sort="sort" :dir="dir" @sort="onSort">Type</SortHead>
          <th>Description</th>
          <SortHead field="numProducts" :sort="sort" :dir="dir" @sort="onSort">Products</SortHead>
        </tr>
      </thead>
      <tbody>
        <tr v-for="type in rows" :key="type.id || type.name">
          <td>
            <a href="#" @click.prevent="$emit('open', type.name)">{{ type.name }}</a>
          </td>
          <td>{{ type.description }}</td>
          <td>{{ type.numProducts }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<script>
import { computed, ref } from 'vue'
import SortHead from './SortHead.vue'
import { sortRows, toggleSort } from '../sort.js'

export default {
  name: 'CatalogView',
  components: { SortHead },
  props: {
    types: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  emits: ['open', 'query'],
  setup(props) {
    const sql = ref('')
    const sort = ref('')
    const dir = ref('asc')
    const rows = computed(() => {
      if (!sort.value) {
        return props.types
      }
      const getter = sort.value === 'numProducts'
        ? (row) => Number(row.numProducts) || 0
        : (row) => row.name || ''
      return sortRows(props.types, getter, dir.value)
    })
    function onSort(field) {
      const next = toggleSort(field, sort.value, dir.value)
      sort.value = next.field
      dir.value = next.dir
    }
    return { sql, sort, dir, rows, onSort }
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
</style>
