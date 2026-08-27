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
      <p v-if="!blocks.length" class="empty">No properties in this file.</p>
      <table v-else>
        <thead>
          <tr><th>Property</th><th>Value</th></tr>
        </thead>
        <tbody>
          <template v-for="block in blocks" :key="block.id">
            <tr v-if="block.type === 'row'">
              <td class="mono">{{ block.row.key }}</td>
              <td class="break" :class="{ pre: multiline(block.row.value) }">{{ displayValue(block.row.value) }}</td>
            </tr>
            <tr v-else class="group-row">
              <td class="mono">
                <button type="button" class="toggle" @click="toggle(block.id)">
                  <span class="plus">{{ open[block.id] ? '−' : '+' }}</span>
                  {{ block.label }}
                </button>
              </td>
              <td>
                <span v-if="!open[block.id]" class="ellipsis">{…}</span>
              </td>
            </tr>
            <tr v-for="row in open[block.id] ? block.rows : []" :key="row.key" class="nested">
              <td class="mono">{{ row.key }}</td>
              <td class="break">{{ row.value }}</td>
            </tr>
          </template>
        </tbody>
      </table>
    </article>
  </section>
</template>

<script>
import { computed, reactive } from 'vue'
import { displayValue, groupConfigRows } from '../configGroups.js'

export default {
  name: 'ConfigView',
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['back'],
  setup(props) {
    const config = computed(() => (props.payload && props.payload.config) || {})
    const open = reactive({})
    return {
      config,
      open,
      displayValue,
      blocks: computed(() => groupConfigRows(config.value.properties || [])),
      toggle(id) {
        open[id] = !open[id]
      },
      multiline(value) {
        return String(displayValue(value) || '').indexOf('\n') >= 0
      }
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

.pre {
  white-space: pre-wrap;
}

th {
  width: 40%;
}

button.toggle {
  background: transparent;
  color: inherit;
  border: 0;
  border-radius: 0;
  padding: 0;
  font-weight: 600;
  font-size: inherit;
  font-family: inherit;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
}

button.toggle:hover {
  color: var(--copper);
  background: transparent;
}

.plus {
  display: inline-block;
  width: 0.9rem;
  color: var(--copper);
  font-weight: 700;
}

.group-row td {
  color: var(--muted);
}

.nested td:first-child {
  padding-left: 1.85rem;
}

.ellipsis {
  letter-spacing: 0.12em;
  color: var(--muted);
}
</style>
