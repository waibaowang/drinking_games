export type ActionType = 'FOLD' | 'CHECK' | 'CALL' | 'BET' | 'RAISE' | 'ALL_IN'
export type Street = 'WAITING' | 'PREFLOP' | 'FLOP' | 'TURN' | 'RIVER' | 'COMPLETE'

export interface UserView { username: string; displayName: string }
export interface Card { rank: string; suit: string }
export interface PlayerSnapshot {
  id: string
  name: string
  seat: number
  stack: number
  committedStreet: number
  committedHand: number
  status: 'ACTIVE' | 'FOLDED' | 'ALL_IN' | 'SITTING_OUT'
  cards: Card[]
  dealer: boolean
  smallBlind: boolean
  bigBlind: boolean
}
export interface TableSnapshot {
  version: number
  handNumber: number
  street: Street
  pot: number
  currentBet: number
  minimumRaise: number
  actorSeat: number | null
  board: Card[]
  players: PlayerSnapshot[]
  legalActions: ActionType[]
  messages: string[]
  shuffleCommitment: string | null
  shuffleSeedReveal: string | null
}
export interface TableConfig {
  smallBlind: number
  bigBlind: number
  initialStack: number
  maxPlayers: number
  actionSeconds: number
}
export interface RoomState {
  id: string
  code: string
  name: string
  hostUsername: string
  config: TableConfig
  table: TableSnapshot
  actionDeadline: string | null
}
export interface RoomSummary {
  id: string
  code: string
  name: string
  locked: boolean
  members: number
  maxPlayers: number
  smallBlind: number
  bigBlind: number
}
