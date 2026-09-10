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
    <h2>Resources</h2>
    <p class="muted">{{ resource.url || 'Resource Manager' }}</p>
    <p v-if="error" class="banner">{{ error }}</p>
    <p v-else-if="loading && !resource.url" class="empty">Loading resource manager…</p>
    <template v-else>
      <p class="muted">
        Queue {{ resource.queueSize != null ? resource.queueSize : '—' }}
        / {{ resource.queueCapacity != null ? resource.queueCapacity : '—' }}
      </p>

      <article class="card">
        <h3>Nodes</h3>
        <p class="muted crawler-note">A node whose URL is a batch stub is a separate on-demand daemon. “Not running” is expected unless a batch job is in flight.</p>
        <p v-if="!nodes.length" class="empty">No nodes reported.</p>
        <table v-else>
          <thead>
            <tr>
              <th>Node</th>
              <th>URL</th>
              <th>Capacity</th>
              <th>Load</th>
              <th>Queues</th>
              <th>Daemon</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="node in nodes" :key="node.id">
              <td class="mono">{{ node.id }}</td>
              <td class="break">{{ node.url }}</td>
              <td>{{ node.capacity }}</td>
              <td>{{ node.load || '—' }}</td>
              <td>{{ (node.queues || []).join(', ') || '—' }}</td>
              <td>
                <template v-if="stubFor(node)">
                  <span class="pill" :class="onDemandPill(stubFor(node).status)">
                    {{ onDemandLabel(stubFor(node).status) }}
                  </span>
                  <span class="muted stub-name">{{ stubFor(node).daemon }}</span>
                </template>
                <span v-else>—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </article>

      <article class="card">
        <h3>Queues</h3>
        <p v-if="!queues.length" class="empty">No queues reported.</p>
        <table v-else>
          <thead>
            <tr><th>Queue</th><th>Nodes</th></tr>
          </thead>
          <tbody>
            <tr v-for="queue in queues" :key="queue.name">
              <td>{{ queue.name }}</td>
              <td class="mono">{{ (queue.nodes || []).join(', ') || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </article>

      <article class="card">
        <h3>Queued jobs</h3>
        <p v-if="!jobs.length" class="empty">No jobs in the queue.</p>
        <table v-else>
          <thead>
            <tr>
              <SortHead field="name" :sort="sort" :dir="dir" @sort="onSort">Name</SortHead>
              <SortHead field="id" :sort="sort" :dir="dir" @sort="onSort">ID</SortHead>
              <SortHead field="status" :sort="sort" :dir="dir" @sort="onSort">Status</SortHead>
              <SortHead field="queue" :sort="sort" :dir="dir" @sort="onSort">Queue</SortHead>
              <SortHead field="load" :sort="sort" :dir="dir" @sort="onSort">Load</SortHead>
            </tr>
          </thead>
          <tbody>
            <tr v-for="job in shownJobs" :key="job.id || job.name">
              <td>{{ job.name || '—' }}</td>
              <td class="mono">{{ job.id }}</td>
              <td>{{ job.status || '—' }}</td>
              <td>{{ job.queue || '—' }}</td>
              <td>{{ job.load != null ? job.load : '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="jobs.length" class="more">
          <p class="muted shown">
            Showing {{ shownJobs.length }} of {{ jobs.length }} jobs.
          </p>
          <button v-if="hasMore" type="button" @click="loadMore">
            {{ moreLabel }}
          </button>
        </div>
      </article>
    </template>
  </section>
</template>

<script>
import { computed, ref } from 'vue'
import SortHead from './SortHead.vue'
import { sortRows, toggleSort } from '../sort.js'
import { onDemandLabel, onDemandPill } from '../onDemandStatus.js'
import { stubForNode } from '../resourceStubs.js'
import {
  JOB_PAGE_SIZE,
  hasMoreJobs,
  moreJobsLabel,
  nextShown,
  visibleJobs
} from '../jobPages.js'

export default {
  name: 'ResourcesView',
  components: { SortHead },
  props: {
    payload: { type: Object, default: null },
    stubs: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  setup(props) {
    const resource = computed(() => (props.payload && props.payload.resource) || {})
    const jobs = computed(() => resource.value.jobs || [])
    // Held across refreshes rather than reset by them: the view polls, and a
    // queue that collapsed back to one page every few seconds would be worse
    // than the long table this replaces.
    const shown = ref(JOB_PAGE_SIZE)

    // Sorted before paging, so "Load more" reveals the next rows in the order
    // being read rather than the next rows of the server's order re-sorted.
    // A queue is looked at to answer "what is stuck" and "what is this node
    // doing", and neither question survives an arbitrary order.
    const sort = ref('')
    const dir = ref('asc')
    const sorted = computed(() => {
      if (!sort.value) {
        return jobs.value
      }
      const field = sort.value
      return sortRows(jobs.value, (job) => {
        const value = job ? job[field] : null
        return field === 'load' && value != null ? Number(value) : value
      }, dir.value)
    })

    return {
      resource,
      error: computed(() => resource.value.error || ''),
      nodes: computed(() => resource.value.nodes || []),
      queues: computed(() => resource.value.queues || []),
      jobs,
      sort,
      dir,
      onSort(field) {
        const next = toggleSort(field, sort.value, dir.value)
        sort.value = next.field
        dir.value = next.dir
      },
      shownJobs: computed(() => visibleJobs(sorted.value, shown.value)),
      hasMore: computed(() => hasMoreJobs(jobs.value, shown.value)),
      moreLabel: computed(() => moreJobsLabel(jobs.value, shown.value)),
      loadMore() {
        shown.value = nextShown(jobs.value, shown.value)
      },
      onDemandPill,
      onDemandLabel,
      stubFor(node) {
        return stubForNode(node, props.stubs)
      }
    }
  }
}
</script>

<style scoped>
.more {
  margin: 0.9rem 0 0.2rem;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem 1rem;
}

h2 {
  margin: 1.4rem 0 0.3rem;
}

h3 {
  margin: 0 0 0.6rem;
}

.card {
  margin-top: 1rem;
}

.crawler-note {
  margin: -0.2rem 0 0.7rem;
  font-size: 0.85rem;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.8rem;
}

.break {
  word-break: break-all;
}

.stub-name {
  margin-left: 0.4rem;
  font-size: 0.8rem;
}
</style>
