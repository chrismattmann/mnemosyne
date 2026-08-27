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
  <div v-if="totalPages > 1" class="pager">
    <button class="ghost" :disabled="page <= 1" @click="$emit('page', page - 1)">Previous</button>
    <span>Page {{ page }} of {{ totalPages }}</span>
    <button class="ghost" :disabled="page >= totalPages" @click="$emit('page', page + 1)">Next</button>
  </div>
</template>

<script>
export default {
  name: 'Pager',
  props: {
    page: { type: Number, default: 1 },
    totalPages: { type: Number, default: 1 }
  },
  emits: ['page']
}
</script>

<style scoped>
.pager {
  display: flex;
  align-items: center;
  gap: 0.8rem;
  margin: 1rem 0;
  color: var(--muted);
  font-size: 0.9rem;
}
</style>
