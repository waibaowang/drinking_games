package cn.friendpoker.domain.card;

import java.util.Objects;

public record Card(Rank rank, Suit suit) {
    public Card {
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(suit, "suit");
    }

    @Override
    public String toString() {
        return rank.symbol() + suit.symbol();
    }
}

