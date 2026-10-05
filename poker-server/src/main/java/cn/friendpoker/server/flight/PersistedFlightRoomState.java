package cn.friendpoker.server.flight;

import cn.friendpoker.domain.flight.FlightPersistenceState;
import cn.friendpoker.server.bot.BotSeat;
import cn.friendpoker.server.bot.BotSettings;

import java.util.List;

public record PersistedFlightRoomState(FlightPersistenceState game, List<String> recentActionIds,
                                      List<BotSeat> bots, BotSettings botSettings) {
    public PersistedFlightRoomState(FlightPersistenceState game, List<String> ids) {
        this(game, ids, List.of(), BotSettings.DEFAULT);
    }
    public PersistedFlightRoomState {
        recentActionIds = List.copyOf(recentActionIds);
        bots = bots == null ? List.of() : List.copyOf(bots);
        botSettings = botSettings == null ? BotSettings.DEFAULT : botSettings;
    }
}
