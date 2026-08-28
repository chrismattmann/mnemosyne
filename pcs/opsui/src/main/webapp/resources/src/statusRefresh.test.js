import { test } from 'node:test'
import assert from 'node:assert/strict'
import { formatAgo } from './statusRefresh.js'

test('formatAgo buckets seconds and minutes', () => {
  const now = 1_000_000
  assert.equal(formatAgo(now - 2000, now), 'just now')
  assert.equal(formatAgo(now - 8000, now), '8s ago')
  assert.equal(formatAgo(now - 120000, now), '2m ago')
  assert.equal(formatAgo(0, now), '')
})
