import assert from 'node:assert/strict'
import test from 'node:test'
import {
  CELL_SIZE, cellColor, colors, globalCell, hangarCenters, hangarPoint,
  homePoint, piecePoint, track,
} from '../src/flight-board.ts'

const key = point => `${point.x},${point.y}`
const rotate = point => ({ x: 1000 - point.y, y: point.x })

test('public route has 52 unique cells in four clockwise quarters', () => {
  assert.equal(track.length, 52)
  assert.equal(new Set(track.map(key)).size, 52)
  for (let index = 0; index < track.length; index++) {
    assert.deepEqual(track[(index + 13) % 52], rotate(track[index]))
    const next = track[(index + 1) % 52]
    const distance = Math.hypot(next.x - track[index].x, next.y - track[index].y)
    assert.ok(distance === CELL_SIZE || Math.abs(distance - CELL_SIZE * Math.SQRT2) < 1e-10)
  }
})

test('seat starts, colors, airports and final lanes share the same orientation', () => {
  assert.deepEqual(track[0], { x: 556, y: 108 })
  assert.deepEqual(hangarCenters, [
    { x: 820, y: 180 }, { x: 820, y: 820 }, { x: 180, y: 820 }, { x: 180, y: 180 },
  ])
  for (let seat = 0; seat < 4; seat++) {
    assert.equal(globalCell(seat, 0), seat * 13)
    assert.equal(cellColor(seat * 13), colors[seat])
    assert.deepEqual(homePoint((seat + 1) % 4, 0), rotate(homePoint(seat, 0)))
    for (let progress = 4; progress <= 47; progress += 4) {
      assert.equal(cellColor(globalCell(seat, progress)), colors[seat])
    }
  }
})

test('airports have sixteen unique parking points', () => {
  const parking = Array.from({ length: 4 }, (_, seat) =>
    Array.from({ length: 4 }, (_, piece) => hangarPoint(seat, piece)),
  ).flat()
  assert.equal(new Set(parking.map(key)).size, 16)
})

test('home lanes have six unique evenly spaced cells ending toward the center', () => {
  const publicCells = new Set(track.map(key))
  const lanes = Array.from({ length: 4 }, (_, seat) =>
    Array.from({ length: 6 }, (_, step) => homePoint(seat, step)),
  )
  assert.equal(new Set(lanes.flat().map(key)).size, 24)
  for (const lane of lanes) {
    for (let step = 0; step < lane.length; step++) {
      assert.ok(!publicCells.has(key(lane[step])))
      assert.equal(Math.hypot(lane[step].x - 500, lane[step].y - 500), (6 - step) * CELL_SIZE)
      if (step) assert.equal(Math.hypot(lane[step].x - lane[step - 1].x, lane[step].y - lane[step - 1].y), CELL_SIZE)
    }
  }
})

test('every legal saved piece position maps within the board without artificial offsets', () => {
  for (let seat = 0; seat < 4; seat++) {
    for (let piece = 0; piece < 4; piece++) {
      for (let progress = -1; progress <= 57; progress++) {
        const point = piecePoint(seat, piece, progress)
        assert.ok(Number.isFinite(point.x) && Number.isFinite(point.y))
        assert.ok(point.x > 0 && point.x < 1000 && point.y > 0 && point.y < 1000)
        if (progress === -1) assert.deepEqual(point, hangarPoint(seat, piece))
        else if (progress >= 52) assert.deepEqual(point, homePoint(seat, progress - 52))
        else assert.deepEqual(point, track[globalCell(seat, progress)])
      }
    }
    assert.equal(globalCell(seat, -1), -1)
    assert.equal(globalCell(seat, 52), -1)
    assert.equal(globalCell(seat, 57), -1)
  }
})

test('a board item and each seat relative piece position agree on all public cells', () => {
  for (let cellIndex = 0; cellIndex < 52; cellIndex++) {
    for (let seat = 0; seat < 4; seat++) {
      const progress = (cellIndex - seat * 13 + 52) % 52
      assert.equal(globalCell(seat, progress), cellIndex)
      assert.deepEqual(piecePoint(seat, 0, progress), track[cellIndex])
    }
  }
})
