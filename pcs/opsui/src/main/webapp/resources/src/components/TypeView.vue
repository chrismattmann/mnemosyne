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
    <p v-if="loading && !products.length" class="empty">Loading products…</p>
    <p v-else-if="!products.length" class="empty">No products of this type.</p>
    <table v-else>
      <thead>
        <tr>
          <th>Name</th>
          <th>Received</th>
          <th>Status</th>
          <th>Structure</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="product in products" :key="product.id">
          <td>
            <a href="#" @click.prevent="$emit('open', product.id)">{{ product.name }}</a>
          </td>
          <td>{{ product.receivedTime || '—' }}</td>
          <td>{{ product.transferStatus }}</td>
          <td>{{ product.structure }}</td>
        </tr>
      </tbody>
    </table>
    <Pager :page="page" :total-pages="totalPages" @page="$emit('page', $event)"/>
  </section>
</template>

<script>
import { computed } from 'vue'
import Pager from './Pager.vue'

export default {
  name: 'TypeView',
  components: { Pager },
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['page', 'open', 'back'],
  setup(props) {
    const catalog = computed(() => (props.payload && props.payload.catalog) || {})
    const type = computed(() => catalog.value.type || {})
    return {
      name: computed(() => type.value.name || ''),
      description: computed(() => type.value.description || ''),
      numProducts: computed(() => catalog.value.numProducts != null ? catalog.value.numProducts : 0),
      products: computed(() => catalog.value.products || []),
      page: computed(() => catalog.value.page || 1),
      totalPages: computed(() => catalog.value.totalPages || 1)
    }
  }
}
</script>

<style scoped>
h2 {
  margin: 0.4rem 0 0.3rem;
}
</style>
