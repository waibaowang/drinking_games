package cn.friendpoker.server.flight;

import cn.friendpoker.domain.flight.FlightPersistenceState;

import java.util.List;

public record PersistedFlightRoomState(FlightPersistenceState game, List<String> recentActionIds) {
    public PersistedFlightRoomState {
        recentActionIds = List.copyOf(recentActionIds);
    }
}
