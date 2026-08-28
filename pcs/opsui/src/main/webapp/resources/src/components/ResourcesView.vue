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
        <p v-if="!nodes.length" class="empty">No nodes reported.</p>
        <table v-else>
          <thead>
            <tr>
              <th>Node</th>
              <th>URL</th>
              <th>Capacity</th>
              <th>Load</th>
              <th>Queues</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="node in nodes" :key="node.id">
              <td class="mono">{{ node.id }}</td>
              <td class="break">{{ node.url }}</td>
              <td>{{ node.capacity }}</td>
              <td>{{ node.load || '—' }}</td>
              <td>{{ (node.queues || []).join(', ') || '—' }}</td>
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
              <th>Name</th>
              <th>ID</th>
              <th>Status</th>
              <th>Queue</th>
              <th>Load</th>
              <th>Node</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="job in jobs" :key="job.id || job.name">
              <td>{{ job.name || '—' }}</td>
              <td class="mono">{{ job.id }}</td>
              <td>{{ job.status || '—' }}</td>
              <td>{{ job.queue || '—' }}</td>
              <td>{{ job.load != null ? job.load : '—' }}</td>
              <td class="mono">{{ job.node || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </article>
    </template>
  </section>
</template>

<script>
import { computed } from 'vue'

export default {
  name: 'ResourcesView',
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  setup(props) {
    const resource = computed(() => (props.payload && props.payload.resource) || {})
    return {
      resource,
      error: computed(() => resource.value.error || ''),
      nodes: computed(() => resource.value.nodes || []),
      queues: computed(() => resource.value.queues || []),
      jobs: computed(() => resource.value.jobs || [])
    }
  }
}
</script>

<style scoped>
h2 {
  margin: 1.4rem 0 0.3rem;
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
</style>
