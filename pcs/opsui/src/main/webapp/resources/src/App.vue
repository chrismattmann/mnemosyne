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
  <div class="shell">
    <header class="mast">
      <div class="brand">
        <svg class="mark" viewBox="0 0 32 32" aria-hidden="true">
          <rect x="4" y="6" width="24" height="20" rx="2" fill="none" stroke="currentColor" stroke-width="2"/>
          <path d="M8 12h16M8 16h10M8 20h13" stroke="currentColor" stroke-width="2" fill="none"/>
        </svg>
        <div>
          <h1>OPSUI</h1>
          <p>Mnemosyne</p>
        </div>
      </div>
      <nav>
        <button :class="{ active: route.view === 'status' || route.view === 'config' }" @click="go({ view: 'status' })">Status</button>
        <button :class="{ active: route.view === 'catalog' || route.view === 'type' || route.view === 'product' || route.view === 'search' }" @click="go({ view: 'catalog' })">Catalog</button>
        <button :class="{ active: route.view === 'instances' || route.view === 'instance' }" @click="go({ view: 'instances' })">Instances</button>
        <button :class="{ active: route.view === 'workflows' || route.view === 'workflow' || route.view === 'task' || route.view === 'condition' }" @click="go({ view: 'workflows' })">Workflows</button>
        <button :class="{ active: route.view === 'resources' }" @click="go({ view: 'resources' })">Resources</button>
      </nav>
    </header>

    <StatusView v-if="route.view === 'status'" :report="health" :loading="loading" @open-product="openLatestFile" @open-instances="openInstances" @open-config="openConfig"/>
    <ConfigView v-else-if="route.view === 'config'" :payload="configPayload" :loading="loading" @back="go({ view: 'status' })"/>
    <CatalogView v-else-if="route.view === 'catalog'" :types="types" :loading="loading" @open="openType" @query="openSearch"/>
    <SearchView v-else-if="route.view === 'search'" :payload="searchPayload" :loading="loading" @query="openSearch" @open="openProduct" @open-type="openType" @back="go({ view: 'catalog' })"/>
    <TypeView v-else-if="route.view === 'type'" :payload="typePayload" :loading="loading" @more="openTypeMore" @open="openProduct" @back="go({ view: 'catalog' })"/>
    <ProductView v-else-if="route.view === 'product'" :payload="productPayload" :pedigree="pedigree" :loading="loading" @open-type="openType" @open-instance="openInstance" @open-workflow="openWorkflow" @open-task="openTask" @open-product="openProduct" @back="go({ view: 'catalog' })"/>
    <InstancesView v-else-if="route.view === 'instances'" :payload="instancePayload" :status="route.status || 'ALL'" :loading="loading" @status="openInstances" @page="openInstancesPage" @open-workflow="openWorkflow" @open-task="openTask" @open-instance="openInstance" @open-product="openProduct"/>
    <InstanceView v-else-if="route.view === 'instance'" :payload="instanceDetail" :loading="loading" @open-workflow="openWorkflow" @open-task="openTask" @open-instance="openInstance" @open-type="openType" @open-product="openProduct" @back="go({ view: 'instances', status: route.status || 'ALL', page: 1 })"/>
    <ResourcesView v-else-if="route.view === 'resources'" :payload="resourcePayload" :stubs="resourceStubs" :loading="loading"/>
    <WorkflowsView v-else-if="route.view === 'workflows'" :workflows="workflows" :loading="loading" @open="openWorkflow"/>
    <WorkflowView v-else-if="route.view === 'workflow'" :payload="workflowPayload" :loading="loading" @open-task="openTask" @back="go({ view: 'workflows' })"/>
    <TaskView v-else-if="route.view === 'task'" :payload="taskPayload" :loading="loading" @open-condition="openCondition" @back="go({ view: 'workflows' })"/>
    <ConditionView v-else-if="route.view === 'condition'" :payload="conditionPayload" :loading="loading" @back="go({ view: 'workflows' })"/>

    <p v-if="error" class="banner">{{ error }}</p>
  </div>
