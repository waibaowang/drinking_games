package cn.friendpoker.domain.flight;

import java.util.List;

public record FlightPlayerSnapshot(
        String id,
        String name,
        int seat,
        List<Integer> pieces,
        boolean turn
) {
    public FlightPlayerSnapshot {
        pieces = List.copyOf(pieces);
    }
}
