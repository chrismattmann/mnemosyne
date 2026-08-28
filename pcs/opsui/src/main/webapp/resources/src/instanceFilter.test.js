import { test } from 'node:test'
import assert from 'node:assert/strict'
import { instanceMatches, workflowFilterOptions } from './instanceFilter.js'

const split = {
  workflowName: 'SplitWorkflow',
  workflowId: 'urn:bigtranslate:SplitWorkflow',
  startDateTime: '2026-08-27T20:28:56.401-07:00'
}
const translate = {
  workflowName: 'BigTranslateWorkflow',
  workflowId: 'urn:bigtranslate:BigTranslateWorkflow',
  startDateTime: '2026-08-27T20:28:57.283-07:00'
}

test('workflow and started-after filters combine', () => {
  assert.equal(instanceMatches(split, '', ''), true)
  assert.equal(instanceMatches(split, 'SplitWorkflow', ''), true)
  assert.equal(instanceMatches(split, 'BigTranslateWorkflow', ''), false)
  assert.equal(instanceMatches(translate, '', '2026-08-27'), true)
  assert.equal(instanceMatches(translate, '', '2026-08-28'), false)
})

test('workflow options union instances and definitions', () => {
  const opts = workflowFilterOptions([split], [
    { name: 'BigTranslateWorkflow', id: 'urn:bigtranslate:BigTranslateWorkflow' }
  ])
  assert.deepEqual(opts.map((o) => o.name), ['BigTranslateWorkflow', 'SplitWorkflow'])
})
