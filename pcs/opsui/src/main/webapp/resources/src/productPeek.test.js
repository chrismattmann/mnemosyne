import { test } from 'node:test'
import assert from 'node:assert/strict'
import { decodePeek, isTextMime, looksBinary, PEEK_BYTES } from './productPeek.js'

test('TSV and JSON are text; a JPEG is not guessed from mime', () => {
  assert.equal(isTextMime('text/tab-separated-values'), true)
  assert.equal(isTextMime('application/json'), true)
  assert.equal(isTextMime('image/jpeg'), false)
})

test('nul bytes mark a peek as binary, UTF-8 TSV does not', () => {
  assert.equal(looksBinary(new Uint8Array([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10])), true)
  const tsv = new TextEncoder().encode('id\tname\n1\talice\n')
  assert.equal(looksBinary(tsv), false)
  const decoded = decodePeek(tsv)
  assert.equal(decoded.binary, false)
  assert.match(decoded.text, /alice/)
})

test('peek length is capped', () => {
  const big = new Uint8Array(PEEK_BYTES + 10)
  big.fill(65)
  const decoded = decodePeek(big)
  assert.equal(decoded.truncated, true)
  assert.equal(decoded.text.length, PEEK_BYTES)
})
