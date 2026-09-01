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
      <div>
        <h2>Workflow instances</h2>
        <RefreshNote :refreshed-at="refreshedAt" :stale="stale"/>
      </div>
      <div class="filters">
        <label>
          Status
          <select :value="status" @change="$emit('status', $event.target.value)">
            <option v-for="item in statusList" :key="item" :value="item">{{ item }}</option>
          </select>
        </label>
        <label>
          Workflow
          <select class="wide" :value="workflow" @change="$emit('filter-workflow', $event.target.value)">
            <option value="">All workflows</option>
            <option v-for="item in workflowOptions" :key="item.id || item.name" :value="item.name">
              {{ item.name }}
            </option>
          </select>
        </label>
        <label>
          On or after
          <span class="date-field">
            <input :value="since" type="date" @input="$emit('filter-since', $event.target.value)"/>
            <button v-if="since" type="button" class="ghost" @click="$emit('filter-since', '')">Clear</button>
          </span>
        </label>
      </div>
    </div>
    <p v-if="loading && !instances.length" class="empty">Loading instances…</p>
    <p v-else-if="!rows.length" class="empty">No instances for this filter.</p>
    <table v-else>
      <thead>
        <tr>
          <th>ID</th>
          <SortHead field="workflow" :sort="sort" :dir="dir" @sort="onSort">Workflow</SortHead>
          <SortHead field="product" :sort="sort" :dir="dir" @sort="onSort">Product</SortHead>
          <SortHead field="status" :sort="sort" :dir="dir" @sort="onSort">Status</SortHead>
          <SortHead field="task" :sort="sort" :dir="dir" @sort="onSort">Task</SortHead>
          <SortHead field="start" :sort="sort" :dir="dir" @sort="onSort">Started</SortHead>
          <SortHead field="end" :sort="sort" :dir="dir" @sort="onSort">Ended</SortHead>
          <SortHead field="wall" :sort="sort" :dir="dir" @sort="onSort">Wall clock</SortHead>
          <SortHead field="blocked" :sort="sort" :dir="dir" @sort="onSort">Deferred</SortHead>
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
            <a v-if="inst.productName" href="#" @click.prevent="$emit('open-product', inst.productName)">
              {{ inst.productName }}
            </a>
            <span v-else>—</span>
          </td>
          <td>
            <span class="pill" :class="pillClass(inst.status, inst.abandoned)" :title="inst.abandoned ? 'Not in the workflow engine (likely a WM restart while ' + inst.status + ')' : ''">{{ inst.status }}</span>
          </td>
          <td>
            <a v-if="inst.currentTaskId" href="#" @click.prevent="$emit('open-task', inst.currentTaskId, inst.workflowId)">
              {{ inst.currentTaskName || inst.currentTaskId }}
            </a>
            <span v-else>—</span>
            <div v-if="progressLabel(inst)" class="pge-mini">
              <div class="pge-bar"><span :style="{ width: progressPct(inst) + '%' }"></span></div>
              <span class="muted">{{ progressLabel(inst) }}</span>
            </div>
          </td>
          <td>{{ inst.startDateTime || '—' }}</td>
          <td>{{ inst.endDateTime || '—' }}</td>
          <td class="mono">{{ formatWallClock(inst.wallMs) }}</td>
          <td class="mono" :title="inst.timesBlocked ? 'Looked at and left waiting ' + inst.timesBlocked + ' times' : ''">
            {{ inst.timesBlocked || '—' }}
          </td>
        </tr>
      </tbody>
    </table>
    <p v-if="truncated" class="muted note">
      Showing the {{ shown }} most recent of {{ total }} matching instances.
    </p>
    <Pager :page="page" :total-pages="totalPages" @page="$emit('page', $event)"/>
  </section>
</template>

<script>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import Pager from './Pager.vue'
import RefreshNote from './RefreshNote.vue'
import SortHead from './SortHead.vue'
import { formatWallClock, parseStamp, sortRows, wallClockMs } from '../sort.js'
import { instanceMatches, workflowFilterOptions } from '../instanceFilter.js'
import { instanceAbandoned, instanceLive } from '../workflowGraph.js'
import { statusOptions } from '../statusOptions.js'
import { progressLabel as formatProgress, progressPct as pctProgress } from '../pgeProgress.js'

// Only for a workflow manager too old to be asked what its lifecycle
// declares. A deployment's own statuses are used when it reports them; see
// statusOptions.
const BUILT_IN_STATUSES = [
  'QUEUED', 'RSUBMIT', 'BUILDING CONFIG FILE', 'PGE EXEC', 'CRAWLING',
  'STAGING INPUT', 'FINISHED', 'STARTED', 'PAUSED', 'Executing', 'Success',
  'Failure', 'Stopped', 'Loaded', 'Blocked'
]

