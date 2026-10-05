import type { RoomState } from './types'

export function botControlState(room: RoomState, username: string, connected: boolean, busy: boolean) {
  const bots = room.bots ?? []
  const players = room.gameType === 'FLIGHT' ? room.game.players : room.table.players
  const maxPlayers = room.gameType === 'FLIGHT' ? 4 : room.config.maxPlayers
  const phase = room.gameType === 'FLIGHT' ? room.game.phase : room.table.street
  const betweenGames = phase === 'WAITING' || phase === 'COMPLETE'
  const hasHuman = players.some(player => !bots.some(bot => bot.id === player.id))
  const host = room.hostUsername === username
  const canOperate = host && connected && room.status === 'ACTIVE' && !busy
  return {
    bots,
    settings: room.botSettings ?? { enabled: true, speed: 'NORMAL' as const },
    host,
    betweenGames,
    hasHuman,
    emptySeats: Array.from({ length: maxPlayers }, (_, seat) => seat).filter(seat => !players.some(player => player.seat === seat)),
    canEdit: canOperate && betweenGames,
    canRun: canOperate && bots.length > 0,
    depletedBotIds: room.gameType === 'POKER' && betweenGames
      ? room.table.players.filter(player => player.stack === 0 && bots.some(bot => bot.id === player.id)).map(player => player.id)
      : [],
  }
}
