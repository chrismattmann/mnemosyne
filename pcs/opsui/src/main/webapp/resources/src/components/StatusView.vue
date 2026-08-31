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
      <p class="muted">
        <span v-if="generated">Report {{ generated }}</span>
        <span v-if="ago"> · refreshed {{ ago }}</span>
        <span v-if="stale" class="pill warn">stale</span>
      </p>
    </div>
    <p v-if="loading && !report" class="empty">Loading health report…</p>
    <template v-else-if="report">
      <div class="grid">
        <article v-for="daemon in daemons" :key="daemon.key" class="card daemon">
          <h3>
            <a href="#" @click.prevent="$emit('open-config', daemon.key)">{{ daemon.label }}</a>
          </h3>
          <p>
            <span class="pill" :class="up(daemon.status) ? 'up' : 'down'">{{ daemon.status || 'unknown' }}</span>
          </p>
          <p class="muted url">{{ daemon.url }}</p>
        </article>
      </div>

      <div class="split">
        <article class="card">
          <h3>Jobs</h3>
          <p v-if="!jobsKnown" class="empty">
            Workflow manager unreachable, so how many jobs are in each state is
            not known.
          </p>
          <p v-else-if="!jobs.length" class="empty">No job counts yet.</p>
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
          <p class="muted crawler-note">Crawlers start on demand. They are not a standing daemon, so “not running” is expected unless a crawl is in flight.</p>
          <p v-if="!crawlers.length" class="empty">No crawlers configured.</p>
          <table v-else>
            <thead>
              <tr><th>Name</th><th>Status</th><th>Crawls</th><th>Avg time</th></tr>
            </thead>
            <tbody>
              <tr v-for="crawler in crawlers" :key="crawler.crawlerName || crawler.crawler">
                <td>{{ crawler.crawlerName || crawler.crawler }}</td>
                <td>
                  <span class="pill" :class="onDemandPill(crawler.status)">
                    {{ onDemandLabel(crawler.status) }}
                  </span>
                </td>
                <td>{{ crawlCount(crawler) }}</td>
                <td>{{ avgTime(crawler) }}</td>
              </tr>
            </tbody>
          </table>
        </article>
      </div>

      <article class="card">
        <h3>Latest files</h3>
        <p v-if="!files.length" class="empty">{{ filesEmpty }}</p>
        <table v-else>
          <thead>
            <tr><th>Name</th><th>Received</th></tr>
          </thead>
          <tbody>
            <tr v-for="file in files" :key="file.id || file.filepath">
              <td>
                <a href="#" @click.prevent="$emit('open-product', file.id || file.name)">{{ file.name || file.filepath }}</a>
              </td>
              <td>{{ file.receivedTime }}</td>
            </tr>
          </tbody>
        </table>
      </article>

      <article v-if="stubs.length" class="card">
        <h3>Batch stubs</h3>
        <p class="muted crawler-note">Batch stubs are separate daemons from the Resource Manager. They start on demand, so “not running” is expected unless a batch job is in flight.</p>
        <table>
          <thead>
            <tr><th>Daemon</th><th>URL</th><th>Status</th></tr>
          </thead>
          <tbody>
            <tr v-for="stub in stubs" :key="stub.url">
              <td>{{ stub.daemon }}</td>
              <td class="url">{{ stub.url }}</td>
              <td>
                <span class="pill" :class="onDemandPill(stub.status)">{{ onDemandLabel(stub.status) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </article>
    </template>
  </section>
</template>

<script>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import SortHead from './SortHead.vue'
import { sortRows, toggleSort } from '../sort.js'
import { onDemandLabel, onDemandPill } from '../onDemandStatus.js'
import { formatAgo } from '../statusRefresh.js'

export default {
  name: 'StatusView',
  components: { SortHead },
  props: {
    report: { type: Object, default: null },
    loading: { type: Boolean, default: false },
    refreshedAt: { type: Number, default: 0 },
    stale: { type: Boolean, default: false }
  },
  emits: ['open-product', 'open-instances', 'open-config'],
  setup(props) {
    function up(status) {
      return String(status || '').toUpperCase() === 'UP'
    }

    function missingStat(value) {
      return value == null || value < 0
    }

    function crawlCount(crawler) {
      return missingStat(crawler.numCrawls) ? 'None' : crawler.numCrawls
    }

    function avgTime(crawler) {
      return missingStat(crawler.avgCrawlTime) ? 'N/A' : crawler.avgCrawlTime
    }

    const generated = computed(() => (props.report && props.report.generated) || '')
    const now = ref(Date.now())
    let tick = null
    const ago = computed(() => formatAgo(props.refreshedAt, now.value))
    onMounted(() => {
      tick = setInterval(() => {
        now.value = Date.now()
      }, 1000)
    })
    onUnmounted(() => {
      if (tick) {
        clearInterval(tick)
      }
    })

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
    // Counting jobs means asking the workflow manager. With the manager down
    // there is nothing to ask and the list arrives empty, which reads exactly
    // like a deployment with no jobs -- and a table of zeroes would state
    // that there is no work at the moment we cannot see any of it. A service
    // too old to say either way is taken at its word, so nothing changes for
    // one that does not send this.
    const jobsKnown = computed(() => {
      const report = props.report || {}
      return report.jobHealthAvailable !== false
    })
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

    const filesEmpty = computed(() => {
      const fm = ((props.report && props.report.daemonStatus) || {}).fm || {}
      if (up(fm.status)) {
        return 'No recent ingestions in File Manager.'
      }
      return 'Nothing ingested yet.'
    })

    return {
      up, onDemandPill, onDemandLabel, crawlCount, avgTime, generated, ago, daemons, stubs, jobs, jobsKnown, sortedJobs,
      jobSort, jobDir, sortJobs, crawlers, files, filesEmpty
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

.crawler-note {
  margin: -0.2rem 0 0.7rem;
  font-size: 0.85rem;
}

.daemon h3 {
  margin-bottom: 0.45rem;
}

@media (max-width: 800px) {
  .split {
    grid-template-columns: 1fr;
  }
}
</style>
