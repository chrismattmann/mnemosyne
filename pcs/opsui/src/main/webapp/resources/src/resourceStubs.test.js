import { test } from 'node:test'
import assert from 'node:assert/strict'
import { stubForNode } from './resourceStubs.js'

test('an RM node at the batch-stub port joins the stub health row', () => {
  const stub = stubForNode(
    { id: 'localhost', url: 'http://localhost:2001' },
    [{ daemon: 'batch stub', url: 'http://localhost:2001/', status: 'DOWN' }]
  )
  assert.equal(stub.daemon, 'batch stub')
  assert.equal(stub.status, 'DOWN')
})

test('a node that is not a stub is left alone', () => {
  assert.equal(
    stubForNode({ url: 'http://worker:9000' }, [{ url: 'http://localhost:2001', status: 'DOWN' }]),
    null
  )
})
