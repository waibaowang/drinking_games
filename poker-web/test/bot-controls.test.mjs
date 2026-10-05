import assert from 'node:assert/strict'
import test from 'node:test'
import { botControlState, flightBotTurnMessage } from '../src/bot-controls.ts'

const bot = { id: 'bot:test', name: '机器人 1', seat: 1 }
const human = { id: 'alice', name: '机器人一样的昵称', seat: 0 }
function flight(players = [human], bots = [], phase = 'WAITING') {
  return { gameType: 'FLIGHT', hostUsername: 'alice', status: 'ACTIVE', bots, botSettings: { enabled: true, speed: 'NORMAL' }, game: { players, phase } }
}
function poker(players, bots, street = 'WAITING') {
  return { gameType: 'POKER', hostUsername: 'alice', status: 'ACTIVE', bots, botSettings: { enabled: true, speed: 'FAST' }, config: { maxPlayers: 6 }, table: { players, street } }
}

test('both games use actual empty seats and server bot metadata, not player nicknames', () => {
  const state = botControlState(flight([human, bot], [bot]), 'alice', true, false)
  assert.deepEqual(state.emptySeats, [2, 3])
  assert.equal(state.hasHuman, true)
  assert.equal(state.canEdit, true)
  assert.equal(state.canRun, true)
  const pokerState = botControlState(poker([human, { ...bot, stack: 100 }], [bot]), 'alice', true, false)
  assert.deepEqual(pokerState.emptySeats, [2, 3, 4, 5])
  assert.equal(pokerState.settings.speed, 'FAST')
})

test('adding requires a human seat, full rooms have no add targets', () => {
  assert.equal(botControlState(flight([], []), 'alice', true, false).hasHuman, false)
  assert.equal(botControlState(flight([bot], [bot]), 'alice', true, false).hasHuman, false)
  assert.deepEqual(botControlState(flight(Array.from({ length: 4 }, (_, seat) => ({ ...human, seat }))), 'alice', true, false).emptySeats, [])
})

test('non-host, disconnected, closed and pending controls cannot mutate rooms', () => {
  for (const [room, username, connected, busy] of [
    [flight([human, bot], [bot]), 'bob', true, false],
    [flight([human, bot], [bot]), 'alice', false, false],
    [{ ...flight([human, bot], [bot]), status: 'CLOSED' }, 'alice', true, false],
    [flight([human, bot], [bot]), 'alice', true, true],
  ]) {
    const state = botControlState(room, username, connected, busy)
    assert.equal(state.canEdit, false)
    assert.equal(state.canRun, false)
  }
})

test('active hands and flights forbid roster editing but allow pause and speed', () => {
  for (const room of [flight([human, bot], [bot], 'ROLL'), flight([human, bot], [bot], 'MOVE'), poker([human, bot], [bot], 'PREFLOP'), poker([human, bot], [bot], 'SHUFFLE_PENDING')]) {
    const state = botControlState(room, 'alice', true, false)
    assert.equal(state.canEdit, false)
    assert.equal(state.canRun, true)
  }
  assert.equal(botControlState(flight([human, bot], [bot], 'COMPLETE'), 'alice', true, false).canEdit, true)
})

test('depleted poker bots are reported only between hands, not during all-in', () => {
  const players = [{ ...human, stack: 0 }, { ...bot, stack: 0 }]
  assert.deepEqual(botControlState(poker(players, [bot], 'COMPLETE'), 'alice', true, false).depletedBotIds, [bot.id])
  assert.deepEqual(botControlState(poker(players, [bot], 'RIVER'), 'alice', true, false).depletedBotIds, [])
  assert.deepEqual(botControlState(flight([human, bot], [bot]), 'alice', true, false).depletedBotIds, [])
})

test('legacy state without bot metadata remains a normal human room', () => {
  const room = flight()
  delete room.bots
  delete room.botSettings
  const state = botControlState(room, 'alice', true, false)
  assert.deepEqual(state.bots, [])
  assert.deepEqual(state.settings, { enabled: true, speed: 'NORMAL' })
  assert.equal(state.hasHuman, true)
  assert.equal(state.canRun, false)
})

