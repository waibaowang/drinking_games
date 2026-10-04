package cn.friendpoker.server.flight;

import cn.friendpoker.domain.flight.FlightSnapshot;
import cn.friendpoker.server.room.GameType;
import cn.friendpoker.server.room.RoomStatus;

import java.util.UUID;

public record FlightRoomState(
        UUID id,
        GameType gameType,
        String code,
        String name,
        String hostUsername,
        FlightSnapshot game,
        RoomStatus status,
        boolean recovered
) {
}
