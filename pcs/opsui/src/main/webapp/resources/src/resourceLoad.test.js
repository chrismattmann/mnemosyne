import { test } from 'node:test'
import assert from 'node:assert/strict'
import { loadResourceOverview } from './resourceLoad.js'

test('resource data renders without waiting for the health report', async () => {
  let finishHealth
  const healthRequest = new Promise(resolve => { finishHealth = resolve })
  let shownResource = null
  let shownHealth = null

  await loadResourceOverview({
    getResources: async () => ({ resource: { url: 'http://localhost:9002' } }),
    getHealth: () => healthRequest,
    current: () => true,
    resource: value => { shownResource = value },
    health: value => { shownHealth = value }
  })

  assert.equal(shownResource.resource.url, 'http://localhost:9002')
  assert.equal(shownHealth, null)

  finishHealth({ report: { daemonStatus: {} } })
  await healthRequest
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(shownHealth, { report: { daemonStatus: {} } })
})

test('a failed health refresh does not fail a successful resource load', async () => {
  let shownResource = null
  await loadResourceOverview({
    getResources: async () => ({ resource: { alive: true } }),
    getHealth: async () => { throw new Error('busy') },
    current: () => true,
    resource: value => { shownResource = value },
    health: () => { throw new Error('should not be called') }
  })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(shownResource.resource.alive, true)
})
