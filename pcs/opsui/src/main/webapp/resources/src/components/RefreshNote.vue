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
  <p v-if="ago || stale" class="muted refresh">
    <span v-if="ago">refreshed {{ ago }}</span>
    <span v-if="stale" class="pill warn">stale</span>
  </p>
</template>

<script>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { formatAgo } from '../statusRefresh.js'

export default {
  name: 'RefreshNote',
  props: {
    refreshedAt: { type: Number, default: 0 },
    stale: { type: Boolean, default: false }
  },
  setup(props) {
    const now = ref(Date.now())
    let tick = null
    onMounted(() => {
      tick = setInterval(() => {
        now.value = Date.now()
      }, 1000)
    })
    onUnmounted(() => {
      if (tick) {
        clearInterval(tick)
      }
    })
    return {
      ago: computed(() => formatAgo(props.refreshedAt, now.value))
    }
  }
}
</script>

<style scoped>
.refresh {
  margin: 0.2rem 0 0;
  font-size: 0.85rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
</style>
