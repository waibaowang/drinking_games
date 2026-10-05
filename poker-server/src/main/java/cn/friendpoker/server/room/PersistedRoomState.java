package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.TablePersistenceState;
import cn.friendpoker.server.bot.BotSeat;
import cn.friendpoker.server.bot.BotSettings;

import java.util.List;

public record PersistedRoomState(TablePersistenceState table, List<String> recentActionIds,
                                 List<BotSeat> bots, BotSettings botSettings) {
    public PersistedRoomState(TablePersistenceState table, List<String> ids) {
        this(table, ids, List.of(), BotSettings.DEFAULT);
    }
    public PersistedRoomState {
        recentActionIds = List.copyOf(recentActionIds);
        bots = bots == null ? List.of() : List.copyOf(bots);
        botSettings = botSettings == null ? BotSettings.DEFAULT : botSettings;
    }
}
