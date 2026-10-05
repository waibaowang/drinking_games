export interface Point { x: number; y: number }

export const CELL_SIZE = 56
export const colors = ['#ef5350', '#f5c542', '#42a5f5', '#55bd77']
export const colorNames = ['红色', '黄色', '蓝色', '绿色']

const quarter = [
  [1, -7], [2, -7], [3, -7], [3, -6], [3, -5], [3, -4],
  [4, -3], [5, -3], [6, -3], [7, -3], [7, -2], [7, -1], [7, 0],
]

function rotate(x: number, y: number, seat: number): Point {
  if (seat === 1) return { x: -y, y: x }
  if (seat === 2) return { x: -x, y: -y }
  if (seat === 3) return { x: y, y: -x }
  return { x, y }
}

function boardPoint(x: number, y: number, seat = 0): Point {
  const point = rotate(x, y, seat)
  return { x: 500 + point.x, y: 500 + point.y }
}

export const track: Point[] = Array.from({ length: 4 }, (_, seat) =>
  quarter.map(([x, y]) => boardPoint(x * CELL_SIZE, y * CELL_SIZE, seat)),
).flat()

export const hangarCenters: Point[] = Array.from({ length: 4 }, (_, seat) =>
  boardPoint(320, -320, seat),
)

export function cellColor(index: number): string {
  return colors[index % 4]
}

export function homePoint(seat: number, step: number): Point {
  return boardPoint(0, -(6 - step) * CELL_SIZE, seat)
}

export function hangarPoint(seat: number, piece: number): Point {
  const center = hangarCenters[seat]
  return { x: center.x + (piece % 2 ? 42 : -42), y: center.y + (piece < 2 ? -42 : 42) }
}

export function globalCell(seat: number, progress: number): number {
  if (progress < 0 || progress >= track.length) return -1
  return (seat * 13 + progress) % track.length
}

export function piecePoint(seat: number, piece: number, progress: number): Point {
  if (progress < 0) return hangarPoint(seat, piece)
  if (progress >= 52) return homePoint(seat, progress - 52)
  return track[globalCell(seat, progress)]
}
