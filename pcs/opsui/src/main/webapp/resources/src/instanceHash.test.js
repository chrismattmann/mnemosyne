import { test } from 'node:test'
import assert from 'node:assert/strict'
import { instancesQuery, splitHash } from './instanceHash.js'

test('splitHash keeps path and instance filters', () => {
  const parsed = splitHash('#/instances/ALL?workflow=SplitWorkflow&since=2026-08-27')
  assert.equal(parsed.path, 'instances/ALL')
  assert.equal(parsed.query.workflow, 'SplitWorkflow')
  assert.equal(parsed.query.since, '2026-08-27')
})

test('instancesQuery omits empty filters', () => {
  assert.equal(instancesQuery('', ''), '')
  assert.equal(instancesQuery('SplitWorkflow', ''), '?workflow=SplitWorkflow')
  assert.equal(instancesQuery('SplitWorkflow', '2026-08-27'), '?workflow=SplitWorkflow&since=2026-08-27')
})
