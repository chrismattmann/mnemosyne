import { test } from 'node:test'
import assert from 'node:assert/strict'
import { shouldResetTypeVisit, typeFromParts, typeHash } from './typeVisit.js'

test('type hash is the type name with no page', () => {
  assert.equal(typeHash('EmploymentJobAggregatesTsvSplit'), 'catalog/EmploymentJobAggregatesTsvSplit')
})

test('a leftover page segment in the hash is ignored', () => {
  const parsed = typeFromParts(['catalog', 'EmploymentJobAggregatesTsvSplit', '3'])
  assert.deepEqual(parsed, { view: 'type', name: 'EmploymentJobAggregatesTsvSplit' })
})

test('a new visit to a type resets paging; staying on it does not', () => {
  assert.equal(shouldResetTypeVisit({ view: 'catalog' }, { view: 'type', name: 'Split' }), true)
  assert.equal(shouldResetTypeVisit({ view: 'type', name: 'Split' }, { view: 'product', id: 'a' }), true)
  assert.equal(shouldResetTypeVisit({ view: 'type', name: 'Split' }, { view: 'type', name: 'Tsv' }), true)
  assert.equal(shouldResetTypeVisit({ view: 'type', name: 'Split' }, { view: 'type', name: 'Split' }), false)
})
