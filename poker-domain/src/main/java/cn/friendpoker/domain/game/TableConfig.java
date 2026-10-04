package cn.friendpoker.domain.game;

public record TableConfig(long smallBlind, long bigBlind, long initialStack, int maxPlayers, int actionSeconds) {
    public TableConfig {
        if (smallBlind <= 0 || bigBlind < smallBlind * 2) {
            throw new IllegalArgumentException("Big blind must be at least twice the small blind");
        }
        if (initialStack < bigBlind * 10) {
            throw new IllegalArgumentException("Initial stack must be at least ten big blinds");
        }
        if (maxPlayers < 2 || maxPlayers > 9) {
            throw new IllegalArgumentException("A table supports 2 to 9 players");
        }
        if (actionSeconds < 10 || actionSeconds > 120) {
            throw new IllegalArgumentException("Action time must be between 10 and 120 seconds");
        }
    }
}

