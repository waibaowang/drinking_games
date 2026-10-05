import type { FlightRoomState, RoomState } from './types'

export function botControlState(room: RoomState, username: string, connected: boolean, busy: boolean) {
  const bots = room.bots ?? []
  const players = room.gameType === 'FLIGHT' ? room.game.players : room.table.players
  const maxPlayers = room.gameType === 'FLIGHT' ? 4 : room.config.maxPlayers
  const phase = room.gameType === 'FLIGHT' ? room.game.phase : room.table.street
  const betweenGames = phase === 'WAITING' || phase === 'COMPLETE'
  const hasHuman = players.some(player => !bots.some(bot => bot.id === player.id))
  const fundedPlayers = room.gameType === 'POKER' ? room.table.players.filter(player => player.stack > 0) : players
  const hasFundedHuman = fundedPlayers.some(player => !bots.some(bot => bot.id === player.id))
  const host = room.hostUsername === username
  const canOperate = host && connected && room.status === 'ACTIVE' && !busy
  const emptySeats = Array.from({ length: maxPlayers }, (_, seat) => seat).filter(seat => !players.some(player => player.seat === seat))
  return {
    bots,
    settings: room.botSettings ?? { enabled: true, speed: 'NORMAL' as const },
    host,
    betweenGames,
    hasHuman,
    hasFundedHuman,
    canStart: host && room.status === 'ACTIVE' && betweenGames && fundedPlayers.length >= 2 && (bots.length === 0 || hasFundedHuman),
    selfOutOfChips: betweenGames && room.gameType === 'POKER' && room.table.players.some(player => player.id === username && player.stack === 0),
    emptySeats,
    canAdd: canOperate && betweenGames && hasFundedHuman && emptySeats.length > 0,
    canEdit: canOperate && betweenGames,
    canRun: canOperate && bots.length > 0,
    depletedBotIds: room.gameType === 'POKER' && betweenGames
      ? room.table.players.filter(player => player.stack === 0 && bots.some(bot => bot.id === player.id)).map(player => player.id)
      : [],
  }
}

export function flightBotTurnMessage(room: FlightRoomState): string | null {
  if (room.status !== 'ACTIVE' || !['ROLL', 'MOVE'].includes(room.game.phase)) return null
  const actor = room.game.players.find(player => player.seat === room.game.currentSeat)
  if (!actor || !(room.bots ?? []).some(bot => bot.id === actor.id)) return null
  const result = room.game.phase === 'MOVE' ? `，掷出了 ${room.game.lastRoll}` : ''
  if (room.botSettings?.enabled === false) return `${actor.name} 已暂停${result}`
  return room.game.phase === 'MOVE' ? `${actor.name} 掷出了 ${room.game.lastRoll}，正在移动` : `${actor.name} 准备掷骰子`
}
