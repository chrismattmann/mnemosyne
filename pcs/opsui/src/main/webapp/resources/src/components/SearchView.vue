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
    <form class="query" @submit.prevent="$emit('query', sql)">
      <input v-model="sql" type="text" spellcheck="false"
        placeholder="SELECT Filename FROM EmploymentJob"/>
      <button type="submit">Query</button>
    </form>
    <p v-if="error" class="banner">{{ error }}</p>
    <p v-else-if="loading && !results.length" class="empty">Running query…</p>
    <p v-else-if="!results.length" class="empty">No products matched.</p>
    <table v-else>
      <thead>
        <tr>
          <th>Name</th>
          <th>Type</th>
          <th>Status</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="product in results" :key="product.id || product.name">
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
    <p v-if="truncated" class="muted">Showing the first {{ results.length }} matches.</p>
  </section>
</template>

<script>
import { computed, ref, watch } from 'vue'

export default {
  name: 'SearchView',
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['query', 'open', 'open-type', 'back'],
  setup(props) {
    const query = computed(() => (props.payload && props.payload.query) || {})
    const sql = ref(query.value.sql || '')
    watch(query, (next) => {
      if (next.sql) {
        sql.value = next.sql
      }
    })
    return {
      sql,
      results: computed(() => query.value.results || []),
      error: computed(() => query.value.error || ''),
      truncated: computed(() => Boolean(query.value.truncated)),
      typeName(product) {
        return (product && product.type && product.type.name) || ''
      }
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

.muted {
  margin-top: 0.8rem;
}
</style>
