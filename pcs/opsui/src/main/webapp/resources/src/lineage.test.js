import { test } from 'node:test'
import assert from 'node:assert/strict'
import { lineageNodes } from './lineage.js'

test('split upstream is the parent TSV, not the split repeating itself', () => {
  const nodes = lineageNodes(
    { 'jobs.tsv.aaaa': ['jobs.tsv'] },
    'jobs.tsv.aaaa'
  )
  assert.deepEqual(nodes, [{ name: 'jobs.tsv', children: null }])
})

test('TSV downstream is the split, not invented leaf JSON', () => {
  const nodes = lineageNodes(
    { 'jobs.tsv': ['jobs.tsv.aaaa'] },
    'jobs.tsv'
  )
  assert.deepEqual(nodes, [{ name: 'jobs.tsv.aaaa', children: null }])
})

test('a product with only itself in the tree has no relatives', () => {
  assert.deepEqual(lineageNodes('jobs.tsv', 'jobs.tsv'), [])
  assert.deepEqual(lineageNodes({ 'jobs.tsv': [] }, 'jobs.tsv'), [])
})

test('nested cataloged children stay nested', () => {
  const nodes = lineageNodes(
    { root: [{ mid: ['leaf'] }] },
    'other'
  )
  assert.equal(nodes.length, 1)
  assert.equal(nodes[0].name, 'root')
  assert.deepEqual(nodes[0].children, [{ mid: ['leaf'] }])
})
