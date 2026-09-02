import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { POLL_MS, shouldPoll } from './pollViews.js'

test('live-watch pages poll; one-off pages do not', () => {
  assert.equal(shouldPoll('status'), true)
  assert.equal(shouldPoll('catalog'), true)
  assert.equal(shouldPoll('type'), true)
  assert.equal(shouldPoll('instances'), true)
  assert.equal(shouldPoll('instance'), true)
  assert.equal(shouldPoll('resources'), true)
  assert.equal(shouldPoll('workflow'), true)
  assert.equal(shouldPoll('search'), false)
  assert.equal(shouldPoll('product'), false)
  assert.equal(shouldPoll('task'), false)
  assert.equal(POLL_MS, 8000)
})

test('polled instance JSON is asked without the HTTP cache', () => {
  const here = dirname(fileURLToPath(import.meta.url))
  const src = readFileSync(join(here, 'api.js'), 'utf8')
  assert.equal(src.includes("cache: 'no-store'"), true)
  assert.equal(src.includes('liveGet(`${services()}/workflow/instances?${params}`)'), true)
})
