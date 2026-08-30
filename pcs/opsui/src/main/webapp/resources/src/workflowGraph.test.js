import { test } from 'node:test'
import assert from 'node:assert/strict'
import { instanceTerminal, taskBubbleState } from './workflowGraph.js'

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

test('a one-task BigTranslate FINISHED is done, not current', () => {
  const only = [{ id: 'urn:bigtranslate:BigTranslate_Task', name: 'BigTranslate_Task' }]
  const state = taskBubbleState(only[0], only, 'urn:bigtranslate:BigTranslate_Task', 'FINISHED')
  assert.deepEqual(state, { current: false, done: true, failed: false })
})
