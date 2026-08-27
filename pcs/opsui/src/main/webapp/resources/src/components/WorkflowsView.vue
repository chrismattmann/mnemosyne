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
    <h2>Workflow definitions</h2>
    <p class="muted">Registered workflows in Workflow Manager.</p>
    <p v-if="loading && !workflows.length" class="empty">Loading workflows…</p>
    <p v-else-if="!workflows.length" class="empty">No workflow definitions.</p>
    <table v-else>
      <thead>
        <tr>
          <SortHead field="name" :sort="sort" :dir="dir" @sort="onSort">Name</SortHead>
          <th>ID</th>
          <SortHead field="taskCount" :sort="sort" :dir="dir" @sort="onSort">Tasks</SortHead>
        </tr>
      </thead>
      <tbody>
        <tr v-for="workflow in rows" :key="workflow.id">
          <td>
            <a href="#" @click.prevent="$emit('open', workflow.id)">{{ workflow.name }}</a>
          </td>
          <td class="mono">{{ workflow.id }}</td>
          <td>{{ workflow.taskCount }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<script>
import { computed, ref } from 'vue'
import SortHead from './SortHead.vue'
import { sortRows, toggleSort } from '../sort.js'

export default {
  name: 'WorkflowsView',
  components: { SortHead },
  props: {
    workflows: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  emits: ['open'],
  setup(props) {
    const sort = ref('')
    const dir = ref('asc')
    const rows = computed(() => {
      if (!sort.value) {
        return props.workflows
      }
      const getter = sort.value === 'taskCount'
        ? (row) => Number(row.taskCount) || 0
        : (row) => row.name || ''
      return sortRows(props.workflows, getter, dir.value)
    })
    function onSort(field) {
      const next = toggleSort(field, sort.value, dir.value)
      sort.value = next.field
      dir.value = next.dir
    }
    return { sort, dir, rows, onSort }
  }
}
</script>

<style scoped>
h2 {
  margin: 1.4rem 0 0.3rem;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.8rem;
}
</style>
