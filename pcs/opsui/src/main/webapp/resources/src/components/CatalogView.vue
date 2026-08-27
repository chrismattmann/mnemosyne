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
    <h2>File catalog</h2>
    <p class="muted">Product types in File Manager.</p>
    <p v-if="loading && !types.length" class="empty">Loading types…</p>
    <p v-else-if="!types.length" class="empty">No product types yet.</p>
    <table v-else>
      <thead>
        <tr>
          <th>Type</th>
          <th>Description</th>
          <th>Products</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="type in types" :key="type.id || type.name">
          <td>
            <a href="#" @click.prevent="$emit('open', type.name)">{{ type.name }}</a>
          </td>
          <td>{{ type.description }}</td>
          <td>{{ type.numProducts }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<script>
export default {
  name: 'CatalogView',
  props: {
    types: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  emits: ['open']
}
</script>

<style scoped>
h2 {
  margin: 1.4rem 0 0.3rem;
}
</style>
