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

    <StatusView v-if="route.view === 'status'" :report="health" :loading="loading" :refreshed-at="refreshedAt" :stale="stale" @open-product="openLatestFile" @open-instances="openInstances" @open-config="openConfig"/>
    <ConfigView v-else-if="route.view === 'config'" :payload="configPayload" :loading="loading" @back="go({ view: 'status' })"/>
    <CatalogView v-else-if="route.view === 'catalog'" :types="types" :loading="loading" :refreshed-at="refreshedAt" :stale="stale" @open="openType" @query="openSearch" @find="openProduct"/>
    <SearchView v-else-if="route.view === 'search'" :payload="searchPayload" :loading="loading" @query="openSearch" @open="openProduct" @open-type="openType" @back="go({ view: 'catalog' })"/>
    <TypeView v-else-if="route.view === 'type'" :payload="typePayload" :loading="loading" :refreshed-at="refreshedAt" :stale="stale" @more="openTypeMore" @refresh="refreshType" @open="openProduct" @back="go({ view: 'catalog' })"/>
    <ProductView v-else-if="route.view === 'product'" :payload="productPayload" :pedigree="pedigree" :loading="loading" @open-type="openType" @open-instance="openInstance" @open-workflow="openWorkflow" @open-task="openTask" @open-product="openProduct" @back="go({ view: 'catalog' })"/>
    <InstancesView v-else-if="route.view === 'instances'" :payload="instancePayload" :workflows="workflows" :status="route.status || 'ALL'" :workflow="route.workflow || ''" :since="route.since || ''" :loading="loading" :refreshed-at="refreshedAt" :stale="stale" @status="openInstances" @page="openInstancesPage" @filter-workflow="setInstanceWorkflow" @filter-since="setInstanceSince" @open-workflow="openWorkflow" @open-task="openTaskFromInstanceRow" @open-instance="openInstance" @open-product="openProduct"/>
    <InstanceView v-else-if="route.view === 'instance'" :payload="instanceDetail" :loading="loading" @open-workflow="openWorkflow" @open-task="openTaskFromInstance" @open-instance="openInstance" @open-type="openType" @open-product="openProduct" @back="backFromInstance"/>
    <ResourcesView v-else-if="route.view === 'resources'" :payload="resourcePayload" :stubs="resourceStubs" :loading="loading"/>
    <WorkflowsView v-else-if="route.view === 'workflows'" :workflows="workflows" :loading="loading" @open="openWorkflow"/>
    <WorkflowView v-else-if="route.view === 'workflow'" :payload="workflowPayload" :loading="loading" @open-task="openTaskFromWorkflow" @open-condition="openConditionFromWorkflow" @open-instance="openInstance" @open-product="openProduct" @back="go({ view: 'workflows' })"/>
    <TaskView v-else-if="route.view === 'task'" :payload="taskPayload" :back-label="route.workflowId ? 'Workflow' : 'Workflows'" :loading="loading" @open-condition="openConditionFromTask" @back="backFromTask"/>
    <ConditionView v-else-if="route.view === 'condition'" :payload="conditionPayload" :back-label="route.taskId ? 'Task' : (route.workflowId ? 'Workflow' : 'Workflows')" :loading="loading" @back="backFromCondition"/>

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
import { loadTypePages } from './catalogPages.js'
import { instancesQuery, splitHash } from './instanceHash.js'
import { POLL_MS, shouldPoll } from './pollViews.js'
import { shouldResetTypeVisit, typeFromParts, typeHash } from './typeVisit.js'

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
    const refreshedAt = ref(0)
    const stale = ref(false)
    let timer = null
    let loadSeq = 0
    const typePage = ref(1)

    function parseHash() {
      const split = splitHash(window.location.hash || '')
      const parts = split.path.split('/').filter(Boolean).map((p) => {
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
        return typeFromParts(parts)
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
          page: Number(parts[2] || 1),
          workflow: split.query.workflow || '',
          since: split.query.since || ''
        }
      }
      if (head === 'workflows') {
        return { view: 'workflows' }
      }
      if (head === 'workflow' && parts[1]) {
        return { view: 'workflow', id: parts[1] }
      }
      if (head === 'task' && parts[1]) {
        return { view: 'task', id: parts[1], workflowId: parts[2] || '' }
      }
      if (head === 'condition' && parts[1]) {
        return { view: 'condition', id: parts[1], taskId: parts[2] || '', workflowId: parts[3] || '' }
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
        return typeHash(next.name)
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
          + instancesQuery(next.workflow, next.since)
      }
      if (next.view === 'workflows') {
        return 'workflows'
      }
      if (next.view === 'workflow') {
        return 'workflow/' + encodeURIComponent(next.id)
      }
      if (next.view === 'task') {
        const workflow = next.workflowId ? '/' + encodeURIComponent(next.workflowId) : ''
        return 'task/' + encodeURIComponent(next.id) + workflow
      }
      if (next.view === 'condition') {
        const task = next.taskId ? '/' + encodeURIComponent(next.taskId) : ''
        const workflow = next.taskId && next.workflowId ? '/' + encodeURIComponent(next.workflowId) : ''
        return 'condition/' + encodeURIComponent(next.id) + task + workflow
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
      go({ view: 'type', name })
    }

    function openTypePage(page) {
      typePage.value = page || 1
      load()
    }

    function openTypeMore(page) {
      const catalog = typePayload.value && typePayload.value.catalog
      const current = catalog && catalog.page ? catalog.page : typePage.value
      const total = catalog && catalog.totalPages ? catalog.totalPages : 1
      const next = page || current + 1
      if (next > total || loading.value) {
        return
      }
      typePage.value = next
      load()
    }

    function refreshType() {
      load({ quiet: true, refreshType: true })
    }

    function hasExistingData(view) {
      if (view === 'status') {
        return Boolean(health.value)
      }
      if (view === 'catalog') {
        return types.value.length > 0
      }
      if (view === 'type') {
        return Boolean(typePayload.value)
      }
      if (view === 'instances') {
        return Boolean(instancePayload.value)
      }
      if (view === 'instance') {
        return Boolean(instanceDetail.value)
      }
      if (view === 'resources') {
        return Boolean(resourcePayload.value)
      }
      if (view === 'workflow') {
        return Boolean(workflowPayload.value)
      }
      return false
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

    function instanceRoute(extra) {
      return Object.assign({
        view: 'instances',
        status: route.value.status || 'ALL',
        page: route.value.page || 1,
        workflow: route.value.workflow || '',
        since: route.value.since || ''
      }, extra || {})
    }

    function openInstances(status) {
      go(instanceRoute({ status: status || 'ALL', page: 1 }))
    }

    function openInstancesPage(page) {
      go(instanceRoute({ page: page }))
    }

    function setInstanceWorkflow(workflow) {
      go(instanceRoute({ workflow: workflow || '', page: 1 }))
    }

    function setInstanceSince(since) {
      go(instanceRoute({ since: since || '', page: 1 }))
    }

    function backFromInstance() {
      go(instanceRoute({ view: 'instances' }))
    }

    function openInstance(id) {
      go({
        view: 'instance',
        id,
        status: route.value.status,
        workflow: route.value.workflow,
        since: route.value.since,
        page: route.value.page
      })
    }

    function openWorkflow(id) {
      go({ view: 'workflow', id })
    }

    function openTask(id, workflowId) {
      go({
        view: 'task',
        id,
        workflowId: workflowId || ''
      })
    }

    function openTaskFromWorkflow(id) {
      openTask(id, route.value.id)
    }

    function openTaskFromInstance(id) {
      const inst = instanceDetail.value && instanceDetail.value.instance
      openTask(id, (inst && inst.workflowId) || '')
    }

    function openTaskFromInstanceRow(id, workflowId) {
      openTask(id, workflowId)
    }

    function openCondition(id) {
      go({
        view: 'condition',
        id,
        taskId: route.value.view === 'task' ? route.value.id : '',
        workflowId: route.value.workflowId || ''
      })
    }

    // A condition can now be reached from a workflow as well as from a task,
    // because a workflow carries conditions of its own. Remember which one we
    // came from so "back" returns there rather than to the workflow list.
    function openConditionFromWorkflow(id) {
      go({
        view: 'condition',
        id,
        taskId: '',
        workflowId: route.value.id
      })
    }

    function openConditionFromTask(id) {
      go({
        view: 'condition',
        id,
        taskId: route.value.id,
        workflowId: route.value.workflowId || ''
      })
    }

    function backFromTask() {
      if (route.value.workflowId) {
        openWorkflow(route.value.workflowId)
      } else {
        go({ view: 'workflows' })
      }
    }

    function backFromCondition() {
      if (route.value.taskId) {
        openTask(route.value.taskId, route.value.workflowId)
      } else if (route.value.workflowId) {
        openWorkflow(route.value.workflowId)
      } else {
        go({ view: 'workflows' })
      }
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

    async function load(options) {
      const r = route.value
      const quiet = Boolean(options && options.quiet && hasExistingData(r.view))
      if (quiet && loading.value) {
        return
      }
      const seq = ++loadSeq
      if (!quiet) {
        loading.value = true
      }
      try {
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
          typePayload.value = await loadTypePages({
            name: r.name,
            through: typePage.value,
            previous: typePayload.value,
            refresh: Boolean(options && options.refreshType) || quiet,
            getPage: getTypeProducts
          })
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
          const [page, defs] = await Promise.all([
            getInstances(r.status || 'ALL', r.page || 1),
            getWorkflows()
          ])
          instancePayload.value = page
          workflows.value = defs.workflows || []
        } else if (r.view === 'instance') {
          instanceDetail.value = await getInstance(r.id)
        } else if (r.view === 'workflows') {
          const body = await getWorkflows()
          workflows.value = body.workflows || []
        } else if (r.view === 'workflow') {
          const [def, insts] = await Promise.all([
            getWorkflow(r.id),
            getInstances('ALL', 1, r.id)
          ])
          workflowPayload.value = {
            workflow: def.workflow || def,
            page: (insts && insts.page) || { instances: [] }
          }
        } else if (r.view === 'task') {
          taskPayload.value = await getTask(r.id)
        } else if (r.view === 'condition') {
          conditionPayload.value = await getCondition(r.id)
        } else if (r.view === 'config') {
          configPayload.value = await getConfig(r.id)
        }
        if (seq !== loadSeq) {
          return
        }
        error.value = ''
        if (shouldPoll(r.view)) {
          refreshedAt.value = Date.now()
          stale.value = false
        }
      } catch (e) {
        if (seq !== loadSeq) {
          return
        }
        if (hasExistingData(r.view)) {
          stale.value = true
          error.value = ''
        } else {
          error.value = e.message || String(e)
        }
      } finally {
        if (seq === loadSeq && !quiet) {
          loading.value = false
        }
      }
    }

    watch(route, (next, prev) => {
      if (shouldResetTypeVisit(prev, next)) {
        typePayload.value = null
        typePage.value = 1
      }
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
        if (shouldPoll(route.value.view)) {
          load({ quiet: true })
        }
      }, POLL_MS)
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
      route, loading, error, health, refreshedAt, stale, types, typePayload, productPayload,
      pedigree, instancePayload, instanceDetail, workflows, workflowPayload, taskPayload,
      conditionPayload, configPayload, searchPayload, resourcePayload, resourceStubs, go, openType, openTypePage, openTypeMore, refreshType, openProduct,
      openProductByPath, openLatestFile, openInstances, openInstancesPage, setInstanceWorkflow, setInstanceSince, backFromInstance, openInstance, openWorkflow, openTask, openTaskFromWorkflow, openTaskFromInstance, openTaskFromInstanceRow, openCondition, openConditionFromTask, openConditionFromWorkflow, backFromTask, backFromCondition, openConfig, openSearch
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
