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
    <p><a href="#" @click.prevent="$emit('back')">← Workflows</a></p>
    <h2>{{ workflow.name || 'Workflow' }}</h2>
    <p class="muted mono">{{ workflow.id }}</p>
    <p v-if="loading && !workflow.id" class="empty">Loading workflow…</p>
    <article class="card">
      <h3>Description</h3>
      <WorkflowGraph :tasks="tasks" :name="workflow.name" @open-task="$emit('open-task', $event)"/>
      <p v-if="!tasks.length" class="empty">No tasks on this workflow.</p>
    </article>
    <article class="card">
      <h3>Tasks</h3>
      <p v-if="!tasks.length" class="empty">No tasks on this workflow.</p>
      <table v-else>
        <thead>
          <tr>
            <th>#</th>
            <th>Name</th>
            <th>ID</th>
            <th>Class</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(task, i) in tasks" :key="task.id || i">
            <td>{{ i + 1 }}</td>
            <td>
              <a v-if="task.id" href="#" @click.prevent="$emit('open-task', task.id)">{{ task.name }}</a>
              <span v-else>{{ task.name }}</span>
            </td>
            <td class="mono">{{ task.id }}</td>
            <td class="mono">{{ task.className }}</td>
          </tr>
        </tbody>
      </table>
    </article>

    <article v-if="preConditions.length || postConditions.length" class="card">
      <h3>Conditions</h3>
      <p class="muted">Gates on the workflow itself, evaluated around its tasks.</p>
      <table>
        <thead>
          <tr>
            <th>#</th>
            <th>When</th>
            <th>Name</th>
            <th>ID</th>
            <th>Class</th>
            <th>Timeout</th>
            <th>Properties</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in workflowConditions" :key="row.when + (row.cond.id || row.index)">
            <td>{{ row.index }}</td>
            <td>{{ row.when }}</td>
            <td>
              <a v-if="row.cond.id" href="#" @click.prevent="$emit('open-condition', row.cond.id)">{{ row.cond.name }}</a>
              <span v-else>{{ row.cond.name }}</span>
            </td>
            <td class="mono">{{ row.cond.id }}</td>
            <td class="mono">{{ row.cond.className }}</td>
            <td>{{ row.cond.timeoutSeconds > 0 ? row.cond.timeoutSeconds + 's' : '—' }}</td>
            <td>{{ propertyCount(row.cond) }}</td>
          </tr>
        </tbody>
      </table>
    </article>

    <article class="card">
      <h3>Recent instances</h3>
      <p v-if="loading && !instances.length" class="empty">Loading instances…</p>
      <p v-else-if="!instances.length" class="empty">No runs of this workflow yet.</p>
      <table v-else>
        <thead>
          <tr>
            <th>ID</th>
            <th>Status</th>
            <th>Product</th>
            <th>Task</th>
            <th>Started</th>
            <th>Ended</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="inst in instances" :key="inst.id">
            <td class="mono">
              <a v-if="inst.id" href="#" @click.prevent="$emit('open-instance', inst.id)">{{ inst.id }}</a>
              <span v-else>—</span>
            </td>
            <td>
              <span class="pill" :class="pillClass(inst.status)">{{ inst.status || '—' }}</span>
            </td>
            <td>
              <a v-if="inst.productName" href="#" @click.prevent="$emit('open-product', inst.productName)">
                {{ inst.productName }}
              </a>
              <span v-else>—</span>
            </td>
            <td>
              <a v-if="inst.currentTaskId" href="#" @click.prevent="$emit('open-task', inst.currentTaskId)">
                {{ inst.currentTaskName || inst.currentTaskId }}
              </a>
              <span v-else>—</span>
            </td>
            <td>{{ inst.startDateTime || '—' }}</td>
            <td>{{ inst.endDateTime || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="truncated" class="muted shown">Showing the {{ instances.length }} most recent runs.</p>
    </article>
  </section>
</template>

<script>
import { computed } from 'vue'
import WorkflowGraph from './WorkflowGraph.vue'

export default {
  name: 'WorkflowView',
  components: { WorkflowGraph },
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['open-task', 'open-condition', 'open-instance', 'open-product', 'back'],
  setup(props) {
    const workflow = computed(() => (props.payload && props.payload.workflow) || {})
    const page = computed(() => (props.payload && props.payload.page) || {})
    return {
      workflow,
      tasks: computed(() => workflow.value.tasks || []),
      preConditions: computed(() => workflow.value.preConditions || []),
      postConditions: computed(() => workflow.value.postConditions || []),
      // Pre- then post-, numbered in declaration order: a sequential block
      // runs them in the order the workflow declared, so the order shown is
      // part of what the gate means.
      // Two conditions of the same class are told apart by their properties,
      // so the count is worth showing next to the class name; the detail view
      // lists them.
      propertyCount(cond) {
        const props = (cond && cond.properties) || {}
        const n = Object.keys(props).length
        return n === 0 ? '—' : String(n)
      },
      workflowConditions: computed(() => {
        const pre = (workflow.value.preConditions || []).map((cond, i) => ({ when: 'pre', cond, index: i + 1 }))
        const post = (workflow.value.postConditions || []).map((cond, i) => ({ when: 'post', cond, index: i + 1 }))
        return pre.concat(post)
      }),
      instances: computed(() => page.value.instances || []),
      truncated: computed(() => Boolean(page.value.truncated)),
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

.card {
  margin-top: 1rem;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.8rem;
}

.shown {
  margin-top: 0.6rem;
  font-size: 0.85rem;
}
</style>
