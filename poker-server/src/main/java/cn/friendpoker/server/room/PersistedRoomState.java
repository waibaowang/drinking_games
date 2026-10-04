package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.TablePersistenceState;

import java.util.List;

public record PersistedRoomState(TablePersistenceState table, List<String> recentActionIds) {
    public PersistedRoomState {
        recentActionIds = List.copyOf(recentActionIds);
    }
}
