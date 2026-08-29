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
    <h2>{{ name }}</h2>
    <p class="muted">{{ description }} · {{ numProducts }} products</p>
    <RefreshNote :refreshed-at="refreshedAt" :stale="stale"/>
    <p v-if="loading && !products.length" class="empty">Loading products…</p>
    <p v-else-if="!products.length" class="empty">No products of this type.</p>
    <table v-else>
      <thead>
        <tr>
          <SortHead field="name" :sort="sort" :dir="dir" @sort="onSort">Name</SortHead>
          <SortHead field="received" :sort="sort" :dir="dir" @sort="onSort">Received</SortHead>
          <th>Status</th>
          <th>Structure</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="product in rows" :key="product.id">
          <td>
            <a href="#" @click.prevent="$emit('open', product.id)">{{ product.name }}</a>
          </td>
          <td>{{ product.receivedTime || '—' }}</td>
          <td>{{ product.transferStatus }}</td>
          <td>{{ product.structure }}</td>
        </tr>
      </tbody>
    </table>
    <div v-if="rows.length" class="more">
      <p class="muted shown">
        Showing {{ rows.length }} of {{ numProducts }} products.
      </p>
      <button v-if="hasMore" type="button" :disabled="loading" @click="loadMore">
        {{ loading ? 'Loading…' : moreLabel }}
      </button>
    </div>
  </section>
</template>

<script>
import { computed, ref } from 'vue'
import RefreshNote from './RefreshNote.vue'
import SortHead from './SortHead.vue'
import { typeHasMore } from '../catalogPages.js'
import { parseStamp, sortRows, toggleSort } from '../sort.js'

export default {
  name: 'TypeView',
  components: { RefreshNote, SortHead },
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false },
    refreshedAt: { type: Number, default: 0 },
    stale: { type: Boolean, default: false }
  },
  emits: ['more', 'refresh', 'open', 'back'],
  setup(props, { emit }) {
    const catalog = computed(() => (props.payload && props.payload.catalog) || {})
    const type = computed(() => catalog.value.type || {})
    const page = computed(() => catalog.value.page || 1)
    const totalPages = computed(() => catalog.value.totalPages || 1)
    const products = computed(() => catalog.value.products || [])
    const hasMore = computed(() => typeHasMore(catalog.value, products.value.length))
    const sort = ref('')
    const dir = ref('asc')
    const rows = computed(() => {
      if (!sort.value) {
        return products.value
      }
      const getter = sort.value === 'received'
        ? (row) => parseStamp(row.receivedTime)
        : (row) => row.name || ''
      return sortRows(products.value, getter, dir.value)
    })
    const remaining = computed(() => {
      const total = catalog.value.numProducts != null ? catalog.value.numProducts : 0
      return Math.max(0, total - products.value.length)
    })
    const moreLabel = computed(() => {
      if (!remaining.value) {
        return 'Load more'
      }
      return 'Load more · ' + remaining.value + ' remaining'
    })

    function onSort(field) {
      const next = toggleSort(field, sort.value, dir.value)
      sort.value = next.field
      dir.value = next.dir
    }

    function loadMore() {
      if (props.loading || !hasMore.value) {
        return
      }
      if (page.value < totalPages.value) {
        emit('more', page.value + 1)
        return
      }
      emit('refresh')
    }

    return {
      name: computed(() => type.value.name || ''),
      description: computed(() => type.value.description || ''),
      numProducts: computed(() => catalog.value.numProducts != null ? catalog.value.numProducts : 0),
      products,
      rows,
      sort,
      dir,
      onSort,
      hasMore,
      moreLabel,
      loadMore
    }
  }
}
</script>

<style scoped>
h2 {
  margin: 0.4rem 0 0.3rem;
}

.shown {
  margin: 0;
  font-size: 0.85rem;
}

.more {
  margin: 0.9rem 0 1.6rem;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem 1rem;
}
</style>
