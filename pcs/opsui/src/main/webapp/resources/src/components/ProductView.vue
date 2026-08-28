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
    <p v-if="loading && !product.id && !product.missing" class="empty">Loading product…</p>
    <article v-else-if="product.missing" class="card missing">
      <h2>Not in File Manager</h2>
      <p class="muted">Nothing named or identified as <span class="mono">{{ product.id }}</span> is in the File Manager catalog. It may never have been ingested, or it may have been removed.</p>
    </article>
    <template v-else>
      <div class="head">
        <div>
          <h2>{{ product.name || 'Product' }}</h2>
          <p class="muted">{{ product.id }} · {{ product.transferStatus }} · {{ product.structure }}</p>
        </div>
        <a v-if="product.id" class="download" :href="downloadHref">Download</a>
      </div>
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
        <h3>Peek</h3>
        <p v-if="peek.loading" class="empty">Reading the first {{ peekBytes }} bytes…</p>
        <p v-else-if="peek.error" class="muted">{{ peek.error }}</p>
        <p v-else-if="peek.binary" class="muted">Binary file — download it instead of previewing.</p>
        <pre v-else-if="peek.text" class="peek">{{ peek.text }}</pre>
        <p v-else class="empty">Nothing to preview.</p>
        <p v-if="peek.truncated" class="muted shown">First {{ peekBytes }} bytes. Download for the rest.</p>
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

      <article class="card">
        <h3>Lineage</h3>
        <p v-if="pedigree && pedigree.error" class="muted">{{ pedigree.error }}</p>
        <LineageGraph
          v-else
          :upstream="upstream"
          :downstream="downstream"
          :self-name="product.name"
          empty="No cataloged relatives."
          @open="$emit('open-product', $event)"/>
      </article>
    </template>
  </section>
</template>

<script>
import { computed, ref, watch } from 'vue'
import LineageGraph from './LineageGraph.vue'
import MetadataTable from './MetadataTable.vue'
import { peekProduct, productDataUrl } from '../api.js'
import { isTextMime, PEEK_BYTES } from '../productPeek.js'

export default {
  name: 'ProductView',
  components: { LineageGraph, MetadataTable },
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
    const peek = ref({ loading: false, text: '', binary: false, truncated: false, error: '' })

    watch(
      () => product.value.id,
      async () => {
        peek.value = { loading: false, text: '', binary: false, truncated: false, error: '' }
        if (!product.value.id || product.value.missing) {
          return
        }
        const mime = (refs.value[0] && refs.value[0].mimeType) || ''
        if (mime && !isTextMime(mime)) {
          peek.value = { loading: false, text: '', binary: true, truncated: false, error: '' }
          return
        }
        peek.value.loading = true
        try {
          const result = await peekProduct(product.value.id)
          const binary = Boolean(result.binary)
          peek.value = {
            loading: false,
            text: binary ? '' : (result.text || ''),
            binary: binary,
            truncated: Boolean(result.truncated),
            error: ''
          }
        } catch (e) {
          peek.value = {
            loading: false,
            text: '',
            binary: false,
            truncated: false,
            error: e.message || String(e)
          }
        }
      },
      { immediate: true }
    )

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
      downloadHref, refHref, backToType, peek, peekBytes: PEEK_BYTES
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

.peek {
  margin: 0;
  max-height: 16rem;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.78rem;
  white-space: pre-wrap;
  word-break: break-all;
  background: #f5f0ea;
  padding: 0.7rem 0.8rem;
  border-radius: 4px;
}

.shown {
  margin-top: 0.5rem;
  font-size: 0.85rem;
}

.break {
  word-break: break-all;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85rem;
}

.missing h2 {
  margin-bottom: 0.4rem;
}


</style>
