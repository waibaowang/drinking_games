package cn.friendpoker.domain.game;

import cn.friendpoker.domain.card.Card;

import java.util.ArrayList;
import java.util.List;

final class PlayerState {
    final String id;
    final String name;
    final int seat;
    long stack;
    long committedStreet;
    long committedHand;
    PlayerStatus status = PlayerStatus.ACTIVE;
    final List<Card> holeCards = new ArrayList<>(2);

    PlayerState(String id, String name, int seat, long stack) {
        this.id = id;
        this.name = name;
        this.seat = seat;
        this.stack = stack;
    }

    void prepareForHand() {
        committedStreet = 0;
        committedHand = 0;
        holeCards.clear();
        status = stack > 0 ? PlayerStatus.ACTIVE : PlayerStatus.SITTING_OUT;
    }

    long commit(long requested) {
        long paid = Math.min(requested, stack);
        stack -= paid;
        committedStreet += paid;
        committedHand += paid;
        if (stack == 0 && status == PlayerStatus.ACTIVE) {
            status = PlayerStatus.ALL_IN;
        }
        return paid;
    }
}

