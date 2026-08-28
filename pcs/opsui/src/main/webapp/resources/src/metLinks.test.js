import { test } from 'node:test'
import assert from 'node:assert/strict'
import { classifyMetKey } from './metLinks.js'

test('InputFiles, Filename, SplitFilename and TsvFile open as products', () => {
  assert.equal(classifyMetKey('InputFiles'), 'product')
  assert.equal(classifyMetKey('Filename'), 'product')
  assert.equal(classifyMetKey('SplitFilename'), 'product')
  assert.equal(classifyMetKey('TsvFile'), 'product')
  assert.equal(classifyMetKey('SourceTsv'), 'product')
})

test('workflow and instance keys still classify', () => {
  assert.equal(classifyMetKey('WorkflowInstId'), 'instance')
  assert.equal(classifyMetKey('WorkflowId'), 'workflow')
  assert.equal(classifyMetKey('TaskId'), 'task')
  assert.equal(classifyMetKey('ProductType'), 'type')
})
