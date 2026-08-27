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
    <h2>{{ task.name || 'Task' }}</h2>
    <p v-if="loading && !task.id" class="empty">Loading task…</p>
    <article v-else class="card">
      <table>
        <tbody>
          <tr><th>ID</th><td class="mono">{{ task.id }}</td></tr>
          <tr><th>Name</th><td>{{ task.name }}</td></tr>
          <tr><th>Class</th><td class="mono">{{ task.className }}</td></tr>
        </tbody>
      </table>
    </article>
  </section>
</template>

<script>
import { computed } from 'vue'

export default {
  name: 'TaskView',
  props: {
    payload: { type: Object, default: null },
    loading: { type: Boolean, default: false }
  },
  emits: ['back'],
  setup(props) {
    return {
      task: computed(() => (props.payload && props.payload.task) || {})
    }
  }
}
</script>

<style scoped>
h2 {
  margin: 0.4rem 0 0.8rem;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85rem;
}

th {
  width: 8rem;
}
</style>