test('poker cannot start a bot hand if the only human has no chips', () => {
  const secondBot = { id: 'bot:second', name: '机器人 2', seat: 2 }
  const players = [{ ...human, stack: 0 }, { ...bot, stack: 100 }, { ...secondBot, stack: 100 }]
  const room = poker(players, [bot, secondBot], 'COMPLETE')
  const state = botControlState(room, 'alice', true, false)
  assert.equal(state.canStart, false)
  assert.equal(state.canAdd, false)
  assert.equal(state.canEdit, true)
  assert.equal(state.selfOutOfChips, true)
  assert.equal(state.hasFundedHuman, false)
  players[0].stack = 100
  const funded = botControlState(room, 'alice', true, false)
  assert.equal(funded.canStart, true)
  assert.equal(funded.canAdd, true)
  assert.equal(funded.selfOutOfChips, false)
  assert.equal(botControlState(room, 'bob', true, false).canStart, false)
  assert.equal(botControlState(poker(players, [bot, secondBot], 'RIVER'), 'alice', true, false).canStart, false)
  assert.equal(botControlState({ ...room, status: 'CLOSED' }, 'alice', true, false).canStart, false)
})

test('zero-chip warnings wait until the hand ends and respect other funded humans', () => {
  const players = [{ ...human, stack: 0 }, { ...bot, stack: 100 }, { id: 'bob', name: 'Bob', seat: 2, stack: 100 }]
  const state = botControlState(poker(players, [bot], 'COMPLETE'), 'alice', true, false)
  assert.equal(state.canStart, true)
  assert.equal(state.selfOutOfChips, true)
  assert.equal(state.hasFundedHuman, true)
  assert.equal(botControlState(poker(players, [bot], 'RIVER'), 'alice', true, false).selfOutOfChips, false)
  assert.equal(botControlState(poker([{ ...human, stack: 100 }], []), 'alice', true, false).canStart, false)
})

test('flight bot turn text shows authoritative dice results and pause states', () => {
  const room = flight([human, bot], [bot], 'ROLL')
  room.game.currentSeat = 1
  room.game.lastRoll = null
  assert.equal(flightBotTurnMessage(room), '机器人 1 准备掷骰子')
  room.game.phase = 'MOVE'
  room.game.lastRoll = 6
  assert.equal(flightBotTurnMessage(room), '机器人 1 掷出了 6，正在移动')
  room.botSettings.enabled = false
  assert.equal(flightBotTurnMessage(room), '机器人 1 已暂停，掷出了 6')
  room.game.phase = 'ROLL'
  assert.equal(flightBotTurnMessage(room), '机器人 1 已暂停')
  room.game.currentSeat = 0
  assert.equal(flightBotTurnMessage(room), null)
  room.game.currentSeat = 1
  room.status = 'CLOSED'
  assert.equal(flightBotTurnMessage(room), null)
  room.status = 'ACTIVE'
  room.game.phase = 'COMPLETE'
  assert.equal(flightBotTurnMessage(room), null)
})

test('flight starting still requires a real player and two occupied seats', () => {
  const secondBot = { id: 'bot:second', name: '机器人 2', seat: 2 }
  assert.equal(botControlState(flight([bot, secondBot], [bot, secondBot]), 'alice', true, false).canStart, false)
  assert.equal(botControlState(flight([human]), 'alice', true, false).canStart, false)
  assert.equal(botControlState(flight([human, bot], [bot]), 'alice', true, false).canStart, true)
})

test('adding bots respects funding, vacant seats and between-game phases', () => {
  const room = flight([human, bot], [bot])
  assert.equal(botControlState(room, 'alice', true, false).canAdd, true)
  assert.equal(botControlState(flight([], []), 'alice', true, false).canAdd, false)
  assert.equal(botControlState(flight([bot], [bot]), 'alice', true, false).canAdd, false)
  assert.equal(botControlState(flight([human, bot], [bot], 'MOVE'), 'alice', true, false).canAdd, false)
  assert.equal(botControlState(room, 'bob', true, false).canAdd, false)
  assert.equal(botControlState(room, 'alice', false, false).canAdd, false)
  assert.equal(botControlState(room, 'alice', true, true).canAdd, false)
  assert.equal(botControlState({ ...room, status: 'CLOSED' }, 'alice', true, false).canAdd, false)
  assert.equal(botControlState(flight(Array.from({ length: 4 }, (_, seat) => ({ ...human, seat }))), 'alice', true, false).canAdd, false)
})
