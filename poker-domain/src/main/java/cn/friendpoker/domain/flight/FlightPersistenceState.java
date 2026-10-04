package cn.friendpoker.domain.flight;

import java.util.List;
import java.util.Set;

public record FlightPersistenceState(
        List<FlightPlayerSnapshot> players,
        FlightPhase phase,
        long version,
        long gameNumber,
        long roundNumber,
        Integer currentSeat,
        Integer lastRoll,
        Set<Integer> completedSeats,
        List<FlightBoardItem> boardItems,
        long nextItemId,
        List<String> messages,
        String winnerId
) {
    public FlightPersistenceState {
        players = List.copyOf(players);
        completedSeats = Set.copyOf(completedSeats);
        boardItems = List.copyOf(boardItems);
        messages = List.copyOf(messages);
    }
}
