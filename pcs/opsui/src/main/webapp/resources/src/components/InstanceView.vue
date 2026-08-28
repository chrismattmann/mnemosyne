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
    <p><a href="#" @click.prevent="$emit('back')">← Instances</a></p>
    <h2>{{ title }}</h2>
    <p class="muted mono">{{ inst.id }}</p>
    <p v-if="loading && !inst.id" class="empty">Loading instance…</p>
    <article v-else class="card">
      <h3>Run</h3>
      <table>
        <tbody>
          <tr><th>ID</th><td class="mono">{{ inst.id }}</td></tr>
          <tr>
            <th>Workflow</th>
            <td>
              <a v-if="inst.workflowId" href="#" @click.prevent="$emit('open-workflow', inst.workflowId)">
                {{ inst.workflowName || inst.workflowId }}
              </a>
              <span v-else>{{ inst.workflowName || '—' }}</span>
            </td>
          </tr>
          <tr>
            <th>Status</th>
            <td><span class="pill" :class="pillClass(inst.status)">{{ inst.status || '—' }}</span></td>
          </tr>
          <tr>
            <th>{{ finished ? 'Last task' : 'Current task' }}</th>
            <td>
              <a v-if="inst.currentTaskId" href="#" @click.prevent="$emit('open-task', inst.currentTaskId)">
                {{ inst.currentTaskName || inst.currentTaskId }}
              </a>
              <span v-else>—</span>
            </td>
          </tr>
          <tr><th>Started</th><td>{{ inst.startDateTime || '—' }}</td></tr>
          <tr><th>Ended</th><td>{{ inst.endDateTime || '—' }}</td></tr>
          <tr><th>Wall clock</th><td class="mono">{{ formatWallClock(wallMs) }}</td></tr>
          <tr v-if="inst.priority"><th>Priority</th><td>{{ inst.priority }}</td></tr>
          <tr v-if="inst.timesBlocked != null"><th>Times blocked</th><td>{{ inst.timesBlocked }}</td></tr>
        </tbody>
      </table>
    </article>

    <article v-if="tasks.length" class="card">
      <h3>Description</h3>
      <WorkflowGraph
        :tasks="tasks"
        :name="inst.workflowName"
        :current-task-id="inst.currentTaskId"
        :status="inst.status"
        @open-task="$emit('open-task', $event)"/>
    </article>

    <article class="card">
      <h3>Files this run produced</h3>
      <p v-if="!products.length" class="empty">No File Manager products recorded for this run.</p>
      <table v-else>
        <thead>
          <tr>
            <th>Name</th>
            <th>Type</th>
            <th>Received</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="product in products" :key="product.id || product.name">
            <td>
              <a v-if="product.id || product.name" href="#" @click.prevent="$emit('open-product', product.id || product.name)">
                {{ product.name || product.id }}
              </a>
              <span v-else>—</span>
            </td>
            <td>
              <a v-if="productType(product)" href="#" @click.prevent="$emit('open-type', productType(product))">
                {{ productType(product) }}
              </a>
              <span v-else>—</span>
            </td>
            <td>{{ product.receivedTime || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </article>

    <article class="card">
      <h3>Instance metadata</h3>
      <MetadataTable
        :metadata="metadata"
        empty="No metadata on this instance."
        @open-instance="$emit('open-instance', $event)"
        @open-workflow="$emit('open-workflow', $event)"
        @open-task="$emit('open-task', $event)"
        @open-type="$emit('open-type', $event)"
        @open-product="$emit('open-product', $event)"/>
    </article>
  </section>
</template>

<script>
import { computed } from 'vue'
import MetadataTable from './MetadataTable.vue'
import WorkflowGraph from './WorkflowGraph.vue'
import { formatWallClock, wallClockMs } from '../sort.js'
import { instanceFinished } from '../workflowGraph.js'

export default {
  name: 'InstanceView',
  components: { MetadataTable, WorkflowGraph },
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['back', 'open-workflow', 'open-task', 'open-instance', 'open-type', 'open-product'],
  setup(props) {
    const inst = computed(() => (props.payload && props.payload.instance) || {})
    const metadata = computed(() => inst.value.metadata || {})
    return {
      inst,
      metadata,
      tasks: computed(() => inst.value.tasks || []),
      products: computed(() => inst.value.products || []),
      productType(product) {
        return (product && product.type && product.type.name) || ''
      },
      title: computed(() => inst.value.workflowName || 'Workflow instance'),
      finished: computed(() => instanceFinished(inst.value.status)),
      wallMs: computed(() => wallClockMs(inst.value.startDateTime, inst.value.endDateTime, Date.now())),
      formatWallClock,
      pillClass(status) {
        const value = String(status || '').toUpperCase()
        if (value === 'FINISHED' || value === 'SUCCESS' || value === 'EXECUTIONCOMPLETE') {
          return 'up'
        }
        if (value === 'FAILURE' || value === 'RESULTSFAILURE' || value === 'STOPPED') {
          return 'down'
        }
        if (value === 'PGE EXEC' || value === 'EXECUTING' || value === 'CRAWLING') {
          return 'warn'
        }
        return 'neutral'
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
  font-size: 0.85rem;
}

.break {
  word-break: break-all;
}

th {
  width: 10rem;
}
</style>
