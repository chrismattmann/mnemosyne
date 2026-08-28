import { test } from 'node:test'
import assert from 'node:assert/strict'
import { onDemandLabel, onDemandPill } from './onDemandStatus.js'

test('DOWN crawlers and batch stubs read as not running, not failed', () => {
  assert.equal(onDemandLabel('DOWN'), 'not running')
  assert.equal(onDemandPill('DOWN'), 'neutral')
  assert.equal(onDemandLabel('UP'), 'UP')
  assert.equal(onDemandPill('UP'), 'up')
})
