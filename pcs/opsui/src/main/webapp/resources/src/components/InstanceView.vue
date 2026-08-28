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
            <th>Current task</th>
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

    <article class="card">
      <h3>Instance metadata</h3>
      <p v-if="!metKeys.length" class="empty">No metadata on this instance.</p>
      <table v-else>
        <thead>
          <tr><th>Key</th><th>Value</th></tr>
        </thead>
        <tbody>
          <tr v-for="key in metKeys" :key="key">
            <td class="mono">{{ key }}</td>
            <td class="break">{{ formatMet(metadata[key]) }}</td>
          </tr>
        </tbody>
      </table>
    </article>
  </section>
</template>

<script>
import { computed } from 'vue'
import { formatWallClock, wallClockMs } from '../sort.js'

export default {
  name: 'InstanceView',
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['back', 'open-workflow', 'open-task'],
  setup(props) {
    const inst = computed(() => (props.payload && props.payload.instance) || {})
    const metadata = computed(() => inst.value.metadata || {})
    return {
      inst,
      metadata,
      title: computed(() => inst.value.workflowName || 'Workflow instance'),
      metKeys: computed(() => Object.keys(metadata.value).sort()),
      wallMs: computed(() => wallClockMs(inst.value.startDateTime, inst.value.endDateTime, Date.now())),
      formatWallClock,
      formatMet(value) {
        if (Array.isArray(value)) {
          return value.join(', ')
        }
        return value == null ? '' : String(value)
      },
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
