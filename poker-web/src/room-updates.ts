import type { RoomStatus } from './types'

export function shouldApplyRoomUpdate(status: RoomStatus, version: number, incomingStatus: RoomStatus, incomingVersion: number): boolean {
  return incomingStatus === 'CLOSED' || (status !== 'CLOSED' && incomingVersion >= version)
}
