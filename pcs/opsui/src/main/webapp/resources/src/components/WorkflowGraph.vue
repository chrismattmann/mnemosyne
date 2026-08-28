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
  <div v-if="tasks.length" class="graph" role="img" :aria-label="label">
    <template v-for="(task, i) in tasks" :key="task.id || i">
      <button class="bubble" type="button" :class="bubbleClass(task)" @click="$emit('open-task', task.id)">
        <span class="idx">{{ i + 1 }}</span>
        <span class="name">{{ task.name || task.id }}</span>
      </button>
      <span v-if="i < tasks.length - 1" class="arrow" aria-hidden="true">
        <svg viewBox="0 0 48 16" width="48" height="16">
          <line x1="0" y1="8" x2="36" y2="8" stroke="currentColor" stroke-width="2"/>
          <polygon points="36,2 48,8 36,14" fill="currentColor"/>
        </svg>
      </span>
    </template>
  </div>
</template>

<script>
export default {
  name: 'WorkflowGraph',
  props: {
    tasks: { type: Array, default: () => [] },
    name: { type: String, default: '' },
    currentTaskId: { type: String, default: '' },
    status: { type: String, default: '' }
  },
  emits: ['open-task'],
  computed: {
    label() {
      const names = (this.tasks || []).map((t) => t.name || t.id).join(' then ')
      return (this.name ? this.name + ': ' : '') + names
    },
    finished() {
      const value = String(this.status || '').toUpperCase()
      return value === 'FINISHED' || value === 'SUCCESS' || value === 'EXECUTIONCOMPLETE'
    }
  },
  methods: {
    bubbleClass(task) {
      const current = this.currentTaskId && task && task.id === this.currentTaskId
      return { current, done: this.finished }
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
  width: 6.4rem;
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

.bubble.current {
  border-style: solid;
  border-color: var(--copper);
  background: #f4e6d4;
}

.bubble.done {
  border-style: solid;
  border-color: #7c8f4a;
  background: #e8f0d8;
}

.bubble.done.current {
  border-color: var(--copper);
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
