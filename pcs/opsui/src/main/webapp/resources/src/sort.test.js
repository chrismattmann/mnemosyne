import { test } from 'node:test'
import assert from 'node:assert/strict'
import { wallClockMs } from './sort.js'

test('running instance with no end uses now', () => {
  const start = Date.parse('2026-08-30T12:00:00Z')
  const now = Date.parse('2026-08-30T12:01:30Z')
  assert.equal(wallClockMs('2026-08-30T12:00:00Z', '', now, 'PGE EXEC'), 90000)
})

test('finished instance with an end stamp freezes at that end', () => {
  const now = Date.parse('2026-08-30T13:00:00Z')
  assert.equal(
    wallClockMs('2026-08-30T12:00:00Z', '2026-08-30T12:02:00Z', now, 'FINISHED'),
    120000
  )
})

test('finished instance with no end stamp does not keep counting', () => {
  const now = Date.parse('2026-08-30T13:00:00Z')
  assert.equal(wallClockMs('2026-08-30T12:00:00Z', '', now, 'FINISHED'), null)
  assert.equal(wallClockMs('2026-08-30T12:00:00Z', null, now, 'FAILURE'), null)
  assert.equal(wallClockMs('2026-08-30T12:00:00Z', '', now, 'STOPPED'), null)
})

test('QUEUED past the grace window is abandoned and does not keep counting', () => {
  const start = '2026-08-30T11:28:58.488-07:00'
  const now = Date.parse('2026-08-30T14:22:00-07:00')
  assert.equal(wallClockMs(start, '', now, 'QUEUED'), null)
})

test('a freshly QUEUED instance still counts during the grace window', () => {
  const start = Date.parse('2026-08-30T12:00:00Z')
  const now = start + 30 * 1000
  assert.equal(wallClockMs('2026-08-30T12:00:00Z', '', now, 'QUEUED'), 30000)
})
