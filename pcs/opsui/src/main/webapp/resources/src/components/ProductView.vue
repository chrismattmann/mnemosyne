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
    <p><a href="#" @click.prevent="backToType">← {{ typeName || 'Catalog' }}</a></p>
    <div class="head">
      <div>
        <h2>{{ product.name || 'Product' }}</h2>
        <p class="muted">{{ product.id }} · {{ product.transferStatus }} · {{ product.structure }}</p>
      </div>
      <a v-if="product.id" class="download" :href="downloadHref">Download</a>
    </div>
    <p v-if="loading && !product.id" class="empty">Loading product…</p>
    <template v-else>
      <article class="card">
        <h3>References</h3>
        <p v-if="!refs.length" class="empty">No file references.</p>
        <table v-else>
          <thead>
            <tr>
              <th>Original</th>
              <th>Data store</th>
              <th>Size</th>
              <th>MIME</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(ref, i) in refs" :key="i">
              <td class="break">{{ ref.orig }}</td>
              <td class="break">{{ ref.dataStore }}</td>
              <td>{{ ref.fileSize }}</td>
              <td>{{ ref.mimeType || '—' }}</td>
              <td>
                <a :href="refHref(i)">Download</a>
              </td>
            </tr>
          </tbody>
        </table>
      </article>

      <article class="card">
        <h3>Metadata</h3>
        <MetadataTable
          :metadata="metadata"
          empty="No metadata."
          @open-instance="$emit('open-instance', $event)"
          @open-workflow="$emit('open-workflow', $event)"
          @open-task="$emit('open-task', $event)"
          @open-type="$emit('open-type', $event)"
          @open-product="$emit('open-product', $event)"/>
      </article>

      <div class="split">
        <article class="card">
          <h3>Upstream lineage</h3>
          <p v-if="pedigree && pedigree.error" class="muted">{{ pedigree.error }}</p>
          <LineageTree v-else :value="upstream"/>
        </article>
        <article class="card">
          <h3>Downstream lineage</h3>
          <LineageTree :value="downstream"/>
        </article>
      </div>
    </template>
  </section>
</template>

<script>
import { computed } from 'vue'
import LineageTree from './LineageTree.vue'
import MetadataTable from './MetadataTable.vue'
import { productDataUrl } from '../api.js'

export default {
  name: 'ProductView',
  components: { LineageTree, MetadataTable },
  props: {
    payload: { type: Object, default: null },
    pedigree: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['open-type', 'back', 'open-instance', 'open-workflow', 'open-task', 'open-product'],
  setup(props, { emit }) {
    const product = computed(() => props.payload || {})
    const typeName = computed(() => (product.value.type && product.value.type.name) || '')
    const refs = computed(() => product.value.references || [])
    const metadata = computed(() => product.value.metadata || {})
    const tree = computed(() => (props.pedigree && props.pedigree.pedigree) || {})
    const downloadHref = computed(() => product.value.id ? productDataUrl(product.value.id) : '#')

    function refHref(index) {
      return productDataUrl(product.value.id, index)
    }

    function backToType() {
      if (typeName.value) {
        emit('open-type', typeName.value)
      } else {
        emit('back')
      }
    }

    return {
      product, typeName, refs, metadata,
      upstream: computed(() => tree.value.upstream),
      downstream: computed(() => tree.value.downstream),
      downloadHref, refHref, backToType
    }
  }
}
</script>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  margin: 0.4rem 0 1rem;
}

h2, h3 {
  margin: 0 0 0.5rem;
}

.download {
  background: var(--copper);
  color: #fff8f3;
  padding: 0.45rem 0.95rem;
  border-radius: 4px;
  font-weight: 600;
}

.download:hover {
  text-decoration: none;
  background: var(--copper-dark);
}

.card {
  margin-bottom: 0.8rem;
}

.split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.8rem;
}

.break {
  word-break: break-all;
}

@media (max-width: 800px) {
  .split {
    grid-template-columns: 1fr;
  }
}
</style>
