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

    <article class="card">
      <h3>Configuration</h3>
      <p v-if="!propKeys.length" class="empty">No static properties on this task.</p>
      <table v-else>
        <thead>
          <tr><th>Property</th><th>Value</th></tr>
        </thead>
        <tbody>
          <tr v-for="key in propKeys" :key="key">
            <td class="mono">{{ key }}</td>
            <td class="break">{{ properties[key] }}</td>
          </tr>
        </tbody>
      </table>
    </article>

    <article v-if="required.length" class="card">
      <h3>Required metadata</h3>
      <ul>
        <li v-for="field in required" :key="field" class="mono">{{ field }}</li>
      </ul>
    </article>

    <article class="card">
      <h3>Conditions</h3>
      <p v-if="!conditions.length" class="empty">No pre- or post-conditions.</p>
      <table v-else>
        <thead>
          <tr><th>When</th><th>Name</th><th>Class</th></tr>
        </thead>
        <tbody>
          <tr v-for="cond in conditions" :key="cond.phase + (cond.id || cond.name)">
            <td>{{ cond.phase }}</td>
            <td>
              <a v-if="cond.id" href="#" @click.prevent="$emit('open-condition', cond.id)">{{ cond.name || cond.id }}</a>
              <span v-else>{{ cond.name }}</span>
            </td>
            <td class="mono">{{ cond.className }}</td>
          </tr>
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
  emits: ['back', 'open-condition'],
  setup(props) {
    const task = computed(() => (props.payload && props.payload.task) || {})
    const properties = computed(() => task.value.properties || {})
    const propKeys = computed(() => Object.keys(properties.value))
    const required = computed(() => task.value.requiredMetFields || [])
    const conditions = computed(() => {
      const pre = (task.value.preConditions || []).map((c) => Object.assign({ phase: 'Pre' }, c))
      const post = (task.value.postConditions || []).map((c) => Object.assign({ phase: 'Post' }, c))
      return pre.concat(post)
    })
    return { task, properties, propKeys, required, conditions }
  }
}
</script>

<style scoped>
h2 {
  margin: 0.4rem 0 0.8rem;
}

h3 {
  margin: 0 0 0.6rem;
}

.card {
  margin-bottom: 0.8rem;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85rem;
}

.break {
  word-break: break-all;
}

th {
  width: 10rem;
}

ul {
  margin: 0;
  padding-left: 1.2rem;
}
</style>
