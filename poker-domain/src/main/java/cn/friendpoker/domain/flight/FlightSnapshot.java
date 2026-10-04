package cn.friendpoker.domain.flight;

import java.util.List;

public record FlightSnapshot(
        long version,
        long gameNumber,
        long roundNumber,
        FlightPhase phase,
        Integer currentSeat,
        Integer lastRoll,
        List<Integer> legalPieceIndexes,
        List<FlightPlayerSnapshot> players,
        List<FlightBoardItem> boardItems,
        List<String> messages,
        String winnerId
) {
    public FlightSnapshot {
        legalPieceIndexes = List.copyOf(legalPieceIndexes);
        players = List.copyOf(players);
        boardItems = List.copyOf(boardItems);
        messages = List.copyOf(messages);
    }
}
