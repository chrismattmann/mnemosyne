import { test } from 'node:test'
import assert from 'node:assert/strict'
import { progressLabel, progressPct } from './pgeProgress.js'

test('label is message plus done/total', () => {
  assert.equal(progressLabel({ done: 50, total: 612, message: 'encoded' }), 'encoded 50 / 612')
  assert.equal(progressLabel({ done: 3, total: 10 }), '3 / 10')
  assert.equal(progressLabel(null), '')
})

test('percent is clamped to 0-100', () => {
  assert.equal(progressPct({ done: 50, total: 100 }), 50)
  assert.equal(progressPct({ done: 0, total: 10 }), 0)
  assert.equal(progressPct({ done: 12, total: 10 }), 100)
  assert.equal(progressPct({ done: 5, total: 0 }), 0)
  assert.equal(progressPct(null), 0)
})
