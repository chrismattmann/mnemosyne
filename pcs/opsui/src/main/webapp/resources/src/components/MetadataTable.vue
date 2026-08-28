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
  <p v-if="!keys.length" class="empty">{{ empty }}</p>
  <table v-else>
    <thead>
      <tr><th>Key</th><th>Value</th></tr>
    </thead>
    <tbody>
      <tr v-for="key in keys" :key="key">
        <td class="mono">{{ key }}</td>
        <td class="break">
          <template v-for="(item, i) in values(key)" :key="key + '-' + i">
            <span v-if="i">, </span>
            <a v-if="kind(key)" href="#" @click.prevent="open(key, item)">{{ item }}</a>
            <span v-else>{{ item }}</span>
          </template>
        </td>
      </tr>
    </tbody>
  </table>
</template>

<script>
import { computed } from 'vue'
import { classifyMetKey, metValues } from '../metLinks.js'

export default {
  name: 'MetadataTable',
  props: {
    metadata: { type: Object, default: () => ({}) },
    empty: { type: String, default: 'No metadata.' }
  },
  emits: ['open-instance', 'open-workflow', 'open-task', 'open-type', 'open-product'],
  setup(props, { emit }) {
    const keys = computed(() => Object.keys(props.metadata || {}).sort())
    function kind(key) {
      return classifyMetKey(key)
    }
    function values(key) {
      return metValues((props.metadata || {})[key])
    }
    function open(key, item) {
      const target = kind(key)
      if (target === 'instance') {
        emit('open-instance', item)
      } else if (target === 'workflow') {
        emit('open-workflow', item)
      } else if (target === 'task') {
        emit('open-task', item)
      } else if (target === 'type') {
        emit('open-type', item)
      } else if (target === 'product') {
        emit('open-product', item)
      }
    }
    return { keys, kind, values, open }
  }
}
</script>

<style scoped>
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85rem;
}

.break {
  word-break: break-all;
}
</style>
