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
    <p><a href="#" @click.prevent="$emit('back')">← {{ backLabel }}</a></p>
    <h2>{{ condition.name || 'Condition' }}</h2>
    <p v-if="loading && !condition.name" class="empty">Loading condition…</p>
    <article v-else class="card">
      <table>
        <tbody>
          <tr><th>Name</th><td>{{ condition.name }}</td></tr>
          <tr><th>ID</th><td class="mono">{{ condition.id }}</td></tr>
          <tr><th>Class</th><td class="mono">{{ condition.className }}</td></tr>
          <tr><th>Order</th><td>{{ condition.order }}</td></tr>
          <tr>
            <th>Timeout</th>
            <td>{{ condition.timeoutSeconds > 0 ? condition.timeoutSeconds + 's' : 'none' }}</td>
          </tr>
        </tbody>
      </table>
    </article>

    <article v-if="!loading" class="card">
      <h3>Properties</h3>
      <p v-if="!propertyRows.length" class="empty">This condition has no configured properties.</p>
      <table v-else>
        <thead>
          <tr><th>Name</th><th>Value</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in propertyRows" :key="row.name">
            <td class="mono">{{ row.name }}</td>
            <td class="mono">{{ row.value }}</td>
          </tr>
        </tbody>
      </table>
    </article>
  </section>
</template>

<script>
import { computed } from 'vue'

export default {
  name: 'ConditionView',
  props: {
    payload: { type: Object, default: null },
    backLabel: { type: String, default: 'Workflows' },
    loading: { type: Boolean, default: false }
  },
  emits: ['back'],
  setup(props) {
    const propertyRows = computed(() => {
      const props_ = (props.payload && props.payload.condition && props.payload.condition.properties) || {}
      return Object.keys(props_).map(name => ({ name, value: props_[name] }))
    })
    return {
      propertyRows,
      condition: computed(() => (props.payload && props.payload.condition) || {})
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
