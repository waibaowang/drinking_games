package cn.friendpoker.server.flight;

import cn.friendpoker.domain.flight.FlightSnapshot;
import cn.friendpoker.server.room.GameType;
import cn.friendpoker.server.room.RoomStatus;
import cn.friendpoker.server.bot.BotSeat;
import cn.friendpoker.server.bot.BotSettings;
import java.util.List;

import java.util.UUID;

public record FlightRoomState(
        UUID id,
        GameType gameType,
        String code,
        String name,
        String hostUsername,
        FlightSnapshot game,
        RoomStatus status,
        boolean recovered,
        List<BotSeat> bots,
        BotSettings botSettings,
        long stateVersion
) {
}
