package cn.friendpoker.domain.game;

import cn.friendpoker.domain.card.Card;

import java.util.List;

public record PlayerSnapshot(
        String id,
        String name,
        int seat,
        long stack,
        long committedStreet,
        long committedHand,
        PlayerStatus status,
        List<Card> cards,
        boolean dealer,
        boolean smallBlind,
        boolean bigBlind
) {
    public PlayerSnapshot {
        cards = List.copyOf(cards);
    }
}

