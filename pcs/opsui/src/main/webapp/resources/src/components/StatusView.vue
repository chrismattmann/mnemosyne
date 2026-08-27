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
      <h2>Status</h2>
      <p class="muted">{{ generated }}</p>
    </div>
    <p v-if="loading && !report" class="empty">Loading health report…</p>
    <template v-else-if="report">
      <div class="grid">
        <article v-for="daemon in daemons" :key="daemon.key" class="card">
          <h3>{{ daemon.label }}</h3>
          <p>
            <span class="pill" :class="up(daemon.status) ? 'up' : 'down'">{{ daemon.status || 'unknown' }}</span>
          </p>
          <p class="muted url">{{ daemon.url }}</p>
        </article>
      </div>

      <div class="split">
        <article class="card">
          <h3>Jobs</h3>
          <p v-if="!jobs.length" class="empty">No job counts yet.</p>
          <table v-else>
            <thead>
              <tr>
                <SortHead field="state" :sort="jobSort" :dir="jobDir" @sort="sortJobs">State</SortHead>
                <SortHead field="count" :sort="jobSort" :dir="jobDir" @sort="sortJobs">Count</SortHead>
              </tr>
            </thead>
            <tbody>
              <tr v-for="job in sortedJobs" :key="job.state">
                <td>
                  <a href="#" @click.prevent="$emit('open-instances', job.state)">{{ job.state }}</a>
                </td>
                <td>{{ job.numJobs }}</td>
              </tr>
            </tbody>
          </table>
        </article>

        <article class="card">
          <h3>Crawlers</h3>
          <p v-if="!crawlers.length" class="empty">No crawlers configured.</p>
          <table v-else>
            <thead>
              <tr><th>Name</th><th>Status</th><th>Crawls</th><th>Avg time</th></tr>
            </thead>
            <tbody>
              <tr v-for="crawler in crawlers" :key="crawler.crawlerName || crawler.crawler">
                <td>{{ crawler.crawlerName || crawler.crawler }}</td>
                <td>
                  <span class="pill" :class="up(crawler.status) ? 'up' : (crawler.status ? 'down' : 'neutral')">
                    {{ crawler.status || '—' }}
                  </span>
                </td>
                <td>{{ crawler.numCrawls != null ? crawler.numCrawls : '—' }}</td>
                <td>{{ crawler.avgCrawlTime != null ? crawler.avgCrawlTime : '—' }}</td>
              </tr>
            </tbody>
          </table>
        </article>
      </div>

      <article class="card">
        <h3>Latest files</h3>
        <p v-if="!files.length" class="empty">Nothing ingested yet.</p>
        <table v-else>
          <thead>
            <tr><th>Path</th><th>Received</th></tr>
          </thead>
          <tbody>
            <tr v-for="file in files" :key="file.id || file.filepath">
              <td>
                <a href="#" @click.prevent="$emit('open-product', file.id || file.name || file.filepath)">{{ file.name || file.filepath }}</a>
              </td>
              <td>{{ file.receivedTime }}</td>
            </tr>
          </tbody>
        </table>
      </article>

      <article v-if="stubs.length" class="card">
        <h3>Batch stubs</h3>
        <table>
          <thead>
            <tr><th>Daemon</th><th>URL</th><th>Status</th></tr>
          </thead>
          <tbody>
            <tr v-for="stub in stubs" :key="stub.url">
              <td>{{ stub.daemon }}</td>
              <td>{{ stub.url }}</td>
              <td>
                <span class="pill" :class="up(stub.status) ? 'up' : 'down'">{{ stub.status }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </article>
    </template>
  </section>
</template>

<script>
import { computed, ref } from 'vue'
import SortHead from './SortHead.vue'
import { sortRows, toggleSort } from '../sort.js'

export default {
  name: 'StatusView',
  components: { SortHead },
  props: {
    report: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['open-product', 'open-instances'],
  setup(props) {
    function up(status) {
      return String(status || '').toUpperCase() === 'UP'
    }

    const generated = computed(() => (props.report && props.report.generated) || '')

    const daemons = computed(() => {
      const status = (props.report && props.report.daemonStatus) || {}
      const rows = []
      ;[
        ['fm', 'File Manager'],
        ['wm', 'Workflow Manager'],
        ['rm', 'Resource Manager']
      ].forEach(([key, label]) => {
        const node = status[key] || {}
        rows.push({
          key,
          label,
          status: node.status,
          url: node.url
        })
      })
      return rows
    })

    const stubs = computed(() => {
      const status = (props.report && props.report.daemonStatus) || {}
      return Array.isArray(status.stubs) ? status.stubs : []
    })

    const jobs = computed(() => (props.report && props.report.jobHealth) || [])
    const jobSort = ref('')
    const jobDir = ref('asc')
    const sortedJobs = computed(() => {
      if (!jobSort.value) {
        return jobs.value
      }
      const getter = jobSort.value === 'count'
        ? (row) => Number(row.numJobs) || 0
        : (row) => row.state || ''
      return sortRows(jobs.value, getter, jobDir.value)
    })
    function sortJobs(field) {
      const next = toggleSort(field, jobSort.value, jobDir.value)
      jobSort.value = next.field
      jobDir.value = next.dir
    }

    const crawlers = computed(() => {
      const live = (props.report && props.report.crawlerStatus) || []
      const health = (props.report && props.report.ingestHealth) || []
      const byName = {}
      live.forEach((row) => {
        byName[row.crawlerName] = Object.assign({}, row)
      })
      health.forEach((row) => {
        const name = row.crawler
        byName[name] = Object.assign({}, byName[name] || {}, row)
      })
      return Object.keys(byName).map((k) => byName[k])
    })

    const files = computed(() => {
      const latest = (props.report && props.report.latestFiles) || {}
      return latest.files || []
    })

    return {
      up, generated, daemons, stubs, jobs, sortedJobs, jobSort, jobDir, sortJobs,
      crawlers, files
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

h2, h3 {
  margin: 0 0 0.7rem;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 0.8rem;
  margin-bottom: 0.8rem;
}

.split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.8rem;
  margin-bottom: 0.8rem;
}

.card {
  margin-bottom: 0.8rem;
}

.url {
  word-break: break-all;
  font-size: 0.82rem;
}

@media (max-width: 800px) {
  .split {
    grid-template-columns: 1fr;
  }
}
</style>
