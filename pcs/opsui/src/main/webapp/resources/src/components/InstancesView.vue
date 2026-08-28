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
    <div class="head">
      <h2>Workflow instances</h2>
      <label>
        Status
        <select :value="status" @change="$emit('status', $event.target.value)">
          <option v-for="item in statuses" :key="item" :value="item">{{ item }}</option>
        </select>
      </label>
    </div>
    <p v-if="loading && !instances.length" class="empty">Loading instances…</p>
    <p v-else-if="!instances.length" class="empty">No instances for this filter.</p>
    <table v-else>
      <thead>
        <tr>
          <th>ID</th>
          <SortHead field="workflow" :sort="sort" :dir="dir" @sort="onSort">Workflow</SortHead>
          <SortHead field="status" :sort="sort" :dir="dir" @sort="onSort">Status</SortHead>
          <SortHead field="task" :sort="sort" :dir="dir" @sort="onSort">Current task</SortHead>
          <SortHead field="start" :sort="sort" :dir="dir" @sort="onSort">Started</SortHead>
          <SortHead field="end" :sort="sort" :dir="dir" @sort="onSort">Ended</SortHead>
          <SortHead field="wall" :sort="sort" :dir="dir" @sort="onSort">Wall clock</SortHead>
        </tr>
      </thead>
      <tbody>
        <tr v-for="inst in rows" :key="inst.id">
          <td class="mono">
            <a v-if="inst.id" href="#" @click.prevent="$emit('open-instance', inst.id)">{{ inst.id }}</a>
            <span v-else>—</span>
          </td>
          <td>
            <a v-if="inst.workflowId" href="#" @click.prevent="$emit('open-workflow', inst.workflowId)">
              {{ inst.workflowName || inst.workflowId }}
            </a>
            <span v-else>{{ inst.workflowName }}</span>
          </td>
          <td>
            <span class="pill" :class="pillClass(inst.status)">{{ inst.status }}</span>
          </td>
          <td>
            <a v-if="inst.currentTaskId" href="#" @click.prevent="$emit('open-task', inst.currentTaskId)">
              {{ inst.currentTaskName || inst.currentTaskId }}
            </a>
            <span v-else>—</span>
          </td>
          <td>{{ inst.startDateTime || '—' }}</td>
          <td>{{ inst.endDateTime || '—' }}</td>
          <td class="mono">{{ formatWallClock(inst.wallMs) }}</td>
        </tr>
      </tbody>
    </table>
    <Pager :page="page" :total-pages="totalPages" @page="$emit('page', $event)"/>
  </section>
</template>

<script>
import { computed, ref } from 'vue'
import Pager from './Pager.vue'
import SortHead from './SortHead.vue'
import { formatWallClock, parseStamp, sortRows, toggleSort, wallClockMs } from '../sort.js'

const STATUSES = [
  'ALL', 'QUEUED', 'RSUBMIT', 'BUILDING CONFIG FILE', 'PGE EXEC', 'CRAWLING',
  'STAGING INPUT', 'FINISHED', 'STARTED', 'PAUSED', 'Executing', 'Success',
  'Failure', 'Stopped', 'Loaded', 'Blocked'
]

export default {
  name: 'InstancesView',
  components: { Pager, SortHead },
  props: {
    payload: { type: Object, default: null },
    status: { type: String, default: 'ALL' },
    loading: { type: Boolean, default: false }
  },
  emits: ['status', 'page', 'open-workflow', 'open-task', 'open-instance'],
  setup(props) {
    const pageBody = computed(() => (props.payload && props.payload.page) || {})
    const sort = ref('')
    const dir = ref('asc')
    const now = computed(() => Date.now())
    const instances = computed(() => {
      return (pageBody.value.instances || []).map((inst) => Object.assign({}, inst, {
        wallMs: wallClockMs(inst.startDateTime, inst.endDateTime, now.value)
      }))
    })
    const getters = {
      workflow: (row) => row.workflowName || row.workflowId || '',
      status: (row) => row.status || '',
      task: (row) => row.currentTaskName || row.currentTaskId || '',
      start: (row) => parseStamp(row.startDateTime),
      end: (row) => parseStamp(row.endDateTime),
      wall: (row) => row.wallMs
    }
    const rows = computed(() => {
      if (!sort.value) {
        return instances.value
      }
      return sortRows(instances.value, getters[sort.value] || getters.workflow, dir.value)
    })
    const statuses = computed(() => {
      const current = props.status
      if (current && STATUSES.indexOf(current) === -1) {
        return [current].concat(STATUSES)
      }
      return STATUSES
    })

    function onSort(field) {
      const next = toggleSort(field, sort.value, dir.value)
      sort.value = next.field
      dir.value = next.dir
    }

    function pillClass(status) {
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

    return {
      statuses,
      instances,
      rows,
      sort,
      dir,
      onSort,
      formatWallClock,
      page: computed(() => pageBody.value.page || 1),
      totalPages: computed(() => pageBody.value.totalPages || 1),
      pillClass
    }
  }
}
</script>

<style scoped>
.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
  margin: 1.4rem 0 1rem;
}

h2 {
  margin: 0;
}

label {
  color: var(--muted);
  font-size: 0.85rem;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.8rem;
}
</style>