</template>

<script>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import StatusView from './components/StatusView.vue'
import CatalogView from './components/CatalogView.vue'
import SearchView from './components/SearchView.vue'
import TypeView from './components/TypeView.vue'
import ProductView from './components/ProductView.vue'
import InstancesView from './components/InstancesView.vue'
import InstanceView from './components/InstanceView.vue'
import ResourcesView from './components/ResourcesView.vue'
import WorkflowsView from './components/WorkflowsView.vue'
import WorkflowView from './components/WorkflowView.vue'
import TaskView from './components/TaskView.vue'
import ConditionView from './components/ConditionView.vue'
import ConfigView from './components/ConfigView.vue'
import {
  getCondition, getConfig, getHealth, getInstance, getInstances, getPedigree, getProduct,
  getResources, getTask, getTypeProducts, getTypes, getWorkflow, getWorkflows, queryCatalog
} from './api.js'
import { catalogSqlError } from './sqlQuery.js'
import { mergeTypeCatalog } from './catalogPages.js'

export default {
  name: 'App',
  components: {
    StatusView, CatalogView, SearchView, TypeView, ProductView, InstancesView, InstanceView,
    ResourcesView, WorkflowsView, WorkflowView, TaskView, ConditionView, ConfigView
  },
  setup() {
    const route = ref(parseHash())
    const loading = ref(false)
    const error = ref('')
    const health = ref(null)
    const types = ref([])
    const typePayload = ref(null)
    const productPayload = ref(null)
    const pedigree = ref(null)
    const instancePayload = ref(null)
    const instanceDetail = ref(null)
    const workflows = ref([])
    const workflowPayload = ref(null)
    const taskPayload = ref(null)
    const conditionPayload = ref(null)
    const configPayload = ref(null)
    const searchPayload = ref(null)
    const resourcePayload = ref(null)
    let timer = null

    function parseHash() {
      const raw = (window.location.hash || '').replace(/^#\/?/, '')
      const parts = raw.split('/').filter(Boolean).map((p) => {
        try {
          return decodeURIComponent(p)
        } catch (e) {
          return p
        }
      })
      if (!parts.length || parts[0] === 'status') {
        return { view: 'status' }
      }
      const head = parts[0]
      if (head === 'catalog' && parts[1]) {
        return { view: 'type', name: parts[1], page: Number(parts[2] || 1) }
      }
      if (head === 'catalog') {
        return { view: 'catalog' }
      }
      if (head === 'search') {
        return { view: 'search', sql: parts.slice(1).join('/') }
      }
      if (head === 'resources') {
        return { view: 'resources' }
      }
      if (head === 'product' && parts[1]) {
        return { view: 'product', id: parts[1] }
      }
      if (head === 'instance' && parts[1]) {
        return { view: 'instance', id: parts[1] }
      }
      if (head === 'instances') {
        return {
          view: 'instances',
          status: parts[1] || 'ALL',
          page: Number(parts[2] || 1)
        }
      }
      if (head === 'workflows') {
        return { view: 'workflows' }
      }
      if (head === 'workflow' && parts[1]) {
        return { view: 'workflow', id: parts[1] }
      }
      if (head === 'task' && parts[1]) {
        return { view: 'task', id: parts[1] }
      }
      if (head === 'condition' && parts[1]) {
        return { view: 'condition', id: parts[1] }
      }
      if (head === 'config' && parts[1]) {
        return { view: 'config', id: parts[1] }
      }
      return { view: 'status' }
    }

    function hashFor(next) {
      if (next.view === 'status') {
        return 'status'
      }
      if (next.view === 'catalog') {
        return 'catalog'
      }
      if (next.view === 'search') {
        return 'search/' + encodeURIComponent(next.sql || '')
      }
      if (next.view === 'resources') {
        return 'resources'
      }
      if (next.view === 'type') {
        const page = next.page && next.page !== 1 ? '/' + next.page : ''
        return 'catalog/' + encodeURIComponent(next.name) + page
      }
      if (next.view === 'product') {
        return 'product/' + encodeURIComponent(next.id)
      }
      if (next.view === 'instance') {
        return 'instance/' + encodeURIComponent(next.id)
      }
      if (next.view === 'instances') {
        const status = next.status || 'ALL'
        const page = next.page && next.page !== 1 ? '/' + next.page : ''
        return 'instances/' + encodeURIComponent(status) + page
      }
      if (next.view === 'workflows') {
        return 'workflows'
      }
      if (next.view === 'workflow') {
        return 'workflow/' + encodeURIComponent(next.id)
      }
      if (next.view === 'task') {
        return 'task/' + encodeURIComponent(next.id)
      }
      if (next.view === 'condition') {
        return 'condition/' + encodeURIComponent(next.id)
      }
      if (next.view === 'config') {
        return 'config/' + encodeURIComponent(next.id)
      }
      return 'status'
    }

    function go(next) {
      route.value = next
    }

    function writeHash() {
      const next = hashFor(route.value)
      if (window.location.hash.replace(/^#/, '') !== next) {
        window.location.hash = next
      }
    }

    function openType(name) {
      go({ view: 'type', name, page: 1 })
    }

    function openTypePage(page) {
      go({ view: 'type', name: route.value.name, page })
    }

    function openTypeMore(page) {
      const catalog = typePayload.value && typePayload.value.catalog
      const current = catalog && catalog.page ? catalog.page : (route.value.page || 1)
      const total = catalog && catalog.totalPages ? catalog.totalPages : 1
      const next = page || current + 1
      if (next > total || loading.value) {
        return
      }
      go({ view: 'type', name: route.value.name, page: next })
    }

    function openProduct(id) {
      go({ view: 'product', id })
    }

    function openProductByPath(id) {
      if (!id) {
        return
      }
      const name = String(id).split('/').filter(Boolean).pop()
      if (name) {
        go({ view: 'product', id: name })
      }
    }

    function openLatestFile(id) {
      if (!id) {
        return
      }
      if (String(id).indexOf('/') >= 0) {
        openProductByPath(id)
      } else {
        openProduct(id)
      }
    }

    function openInstances(status) {
      go({ view: 'instances', status: status || 'ALL', page: 1 })
    }

    function openInstancesPage(page) {
      go({
        view: 'instances',
        status: route.value.status || 'ALL',
        page
      })
    }

    function openInstance(id) {
      go({ view: 'instance', id })
    }

    function openWorkflow(id) {
      go({ view: 'workflow', id })
    }

    function openTask(id) {
      go({ view: 'task', id })
    }

    function openCondition(id) {
      go({ view: 'condition', id })
    }

    function openConfig(id) {
      go({ view: 'config', id })
    }

    function openSearch(sql) {
      const trimmed = String(sql || '').trim()
      if (!trimmed) {
        go({ view: 'catalog' })
        return
      }
      go({ view: 'search', sql: trimmed })
    }

    async function load() {
      loading.value = true
      try {
        const r = route.value
        if (r.view === 'status') {
          const body = await getHealth()
          health.value = body.report || body
        } else if (r.view === 'catalog') {
          const body = await getTypes()
          types.value = body.types || []
        } else if (r.view === 'search') {
          const sql = r.sql || ''
          const sqlError = catalogSqlError(sql)
          if (sqlError) {
            searchPayload.value = { query: { sql, error: sqlError, results: [] } }
          } else {
            searchPayload.value = await queryCatalog(sql)
          }
        } else if (r.view === 'resources') {
          const [res, healthBody] = await Promise.all([getResources(), getHealth()])
          resourcePayload.value = res
          health.value = healthBody.report || healthBody
        } else if (r.view === 'type') {
          const through = r.page || 1
          const current = typePayload.value && typePayload.value.catalog
          const currentName = current && current.type && current.type.name
          let havePage = 0
          if (currentName === r.name) {
            havePage = Number(current.page) || 0
          } else {
            typePayload.value = null
          }
          for (let p = havePage + 1; p <= through; p++) {
            const body = await getTypeProducts(r.name, p)
            typePayload.value = mergeTypeCatalog(typePayload.value, body)
            const total = (body.catalog && body.catalog.totalPages) || 1
            if (p >= total) {
              break
            }
          }
        } else if (r.view === 'product') {
          const body = await getProduct(r.id)
          if (body && body.missing) {
            productPayload.value = body
            pedigree.value = null
          } else {
            productPayload.value = body.product || body
            pedigree.value = null
            const name = (body.product && body.product.name) || r.id
            try {
              pedigree.value = await getPedigree(name)
            } catch (e) {
              pedigree.value = { error: e.message }
            }
          }
        } else if (r.view === 'instances') {
          instancePayload.value = await getInstances(r.status || 'ALL', r.page || 1)
        } else if (r.view === 'instance') {
          instanceDetail.value = await getInstance(r.id)
        } else if (r.view === 'workflows') {
          const body = await getWorkflows()
          workflows.value = body.workflows || []
        } else if (r.view === 'workflow') {
          workflowPayload.value = await getWorkflow(r.id)
        } else if (r.view === 'task') {
          taskPayload.value = await getTask(r.id)
        } else if (r.view === 'condition') {
          conditionPayload.value = await getCondition(r.id)
        } else if (r.view === 'config') {
          configPayload.value = await getConfig(r.id)
        }
        error.value = ''
      } catch (e) {
        error.value = e.message || String(e)
      } finally {
        loading.value = false
      }
    }

    watch(route, () => {
      writeHash()
      load()
    })

    onMounted(() => {
      window.addEventListener('hashchange', () => {
        route.value = parseHash()
      })
      writeHash()
      load()
      timer = setInterval(() => {
        if (route.value.view === 'status' || route.value.view === 'instances' || route.value.view === 'instance' || route.value.view === 'resources') {
          load()
        }
      }, 8000)
    })

    onUnmounted(() => {
      if (timer) {
        clearInterval(timer)
      }
    })

    const resourceStubs = computed(() => {
      const status = (health.value && health.value.daemonStatus) || {}
      return Array.isArray(status.stubs) ? status.stubs : []
    })

    return {
      route, loading, error, health, types, typePayload, productPayload,
      pedigree, instancePayload, instanceDetail, workflows, workflowPayload, taskPayload,
      conditionPayload, configPayload, searchPayload, resourcePayload, resourceStubs, go, openType, openTypePage, openTypeMore, openProduct,
      openProductByPath, openLatestFile, openInstances, openInstancesPage, openInstance, openWorkflow, openTask, openCondition, openConfig, openSearch
    }
  }
}
</script>

<style scoped>
.shell {
  max-width: 1180px;
  margin: 0 auto;
  padding: 0 1.25rem 3rem;
}

.mast {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  padding: 1.4rem 0 1rem;
  border-bottom: 3px solid var(--copper);
}

.brand {
  display: flex;
  align-items: center;
  gap: 0.8rem;
  color: var(--copper);
}

.mark {
  width: 2.3rem;
  height: 2.3rem;
}

h1 {
  font-size: 1.85rem;
  line-height: 1;
  margin: 0;
  letter-spacing: 0.04em;
}

.brand p {
  margin: 0.15rem 0 0;
  font-size: 0.78rem;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--muted);
}

nav {
  display: flex;
  gap: 0.35rem;
}

nav button {
  background: transparent;
  color: var(--muted);
  border-bottom: 2px solid transparent;
  border-radius: 0;
  padding: 0.35rem 0.55rem;
}

nav button.active {
  color: var(--copper);
  border-bottom-color: var(--gold);
}
</style>
