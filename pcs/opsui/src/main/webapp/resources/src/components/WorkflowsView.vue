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
          <th>Name</th>
          <th>ID</th>
          <th>Tasks</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="workflow in workflows" :key="workflow.id">
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
export default {
  name: 'WorkflowsView',
  props: {
    workflows: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  emits: ['open']
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