export default {
  name: 'InstancesView',
  components: { Pager, RefreshNote, SortHead },
  props: {
    payload: { type: Object, default: null },
    status: { type: String, default: 'ALL' },
    // What the deployment's lifecycle declares. Empty means it could not be
    // asked, and the built-in list is used instead.
    statuses: { type: Array, default: () => [] },
    workflow: { type: String, default: '' },
    since: { type: String, default: '' },
    // Owned by the route, because the order is asked of the service rather
    // than applied to what it sent back.
    sort: { type: String, default: '' },
    dir: { type: String, default: 'asc' },
    workflows: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    refreshedAt: { type: Number, default: 0 },
    stale: { type: Boolean, default: false }
  },
  emits: ['status', 'page', 'sort', 'filter-workflow', 'filter-since', 'open-workflow', 'open-task', 'open-instance', 'open-product'],
  setup(props, { emit }) {
    const pageBody = computed(() => (props.payload && props.payload.page) || {})
    const now = ref(Date.now())
    let tick = null
    onMounted(() => {
      tick = setInterval(() => {
        const list = pageBody.value.instances || []
        if (list.some((inst) => shouldTick(inst))) {
          now.value = Date.now()
        }
      }, 1000)
    })
    onUnmounted(() => {
      if (tick) {
        clearInterval(tick)
      }
    })
    const instances = computed(() => {
      return (pageBody.value.instances || []).map((inst) => Object.assign({}, inst, {
        wallMs: wallClockMs(inst.startDateTime, inst.endDateTime, now.value, inst.status, inst.running),
        abandoned: instanceAbandoned(inst)
      }))
    })
    const getters = {
      workflow: (row) => row.workflowName || row.workflowId || '',
      product: (row) => row.productName || '',
      status: (row) => row.status || '',
      task: (row) => row.currentTaskName || row.currentTaskId || '',
      start: (row) => parseStamp(row.startDateTime),
      end: (row) => parseStamp(row.endDateTime),
      wall: (row) => row.wallMs,
      // Zero is an answer, not an absence: it was never put off. Reported as
      // a dash and sorted as the number it is.
      blocked: (row) => (typeof row.timesBlocked === 'number' ? row.timesBlocked : 0)
    }
    const filtered = computed(() => {
      return instances.value.filter((inst) => instanceMatches(inst, props.workflow, props.since))
    })
    // The service returns these already ordered. Ordering them again is a
    // no-op against a current service and keeps the column working against
    // one too old to know the parameters.
    const rows = computed(() => {
      if (!props.sort) {
        return filtered.value
      }
      return sortRows(filtered.value, getters[props.sort] || getters.workflow, props.dir)
    })
    const workflowOptions = computed(() => workflowFilterOptions(instances.value, props.workflows))
    // Named statusList because the reported statuses arrive as a prop called
    // statuses; this is what the filter actually offers.
    const statusList = computed(() => {
      const options = statusOptions(props.statuses, BUILT_IN_STATUSES)
      const current = props.status
      // A status already being filtered on stays selectable even if the
      // lifecycle does not name it -- a link can arrive with one.
      if (current && options.indexOf(current) === -1) {
        return [current].concat(options)
      }
      return options
    })

    function shouldTick(inst) {
      if (!inst || inst.endDateTime || instanceAbandoned(inst)) {
        return false
      }
      if (inst.running === true) {
        return true
      }
      if (inst.running === false) {
        return false
      }
      return instanceLive(inst.status)
    }

    function onSort(field) {
      emit('sort', field)
    }

    function pillClass(status, abandoned) {
      if (abandoned) {
        return 'down'
      }
      const value = String(status || '').toUpperCase()
      if (value === 'FINISHED' || value === 'SUCCESS' || value === 'EXECUTIONCOMPLETE') {
        return 'up'
      }
      if (value === 'FAILURE' || value === 'RESULTSFAILURE' || value === 'STOPPED' || value === 'ERROR') {
        return 'down'
      }
      if (value === 'PGE EXEC' || value === 'EXECUTING' || value === 'CRAWLING') {
        return 'warn'
      }
      return 'neutral'
    }

    return {
      statusList,
      instances,
      rows,
      workflowOptions,
      onSort,
      formatWallClock,
      page: computed(() => pageBody.value.page || 1),
      totalPages: computed(() => pageBody.value.totalPages || 1),
      truncated: computed(() => pageBody.value.truncated === true),
      total: computed(() => pageBody.value.total || 0),
      shown: computed(() => pageBody.value.shown || 0),
      pillClass,
      progressLabel(inst) {
        return formatProgress(inst && inst.pgeProgress)
      },
      progressPct(inst) {
        return pctProgress(inst && inst.pgeProgress)
      }
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

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem 1rem;
  align-items: end;
}

label {
  color: var(--muted);
  font-size: 0.85rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

/*
 * The workflow list is instance data, so its widest entry decides how wide
 * the control is: a page holding "task-workflow|<uuid>|urn:drat:RatCodeAudit"
 * sizes it to seventy-odd characters and pushes the date filter onto a row of
 * its own. Which instances came back should not rearrange the filters, so the
 * width is fixed and long names are clipped rather than allowed to spread.
 */
.filters select.wide {
  width: 16rem;
  max-width: 100%;
  text-overflow: ellipsis;
}

.date-field {
  display: flex;
  align-items: center;
  gap: 0.35rem;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.8rem;
}

.note {
  font-size: 0.8rem;
  margin: 0.6rem 0 0;
}

.pge-mini {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  margin-top: 0.25rem;
  font-size: 0.72rem;
}

.pge-bar {
  width: 4.5rem;
  height: 0.4rem;
  background: var(--line);
  border-radius: 999px;
  overflow: hidden;
  flex: 0 0 auto;
}

.pge-bar span {
  display: block;
  height: 100%;
  background: var(--copper);
}
</style>
