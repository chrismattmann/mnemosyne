import { test } from 'node:test'
import assert from 'node:assert/strict'
import { partitionTypes } from './catalogGroups.js'

test('populated types stay in the main list, zeros are empty', () => {
  const { populated, empty } = partitionTypes([
    { name: 'EmploymentJobAggregatesTsv', numProducts: 1 },
    { name: 'EmploymentJob', numProducts: 0 },
    { name: 'EmploymentJobTranslated', numProducts: 0 },
    { name: 'EmploymentJobAggregatesTsvSplit', numProducts: 2 }
  ])
  assert.deepEqual(populated.map((t) => t.name), [
    'EmploymentJobAggregatesTsv',
    'EmploymentJobAggregatesTsvSplit'
  ])
  assert.deepEqual(empty.map((t) => t.name), [
    'EmploymentJob',
    'EmploymentJobTranslated'
  ])
})

test('a catalog of only empty types is all empty, not missing', () => {
  const { populated, empty } = partitionTypes([
    { name: 'GenericFile', numProducts: 0 }
  ])
  assert.equal(populated.length, 0)
  assert.equal(empty.length, 1)
})
