import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mergeTypeCatalog } from './catalogPages.js'

test('page 1 replaces, later pages append without dupes', () => {
  const first = mergeTypeCatalog(null, {
    catalog: {
      type: { name: 'TsvSplit' },
      page: 1,
      totalPages: 2,
      products: [{ id: 'a', name: 'one' }]
    }
  })
  const second = mergeTypeCatalog(first, {
    catalog: {
      type: { name: 'TsvSplit' },
      page: 2,
      totalPages: 2,
      products: [{ id: 'a', name: 'one' }, { id: 'b', name: 'two' }]
    }
  })
  assert.deepEqual(second.catalog.products.map((p) => p.id), ['a', 'b'])
  assert.equal(second.catalog.page, 2)
})

test('a different type starts a new list', () => {
  const prev = {
    catalog: { type: { name: 'Tsv' }, page: 2, products: [{ id: 'x' }] }
  }
  const next = mergeTypeCatalog(prev, {
    catalog: { type: { name: 'TsvSplit' }, page: 1, products: [{ id: 'y' }] }
  })
  assert.deepEqual(next.catalog.products.map((p) => p.id), ['y'])
})
