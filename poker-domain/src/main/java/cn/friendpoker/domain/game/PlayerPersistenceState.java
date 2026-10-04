package cn.friendpoker.domain.game;

import cn.friendpoker.domain.card.Card;

import java.util.List;

public record PlayerPersistenceState(
        String id,
        String name,
        int seat,
        long stack,
        long committedStreet,
        long committedHand,
        PlayerStatus status,
        List<Card> holeCards
) {
    public PlayerPersistenceState {
        holeCards = List.copyOf(holeCards);
    }
}
