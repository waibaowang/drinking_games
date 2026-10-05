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

test('room revision orders bot metadata even when the game revision is unchanged', () => {
  const current = { status: 'ACTIVE', stateVersion: 12, gameVersion: 9 }
  const delayed = { status: 'ACTIVE', stateVersion: 11, gameVersion: 9 }
  const updated = { status: 'ACTIVE', stateVersion: 13, gameVersion: 9 }
  assert.equal(shouldApplyRoomUpdate(current.status, current.stateVersion, delayed.status, delayed.stateVersion), false)
  assert.equal(shouldApplyRoomUpdate(current.status, current.stateVersion, updated.status, updated.stateVersion), true)
})
