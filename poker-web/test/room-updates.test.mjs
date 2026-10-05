import assert from 'node:assert/strict'
import test from 'node:test'
import { shouldApplyRoomUpdate } from '../src/room-updates.ts'

test('closed rooms cannot be reopened by delayed active responses', () => {
  for (const incomingVersion of [8, 9, 10]) {
    assert.equal(shouldApplyRoomUpdate('CLOSED', 9, 'ACTIVE', incomingVersion), false)
  }
  assert.equal(shouldApplyRoomUpdate('CLOSED', 9, 'CLOSED', 9), true)
})

test('active updates reject old versions but allow current metadata and closure', () => {
  assert.equal(shouldApplyRoomUpdate('ACTIVE', 9, 'ACTIVE', 8), false)
  assert.equal(shouldApplyRoomUpdate('ACTIVE', 9, 'ACTIVE', 9), true)
  assert.equal(shouldApplyRoomUpdate('ACTIVE', 9, 'ACTIVE', 10), true)
  assert.equal(shouldApplyRoomUpdate('ACTIVE', 9, 'CLOSED', 8), true)
})
