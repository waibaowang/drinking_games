export type ActionType = 'FOLD' | 'CHECK' | 'CALL' | 'BET' | 'RAISE' | 'ALL_IN'
export type Street = 'WAITING' | 'SHUFFLE_PENDING' | 'PREFLOP' | 'FLOP' | 'TURN' | 'RIVER' | 'COMPLETE'
export type UserRole = 'ADMIN' | 'PLAYER'
export type RoomStatus = 'ACTIVE' | 'CLOSED'
export type GameType = 'POKER' | 'FLIGHT'
export type BotSpeed = 'NORMAL' | 'FAST'
export interface BotSeat { id: string; name: string; seat: number }
export interface BotSettings { enabled: boolean; speed: BotSpeed }

export interface UserView { username: string; displayName: string; role: UserRole; enabled: boolean }
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
export interface PokerRoomState {
  id: string
  gameType: 'POKER'
  code: string
  name: string
  hostUsername: string
  config: TableConfig
  table: TableSnapshot
  actionDeadline: string | null
  status: RoomStatus
  recovered: boolean
  stateVersion?: number
  bots: BotSeat[]
  botSettings: BotSettings
}
export type FlightPhase = 'WAITING' | 'ROLL' | 'MOVE' | 'COMPLETE'
export interface FlightBoardItem { id: number; type: string; cellIndex: number; spawnRound: number; expiresAtRound: number }
export interface FlightPlayerSnapshot { id: string; name: string; seat: number; pieces: number[]; turn: boolean }
export interface FlightSnapshot {
  version: number
  gameNumber: number
  roundNumber: number
  phase: FlightPhase
  currentSeat: number | null
  lastRoll: number | null
  legalPieceIndexes: number[]
  players: FlightPlayerSnapshot[]
  boardItems: FlightBoardItem[]
  messages: string[]
  winnerId: string | null
}
export interface FlightRoomState {
  id: string
  gameType: 'FLIGHT'
  code: string
  name: string
  hostUsername: string
  game: FlightSnapshot
  status: RoomStatus
  recovered: boolean
  stateVersion?: number
  bots: BotSeat[]
  botSettings: BotSettings
}
export type RoomState = PokerRoomState | FlightRoomState
export interface RoomSummary {
  id: string
  gameType: GameType
  code: string
  name: string
  locked: boolean
  members: number
  maxPlayers: number
  smallBlind: number
  bigBlind: number
  status: RoomStatus
  recovered: boolean
}
export interface HistoryItem {
  id: string
  roomId: string
  roomName: string
  handNumber: number
  summary: string
  completedAt: string
  gameType: GameType
}
export interface HistoryPage {
  items: HistoryItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
