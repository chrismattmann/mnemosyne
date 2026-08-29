import { test } from 'node:test'
import assert from 'node:assert/strict'
import { loadTypePages, mergeTypeCatalog, typeHasMore } from './catalogPages.js'

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

test('typeHasMore is true when another page exists or the count is short', () => {
  assert.equal(typeHasMore({ page: 1, totalPages: 3, numProducts: 46 }, 20), true)
  assert.equal(typeHasMore({ page: 3, totalPages: 3, numProducts: 46 }, 45), true)
  assert.equal(typeHasMore({ page: 3, totalPages: 3, numProducts: 46 }, 46), false)
})

test('a refresh reloads only the pages already shown', async () => {
  const pages = {
    1: {
      catalog: {
        type: { name: 'TsvSplit' },
        page: 1,
        totalPages: 2,
        numProducts: 3,
        products: [{ id: 'a' }, { id: 'b' }]
      }
    },
    2: {
      catalog: {
        type: { name: 'TsvSplit' },
        page: 2,
        totalPages: 2,
        numProducts: 3,
        products: [{ id: 'c' }]
      }
    }
  }
  const fetched = []
  const previous = mergeTypeCatalog(null, pages[1])
  const next = await loadTypePages({
    name: 'TsvSplit',
    previous,
    through: 1,
    refresh: true,
    getPage: async (name, page) => {
      fetched.push([name, page])
      return pages[page]
    }
  })
  assert.deepEqual(fetched, [['TsvSplit', 1]])
  assert.deepEqual(next.catalog.products.map((p) => p.id), ['a', 'b'])
  assert.equal(next.catalog.numProducts, 3)
  assert.equal(typeHasMore(next.catalog, next.catalog.products.length), true)
})

test('a refresh through page 2 rebuilds those pages from the start', async () => {
  const fetched = []
  const next = await loadTypePages({
    name: 'TsvSplit',
    through: 2,
    refresh: true,
    getPage: async (name, page) => {
      fetched.push(page)
      return {
        catalog: {
          type: { name: 'TsvSplit' },
          page,
          totalPages: 3,
          numProducts: 5,
          products: [{ id: String(page) }]
        }
      }
    }
  })
  assert.deepEqual(fetched, [1, 2])
  assert.deepEqual(next.catalog.products.map((p) => p.id), ['1', '2'])
})

test('without refresh, later pages append onto what is already shown', async () => {
  const fetched = []
  const previous = mergeTypeCatalog(null, {
    catalog: {
      type: { name: 'TsvSplit' },
      page: 1,
      totalPages: 2,
      products: [{ id: 'a' }]
    }
  })
  const next = await loadTypePages({
    name: 'TsvSplit',
    previous,
    through: 2,
    getPage: async (name, page) => {
      fetched.push(page)
      return {
        catalog: {
          type: { name: 'TsvSplit' },
          page,
          totalPages: 2,
          products: [{ id: 'b' }]
        }
      }
    }
  })
  assert.deepEqual(fetched, [2])
  assert.deepEqual(next.catalog.products.map((p) => p.id), ['a', 'b'])
})
