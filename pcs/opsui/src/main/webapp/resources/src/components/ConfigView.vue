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
    <p><a href="#" @click.prevent="$emit('back')">← Status</a></p>
    <h2>{{ config.name || 'Configuration' }}</h2>
    <p class="muted mono">{{ config.path }}</p>
    <p v-if="loading && !config.id" class="empty">Loading configuration…</p>
    <article v-else class="card">
      <h3>Properties</h3>
      <p v-if="!rows.length" class="empty">No properties in this file.</p>
      <table v-else>
        <thead>
          <tr><th>Property</th><th>Value</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.key">
            <td class="mono">{{ row.key }}</td>
            <td class="break">{{ row.value }}</td>
          </tr>
        </tbody>
      </table>
    </article>
  </section>
</template>

<script>
import { computed } from 'vue'

export default {
  name: 'ConfigView',
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['back'],
  setup(props) {
    const config = computed(() => (props.payload && props.payload.config) || {})
    return {
      config,
      rows: computed(() => config.value.properties || [])
    }
  }
}
</script>

<style scoped>
h2 {
  margin: 0.4rem 0 0.2rem;
}

h3 {
  margin: 0 0 0.6rem;
}

.card {
  margin-top: 1rem;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.8rem;
}

.break {
  word-break: break-all;
}

th {
  width: 40%;
}
</style>
