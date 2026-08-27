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
  <ul v-if="nodes.length" class="tree">
    <li v-for="(node, i) in nodes" :key="i">
      <span>{{ node.name }}</span>
      <LineageTree v-if="node.rawChildren != null" :value="node.rawChildren"/>
    </li>
  </ul>
</template>

<script>
function asNodes(value) {
  if (value == null) {
    return []
  }
  if (typeof value === 'string') {
    return [{ name: value, rawChildren: null }]
  }
  if (Array.isArray(value)) {
    return value.flatMap(asNodes)
  }
  if (typeof value === 'object') {
    return Object.keys(value).map((name) => ({
      name,
      rawChildren: value[name]
    }))
  }
  return []
}

export default {
  name: 'LineageTree',
  props: {
    value: { default: null }
  },
  computed: {
    nodes() {
      return asNodes(this.value)
    }
  }
}
</script>

<style scoped>
.tree {
  list-style: none;
  margin: 0.2rem 0 0;
  padding-left: 1rem;
  border-left: 1px solid var(--line);
}

li {
  margin: 0.2rem 0;
}
</style>
