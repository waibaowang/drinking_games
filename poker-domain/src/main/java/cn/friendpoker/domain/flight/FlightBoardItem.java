package cn.friendpoker.domain.flight;

public record FlightBoardItem(long id, String type, int cellIndex, long spawnRound, long expiresAtRound) {
}
