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
  <p v-if="!nodes.length && empty" class="empty">{{ empty }}</p>
  <div v-else-if="nodes.length" class="graph" role="img" :aria-label="label">
    <template v-for="(node, i) in nodes" :key="node.role + '-' + node.name + '-' + i">
      <button
        class="bubble"
        type="button"
        :class="node.role"
        @click="$emit('open', node.name)">
        <span class="idx">{{ node.role === 'self' ? 'this' : node.role === 'up' ? 'from' : 'into' }}</span>
        <span class="name">{{ node.name }}</span>
      </button>
      <span v-if="i < nodes.length - 1" class="arrow" aria-hidden="true">
        <svg viewBox="0 0 48 16" width="48" height="16">
          <line x1="0" y1="8" x2="36" y2="8" stroke="currentColor" stroke-width="2"/>
          <polygon points="36,2 48,8 36,14" fill="currentColor"/>
        </svg>
      </span>
    </template>
  </div>
</template>

<script>
import { lineageChain } from '../lineage.js'

export default {
  name: 'LineageGraph',
  props: {
    upstream: { default: null },
    downstream: { default: null },
    selfName: { type: String, default: '' },
    empty: { type: String, default: '' }
  },
  emits: ['open'],
  computed: {
    nodes() {
      return lineageChain(this.upstream, this.downstream, this.selfName)
    },
    label() {
      return this.nodes.map((n) => n.name).join(' then ')
    }
  }
}
</script>

<style scoped>
.graph {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.15rem 0.35rem;
  padding: 0.4rem 0 0.2rem;
}

.bubble {
  width: 7.2rem;
  min-height: 6.4rem;
  border: 2px dashed #c4b8a6;
  background: #eeeae4;
  color: var(--ink);
  border-radius: 999px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.25rem;
  padding: 0.55rem 0.4rem;
  text-align: center;
}

.bubble:hover {
  background: #e4d6c4;
  border-color: var(--copper);
}

.bubble.self {
  border-style: solid;
  border-color: var(--copper);
  background: #f4e6d4;
}

.bubble.up,
.bubble.down {
  border-style: solid;
  border-color: #7c8f4a;
  background: #e8f0d8;
}

.idx {
  font-size: 0.7rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--muted);
  font-weight: 700;
}

.name {
  font-size: 0.78rem;
  line-height: 1.2;
  font-weight: 700;
  word-break: break-word;
}

.arrow {
  color: var(--copper);
  display: flex;
  align-items: center;
}
</style>
