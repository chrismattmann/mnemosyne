import { test } from 'node:test'
import assert from 'node:assert/strict'
import { instanceAbandoned, instanceLive, instanceTerminal, taskBubbleState } from './workflowGraph.js'

const tasks = [
  { id: 'urn:opsui:SplitTsv', name: 'Split TSV' },
  { id: 'urn:opsui:TranslateJobs', name: 'Translate' }
]

test('FINISHED marks every task done and none current', () => {
  const split = taskBubbleState(tasks[0], tasks, 'urn:opsui:TranslateJobs', 'FINISHED')
  const translate = taskBubbleState(tasks[1], tasks, 'urn:opsui:TranslateJobs', 'FINISHED')
  assert.deepEqual(split, { current: false, done: true, failed: false })
  assert.deepEqual(translate, { current: false, done: true, failed: false })
})

test('an in-flight Translate start is current, Split is done', () => {
  const split = taskBubbleState(tasks[0], tasks, 'urn:opsui:TranslateJobs', 'PGE EXEC')
  const translate = taskBubbleState(tasks[1], tasks, 'urn:opsui:TranslateJobs', 'PGE EXEC')
  assert.deepEqual(split, { current: false, done: true, failed: false })
  assert.deepEqual(translate, { current: true, done: false, failed: false })
})

test('FINISHED and FAILURE are terminal so the wall clock can freeze', () => {
  assert.equal(instanceTerminal('FINISHED'), true)
  assert.equal(instanceTerminal('FAILURE'), true)
  assert.equal(instanceTerminal('PGE EXEC'), false)
  assert.equal(instanceTerminal('CRAWLING'), false)
})

test('PGE EXEC is live; QUEUED is waiting and becomes abandoned after 2 minutes', () => {
  assert.equal(instanceLive('PGE EXEC'), true)
  assert.equal(instanceLive('QUEUED'), false)
  const start = '2026-08-30T11:28:58.488-07:00'
  const now = Date.parse('2026-08-30T14:22:00-07:00')
  assert.equal(instanceAbandoned('QUEUED', start, '', now), true)
  assert.equal(instanceAbandoned('PGE EXEC', start, '', now), false)
  assert.equal(instanceAbandoned('QUEUED', start, '2026-08-30T11:29:00-07:00', now), false)
})

test('a one-task BigTranslate FINISHED is done, not current', () => {
  const only = [{ id: 'urn:bigtranslate:BigTranslate_Task', name: 'BigTranslate_Task' }]
  const state = taskBubbleState(only[0], only, 'urn:bigtranslate:BigTranslate_Task', 'FINISHED')
  assert.deepEqual(state, { current: false, done: true, failed: false })
})
